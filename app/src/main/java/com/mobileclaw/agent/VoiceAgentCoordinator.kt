package com.mobileclaw.agent

import com.mobileclaw.permission.DeviceCapability
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoicePhoneTaskState { IDLE, STARTING, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
data class VoicePhoneTaskStatus(val taskId: String? = null, val state: VoicePhoneTaskState = VoicePhoneTaskState.IDLE, val summary: String = "No phone task is active.")
enum class VoiceSessionAdmission { ACTIVATED, ALREADY_ACTIVE, STALE_OR_ENDED }

/** Provider-independent owner of the one exact phone task belonging to the current Voice generation. */
class VoiceAgentCoordinator(
    private val scope: CoroutineScope,
    private val taskController: AgentTaskController,
    private val submissions: AgentTaskSubmissionService,
    private val readiness: (DeviceCapability) -> ReadinessLevel,
    private val beforeOwnershipPublication: suspend () -> Unit = {},
    private val phoneWorker: suspend (String) -> AgentResult,
) {
    private data class OwnedTask(
        val generation: Long,
        val requestId: String,
        val taskId: String,
        val goal: String,
        val completion: Deferred<AgentTaskCompletion<AgentResult>>,
        var terminalRequestId: String = requestId,
        var cancellation: VoiceControlCancellationDisposition? = null,
    )
    private val lock = Any()
    private val operationMutex = Mutex()
    private var generation = 0L
    private var generationActive = false
    private var sink: VoiceControlEventSink? = null
    private var owned: OwnedTask? = null
    private var priorGenerationCompletion: Deferred<AgentTaskCompletion<AgentResult>>? = null
    private val handled = LinkedHashMap<String, VoiceControlEvent>()
    private val pending = mutableSetOf<String>()
    private val _status = MutableStateFlow(VoicePhoneTaskStatus())
    val status: StateFlow<VoicePhoneTaskStatus> = _status.asStateFlow()

    fun beginSession(newGeneration: Long, newSink: VoiceControlEventSink): VoiceSessionAdmission {
        val prior = synchronized(lock) {
            if (newGeneration < generation) return VoiceSessionAdmission.STALE_OR_ENDED
            if (newGeneration == generation) return if (generationActive) VoiceSessionAdmission.ALREADY_ACTIVE else VoiceSessionAdmission.STALE_OR_ENDED
            val old = owned?.takeIf { it.generation != newGeneration }?.also {
                it.cancellation = VoiceControlCancellationDisposition.SESSION_ENDED
            }
            generation = newGeneration
            generationActive = true
            sink = newSink
            handled.clear()
            pending.clear()
            priorGenerationCompletion = old?.completion
            _status.value = VoicePhoneTaskStatus()
            old
        }
        prior?.let {
            taskController.cancelTask(it.taskId, AgentCancellationReason.SESSION_REPLACED)
        }
        return VoiceSessionAdmission.ACTIVATED
    }

    fun endSession(endedGeneration: Long) {
        val target = synchronized(lock) {
            if (generation != endedGeneration) return
            generationActive = false
            sink = null
            handled.clear()
            pending.clear()
            owned?.takeIf { it.generation == endedGeneration }?.also {
                it.cancellation = VoiceControlCancellationDisposition.SESSION_ENDED
                priorGenerationCompletion = it.completion
            }
        }
        target?.let {
            _status.value = VoicePhoneTaskStatus(it.taskId, VoicePhoneTaskState.CANCELLING, "Cancelling phone task.")
            taskController.cancelTask(it.taskId, AgentCancellationReason.USER_REQUEST)
        }
    }

    fun accept(request: VoiceControlRequest) {
        val duplicate = synchronized(lock) {
            if (request.generation != generation || !generationActive || sink == null) return
            handled[request.requestId]?.let { return@synchronized sink to it }
            if (!pending.add(request.requestId)) return
            if (handled.size >= MAX_DELEGATIONS) handled.remove(handled.keys.first())
            // Reserve before launching so duplicate callbacks cannot race into two actions.
            null
        }
        duplicate?.let { (target, event) -> target?.send(event); return }
        scope.launch { operationMutex.withLock { process(request) } }
    }

    private suspend fun process(request: VoiceControlRequest) {
        when (val command = request.command) {
            is VoiceControlCommand.Start -> start(request, command.goal)
            VoiceControlCommand.Status -> reportStatus(request)
            VoiceControlCommand.Cancel -> cancel(request)
            is VoiceControlCommand.Replace -> replace(request, command.goal)
        }
    }

    private suspend fun start(request: VoiceControlRequest, goal: String) {
        val barrier = synchronized(lock) { priorGenerationCompletion }
        barrier?.await()
        if (barrier != null) synchronized(lock) {
            if (priorGenerationCompletion === barrier) priorGenerationCompletion = null
        }
        if (!isCurrent(request.generation)) return
        if (readiness(DeviceCapability.PHONE_CONTROL) == ReadinessLevel.BLOCKED ||
            readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL) == ReadinessLevel.BLOCKED) {
            reply(VoiceControlEvent.Rejected(request.generation, request.requestId, "PHONE_CONTROL_NOT_READY"))
            return
        }
        val submission = submissions.reserveExclusive(AgentTaskSubmissionRequest(
            sessionId = "voice:${request.generation}:${request.requestId}",
            taskType = TaskType.PHONE_CONTROL,
            foregroundRequested = true,
        ) { phoneWorker(goal) })
        val prepared = when (submission) {
            is ExclusiveSubmissionResult.Reserved -> submission.task
            is ExclusiveSubmissionResult.Busy -> {
                reply(VoiceControlEvent.Rejected(request.generation, request.requestId, "PHONE_BUSY"))
                return
            }
        }
        beforeOwnershipPublication()
        val task = OwnedTask(request.generation, request.requestId, prepared.taskId, goal.take(120), prepared.completion)
        val claimed = synchronized(lock) {
            if (generation == request.generation && generationActive && sink != null && owned?.let { it.generation == generation } != true) {
                owned = task
                true
            } else false
        }
        if (!claimed) {
            prepared.cancel(AgentCancellationReason.SESSION_REPLACED)
            return
        }
        if (!prepared.start()) {
            synchronized(lock) { if (owned?.taskId == task.taskId) owned = null }
            return
        }
        if (!synchronized(lock) { generation == task.generation && generationActive && sink != null && owned?.taskId == task.taskId }) return
        _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.STARTING, goal.take(120))
        reply(VoiceControlEvent.Accepted(request.generation, request.requestId, task.taskId))
        scope.launch { observeCompletion(task) }
    }

    private suspend fun observeCompletion(task: OwnedTask) {
        if (synchronized(lock) { generation == task.generation && owned?.taskId == task.taskId }) {
            _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.RUNNING, task.goal)
        }
        val completion = task.completion.await()
        val (state, summary) = when (completion) {
            is AgentTaskCompletion.Succeeded -> if (completion.value.success) VoicePhoneTaskState.SUCCEEDED to safe(completion.value.summary) else VoicePhoneTaskState.FAILED to safe(completion.value.summary)
            is AgentTaskCompletion.Failed -> VoicePhoneTaskState.FAILED to safe(completion.message)
            AgentTaskCompletion.Cancelled -> VoicePhoneTaskState.CANCELLED to "Phone task cancelled."
        }
        val publish = synchronized(lock) {
            if (generation == task.generation && owned?.taskId == task.taskId) {
                owned = null
                true
            } else false
        }
        if (publish) _status.value = VoicePhoneTaskStatus(task.taskId, state, summary)
        if (publish && isCurrent(task.generation) && task.cancellation != VoiceControlCancellationDisposition.SUPERSEDED) {
            val terminal = when (state) {
                VoicePhoneTaskState.SUCCEEDED -> VoiceControlTerminalState.SUCCEEDED
                VoicePhoneTaskState.CANCELLED -> VoiceControlTerminalState.CANCELLED
                else -> VoiceControlTerminalState.FAILED
            }
            reply(VoiceControlEvent.Completed(task.generation, task.terminalRequestId, task.taskId, terminal, summary, task.cancellation))
        }
    }

    private fun reportStatus(request: VoiceControlRequest) {
        val value = _status.value
        reply(VoiceControlEvent.Status(request.generation, request.requestId, value))
    }

    private suspend fun cancel(request: VoiceControlRequest) {
        val task = synchronized(lock) { owned?.takeIf { it.generation == request.generation } }
        if (task == null) { reportStatus(request); return }
        _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.CANCELLING, "Cancelling phone task.")
        synchronized(lock) {
            if (owned?.taskId == task.taskId) {
                task.terminalRequestId = request.requestId
                task.cancellation = VoiceControlCancellationDisposition.USER_REQUEST
            }
        }
        taskController.cancelTask(task.taskId, AgentCancellationReason.USER_REQUEST)
        task.completion.await()
    }

    private suspend fun replace(request: VoiceControlRequest, goal: String) {
        val old = synchronized(lock) { owned?.takeIf { it.generation == request.generation } }
        if (old == null) { start(request, goal); return }
        _status.value = VoicePhoneTaskStatus(old.taskId, VoicePhoneTaskState.CANCELLING, "Replacing phone task.")
        synchronized(lock) { if (owned?.taskId == old.taskId) old.cancellation = VoiceControlCancellationDisposition.SUPERSEDED }
        taskController.cancelTask(old.taskId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        old.completion.await()
        if (isCurrent(request.generation)) start(request, goal)
    }

    private fun reply(message: VoiceControlEvent) {
        val target = synchronized(lock) {
            if (generation != message.generation || !generationActive || sink == null) return
            handled[message.requestId] = message
            pending.remove(message.requestId)
            sink
        }
        target?.send(message)
    }
    private fun isCurrent(value: Long) = synchronized(lock) { generation == value && generationActive && sink != null }
    private fun safe(value: String) = value.filter { it >= ' ' && it != '\u007f' }.trim().take(240).ifBlank { "Phone task finished without details." }
    private companion object { const val MAX_DELEGATIONS = 128 }
}
