package com.mobileclaw.agent

import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.mobileclaw.permission.DeviceCapability
import com.mobileclaw.permission.ReadinessLevel
import com.mobileclaw.realtime.RealtimeDelegationChannel
import com.mobileclaw.realtime.RealtimeDelegationRequest
import com.mobileclaw.realtime.RealtimeDelegationSink
import com.mobileclaw.realtime.RealtimeDelegationUpdate
import com.mobileclaw.realtime.VoiceControlEnvelope
import com.mobileclaw.realtime.VoiceControlEnvelopeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

enum class VoicePhoneTaskState { IDLE, STARTING, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
data class VoicePhoneTaskStatus(val taskId: String? = null, val state: VoicePhoneTaskState = VoicePhoneTaskState.IDLE, val summary: String = "No phone task is active.")

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
        val delegationId: String,
        val taskId: String,
        val goal: String,
        val completion: Deferred<AgentTaskCompletion<AgentResult>>,
    )
    private val lock = Any()
    private val operationMutex = Mutex()
    private var generation = 0L
    private var sink: RealtimeDelegationSink? = null
    private var owned: OwnedTask? = null
    private var priorGenerationCompletion: Deferred<AgentTaskCompletion<AgentResult>>? = null
    private val handled = LinkedHashMap<String, RealtimeDelegationUpdate>()
    private val _status = MutableStateFlow(VoicePhoneTaskStatus())
    val status: StateFlow<VoicePhoneTaskStatus> = _status.asStateFlow()

    fun beginSession(newGeneration: Long, newSink: RealtimeDelegationSink) {
        val prior = synchronized(lock) {
            val old = owned?.takeIf { it.generation != newGeneration }
            generation = newGeneration
            sink = newSink
            handled.clear()
            priorGenerationCompletion = old?.completion
            _status.value = VoicePhoneTaskStatus()
            old
        }
        prior?.let { taskController.cancelTask(it.taskId, AgentCancellationReason.SESSION_REPLACED) }
    }

    fun endSession(endedGeneration: Long) {
        val target = synchronized(lock) {
            if (generation != endedGeneration) return
            sink = null
            handled.clear()
            owned?.takeIf { it.generation == endedGeneration }
        }
        target?.let {
            _status.value = VoicePhoneTaskStatus(it.taskId, VoicePhoneTaskState.CANCELLING, "Cancelling phone task.")
            taskController.cancelTask(it.taskId, AgentCancellationReason.USER_REQUEST)
        }
    }

    fun accept(request: RealtimeDelegationRequest) {
        synchronized(lock) {
            if (request.voiceSessionGeneration != generation || sink == null) return
            handled[request.delegationId]?.let { sink?.send(it); return }
            if (handled.size >= MAX_DELEGATIONS) handled.remove(handled.keys.first())
            // Reserve before launching so duplicate callbacks cannot race into two actions.
            handled[request.delegationId] = update(request, "{\"op\":\"control_received\"}", RealtimeDelegationChannel.COMMENTARY)
        }
        diagnostic("delegation delivered to coordinator")
        scope.launch { operationMutex.withLock { process(request) } }
    }

    private suspend fun process(request: RealtimeDelegationRequest) {
        when (val envelope = VoiceControlEnvelopeParser.parse(request.instructionText)) {
            is VoiceControlEnvelope.Start -> start(request, envelope.goal)
            VoiceControlEnvelope.Status -> reportStatus(request)
            VoiceControlEnvelope.Cancel -> cancel(request)
            is VoiceControlEnvelope.Replace -> replace(request, envelope.goal)
            null -> reply(request, json("phone_control_rejected", "reason" to "MALFORMED_CONTROL"), RealtimeDelegationChannel.SPEAKABLE)
        }
    }

    private suspend fun start(request: RealtimeDelegationRequest, goal: String) {
        synchronized(lock) { priorGenerationCompletion }?.await()
        if (!isCurrent(request.voiceSessionGeneration)) return
        if (readiness(DeviceCapability.PHONE_CONTROL) == ReadinessLevel.BLOCKED ||
            readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL) == ReadinessLevel.BLOCKED) {
            reply(request, json("phone_control_not_ready", "reason" to "PHONE_CONTROL_NOT_READY"), RealtimeDelegationChannel.SPEAKABLE)
            return
        }
        val submission = submissions.reserveExclusive(AgentTaskSubmissionRequest(
            sessionId = "voice:${request.voiceSessionGeneration}:${request.delegationId}",
            taskType = TaskType.PHONE_CONTROL,
            foregroundRequested = true,
        ) { phoneWorker(goal) })
        val prepared = when (submission) {
            is ExclusiveSubmissionResult.Reserved -> submission.task.also { diagnostic("phone task reserved") }
            is ExclusiveSubmissionResult.Busy -> {
                reply(request, json("phone_task_rejected", "reason" to "PHONE_BUSY"), RealtimeDelegationChannel.SPEAKABLE)
                return
            }
        }
        beforeOwnershipPublication()
        val task = OwnedTask(request.voiceSessionGeneration, request.delegationId, prepared.taskId, goal.take(120), prepared.completion)
        val claimed = synchronized(lock) {
            if (generation == request.voiceSessionGeneration && sink != null && owned?.let { it.generation == generation } != true) {
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
        diagnostic("phone worker started")
        if (!synchronized(lock) { generation == task.generation && sink != null && owned?.taskId == task.taskId }) return
        _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.STARTING, goal.take(120))
        reply(request, json("phone_task_started", "task_id" to task.taskId, "state" to "STARTING"), RealtimeDelegationChannel.COMMENTARY)
        scope.launch { observeCompletion(task) }
    }

    private suspend fun observeCompletion(task: OwnedTask) {
        if (synchronized(lock) { generation == task.generation && owned?.taskId == task.taskId }) {
            _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.RUNNING, task.goal)
        }
        val completion = task.completion.await()
        diagnostic("phone task terminal result")
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
        if (publish && isCurrent(task.generation)) reply(
            RealtimeDelegationRequest(task.generation, task.delegationId, ""),
            json("phone_task_result", "task_id" to task.taskId, "state" to state.name, "summary" to summary),
            RealtimeDelegationChannel.SPEAKABLE,
        )
    }

    private fun reportStatus(request: RealtimeDelegationRequest) {
        val value = _status.value
        reply(request, json("phone_task_status", "task_id" to value.taskId, "state" to value.state.name, "summary" to value.summary), RealtimeDelegationChannel.SPEAKABLE)
    }

    private suspend fun cancel(request: RealtimeDelegationRequest) {
        val task = synchronized(lock) { owned?.takeIf { it.generation == request.voiceSessionGeneration } }
        if (task == null) { reportStatus(request); return }
        _status.value = VoicePhoneTaskStatus(task.taskId, VoicePhoneTaskState.CANCELLING, "Cancelling phone task.")
        taskController.cancelTask(task.taskId, AgentCancellationReason.USER_REQUEST)
        task.completion.await()
        if (isCurrent(request.voiceSessionGeneration)) reply(request, json("phone_task_cancelled", "task_id" to task.taskId, "state" to "CANCELLED"), RealtimeDelegationChannel.SPEAKABLE)
    }

    private suspend fun replace(request: RealtimeDelegationRequest, goal: String) {
        val old = synchronized(lock) { owned?.takeIf { it.generation == request.voiceSessionGeneration } }
        if (old == null) { start(request, goal); return }
        _status.value = VoicePhoneTaskStatus(old.taskId, VoicePhoneTaskState.CANCELLING, "Replacing phone task.")
        taskController.cancelTask(old.taskId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        old.completion.await()
        if (isCurrent(request.voiceSessionGeneration)) start(request, goal)
    }

    private fun reply(request: RealtimeDelegationRequest, text: String, channel: RealtimeDelegationChannel) {
        val message = update(request, text, channel)
        synchronized(lock) {
            if (generation != request.voiceSessionGeneration || sink == null) return
            handled[request.delegationId] = message
            val sent = sink?.send(message) == true
            diagnostic(if (sent) "context append accepted for send" else "context append send failed")
        }
    }
    private fun isCurrent(value: Long) = synchronized(lock) { generation == value && sink != null }
    private fun update(r: RealtimeDelegationRequest, text: String, channel: RealtimeDelegationChannel) = RealtimeDelegationUpdate(r.voiceSessionGeneration, r.delegationId, text, channel)
    private fun json(op: String, vararg values: Pair<String, String?>) = JsonObject().apply { addProperty("op", op); values.forEach { (k, v) -> if (v == null) add(k, JsonNull.INSTANCE) else addProperty(k, v) } }.toString()
    private fun safe(value: String) = value.filter { it >= ' ' && it != '\u007f' }.trim().take(240).ifBlank { "Phone task finished without details." }
    private fun diagnostic(event: String) = runCatching { Log.i("MobileClawVoice", event) }.let { Unit }
    private companion object { const val MAX_DELEGATIONS = 128 }
}
