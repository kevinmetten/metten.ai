package com.mobileclaw.agent

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

data class AgentTaskSubmissionRequest<T>(
    val sessionId: String,
    val taskType: TaskType,
    val foregroundRequested: Boolean = false,
    val worker: suspend CoroutineScope.(taskId: String) -> T,
)

sealed interface AgentTaskCompletion<out T> {
    data class Succeeded<T>(val value: T) : AgentTaskCompletion<T>
    data class Failed(val message: String) : AgentTaskCompletion<Nothing>
    data object Cancelled : AgentTaskCompletion<Nothing>
}

class PreparedAgentTask<T> internal constructor(
    val taskId: String,
    val completion: Deferred<AgentTaskCompletion<T>>,
    private val starter: () -> Boolean,
    private val canceller: (AgentCancellationReason) -> Boolean,
) {
    fun start(): Boolean = starter()
    fun cancel(reason: AgentCancellationReason): Boolean = canceller(reason)
}

data class AgentTaskHandle<T>(val taskId: String, val completion: Deferred<AgentTaskCompletion<T>>)

sealed interface ExclusiveSubmissionResult<out T> {
    data class Reserved<T>(val task: PreparedAgentTask<T>) : ExclusiveSubmissionResult<T>
    data class Busy(val conflictingTaskId: String, val conflictingSessionId: String, val phase: AgentTaskPhase) : ExclusiveSubmissionResult<Nothing>
}

/** The single application-level reserve/attach/start/complete lifecycle used by UI and Voice. */
class AgentTaskSubmissionService(
    private val controller: AgentTaskController,
    private val scope: CoroutineScope,
    private val foregroundRequester: (String) -> Unit,
) {
    constructor(context: Context, controller: AgentTaskController, scope: CoroutineScope) : this(
        controller, scope, { AgentExecutionForegroundService.requestProtection(context, it) },
    )

    fun <T> submit(request: AgentTaskSubmissionRequest<T>): AgentTaskHandle<T> {
        val prepared = prepare(controller.register(request.sessionId, request.taskType, request.foregroundRequested), request)
        prepared.start()
        return AgentTaskHandle(prepared.taskId, prepared.completion)
    }

    fun <T> reserveExclusive(request: AgentTaskSubmissionRequest<T>): ExclusiveSubmissionResult<T> =
        when (val attempt = controller.tryRegisterExclusiveTaskType(request.sessionId, request.taskType, request.foregroundRequested)) {
            is AgentTaskController.RegistrationAttempt.Busy -> ExclusiveSubmissionResult.Busy(
                attempt.task.taskId, attempt.task.sessionId, attempt.task.phase,
            )
            is AgentTaskController.RegistrationAttempt.Registered -> ExclusiveSubmissionResult.Reserved(prepare(attempt.registration, request))
        }

    fun <T> trySubmitExclusive(request: AgentTaskSubmissionRequest<T>): ExclusiveSubmissionResult<T> =
        reserveExclusive(request).also { if (it is ExclusiveSubmissionResult.Reserved) it.task.start() }

    /** Waits only for an exact same-session predecessor already losing cancellation; unrelated owners stay busy. */
    suspend fun <T> reserveExclusiveAfterSameSessionRelease(
        request: AgentTaskSubmissionRequest<T>,
        isValid: () -> Boolean,
    ): ExclusiveSubmissionResult<T>? {
        while (isValid()) {
            currentCoroutineContext().ensureActive()
            when (val admission = reserveExclusive(request)) {
                is ExclusiveSubmissionResult.Reserved -> return admission
                is ExclusiveSubmissionResult.Busy -> {
                    if (admission.conflictingSessionId != request.sessionId || admission.phase != AgentTaskPhase.CANCELLING) {
                        return admission
                    }
                    controller.activeTasks.first { tasks -> tasks.none { it.taskId == admission.conflictingTaskId } }
                }
            }
        }
        return null
    }

    private fun <T> prepare(
        registration: AgentTaskController.Registration,
        request: AgentTaskSubmissionRequest<T>,
    ): PreparedAgentTask<T> {
        val completion = CompletableDeferred<AgentTaskCompletion<T>>()
        val outcome = AtomicReference<AgentTaskCompletion<T>?>(null)
        val startClaimed = AtomicBoolean(false)
        val job: Job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                outcome.compareAndSet(null, AgentTaskCompletion.Succeeded(request.worker(this, registration.taskId)))
            } catch (cancelled: CancellationException) {
                outcome.compareAndSet(null, AgentTaskCompletion.Cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                outcome.compareAndSet(null, AgentTaskCompletion.Failed(failure.message?.take(160) ?: "The task failed."))
            }
        }
        job.invokeOnCompletion { cause ->
            val terminal = outcome.get() ?: if (cause is CancellationException) AgentTaskCompletion.Cancelled
                else AgentTaskCompletion.Failed(cause?.message?.take(160) ?: "The task did not start.")
            controller.complete(registration)
            completion.complete(terminal)
        }
        if (!controller.attachJob(registration, job)) {
            job.cancel(AgentTaskCancellationException(AgentCancellationReason.USER_REQUEST))
        }
        return PreparedAgentTask(
            taskId = registration.taskId,
            completion = completion,
            starter = {
                if (!startClaimed.compareAndSet(false, true)) false
                else if (!controller.markRunning(registration)) {
                    job.cancel(AgentTaskCancellationException(AgentCancellationReason.USER_REQUEST))
                    false
                } else {
                    if (request.foregroundRequested) foregroundRequester(registration.taskId)
                    if (!job.start()) {
                        job.cancel(AgentTaskCancellationException(AgentCancellationReason.USER_REQUEST))
                        false
                    } else true
                }
            },
            canceller = { reason -> controller.cancelTask(registration.taskId, reason) },
        )
    }
}
