package com.mobileclaw.agent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AgentTaskSubmissionRequest<T>(
    val sessionId: String,
    val taskType: TaskType,
    val foregroundRequested: Boolean = false,
    val worker: suspend CoroutineScope.() -> T,
)

sealed interface AgentTaskCompletion<out T> {
    data class Succeeded<T>(val value: T) : AgentTaskCompletion<T>
    data class Failed(val message: String) : AgentTaskCompletion<Nothing>
    data object Cancelled : AgentTaskCompletion<Nothing>
}

data class AgentTaskHandle<T>(val taskId: String, val completion: Deferred<AgentTaskCompletion<T>>)

sealed interface ExclusiveSubmissionResult<out T> {
    data class Accepted<T>(val handle: AgentTaskHandle<T>) : ExclusiveSubmissionResult<T>
    data class Busy(val conflictingTaskId: String) : ExclusiveSubmissionResult<Nothing>
}

/** The single application-level register/attach/run/complete lifecycle used by UI and Voice. */
class AgentTaskSubmissionService(
    private val controller: AgentTaskController,
    private val scope: CoroutineScope,
    private val foregroundRequester: (String) -> Unit,
) {
    constructor(context: Context, controller: AgentTaskController, scope: CoroutineScope) : this(
        controller, scope, { AgentExecutionForegroundService.requestProtection(context, it) },
    )

    fun <T> submit(request: AgentTaskSubmissionRequest<T>): AgentTaskHandle<T> {
        val registration = controller.register(request.sessionId, request.taskType, request.foregroundRequested)
        return launchRegistered(registration, request)
    }

    fun <T> trySubmitExclusive(request: AgentTaskSubmissionRequest<T>): ExclusiveSubmissionResult<T> {
        val registration = when (val attempt = controller.tryRegisterExclusiveTaskType(
            request.sessionId, request.taskType, request.foregroundRequested,
        )) {
            is AgentTaskController.RegistrationAttempt.Busy -> return ExclusiveSubmissionResult.Busy(attempt.conflictingTaskId)
            is AgentTaskController.RegistrationAttempt.Registered -> attempt.registration
        }
        return ExclusiveSubmissionResult.Accepted(launchRegistered(registration, request))
    }

    private fun <T> launchRegistered(
        registration: AgentTaskController.Registration,
        request: AgentTaskSubmissionRequest<T>,
    ): AgentTaskHandle<T> {
        val completion = CompletableDeferred<AgentTaskCompletion<T>>()
        val job: Job = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: AgentTaskCompletion<T>? = null
            var cancellation: CancellationException? = null
            try {
                controller.markRunning(registration)
                outcome = AgentTaskCompletion.Succeeded(request.worker(this))
            } catch (cancelled: CancellationException) {
                outcome = AgentTaskCompletion.Cancelled
                cancellation = cancelled
            } catch (failure: Throwable) {
                outcome = AgentTaskCompletion.Failed(failure.message?.take(160) ?: "The task failed.")
            } finally {
                controller.complete(registration)
            }
            completion.complete(requireNotNull(outcome))
            cancellation?.let { throw it }
        }
        if (!controller.attachJob(registration, job)) {
            job.cancel()
            completion.complete(AgentTaskCompletion.Cancelled)
        } else {
            if (request.foregroundRequested) foregroundRequester(registration.taskId)
            job.start()
        }
        return AgentTaskHandle(registration.taskId, completion)
    }
}
