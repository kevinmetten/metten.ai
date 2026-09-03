package com.mobileclaw.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class AgentTaskPhase { STARTING, RUNNING, CANCELLING }

enum class AgentCancellationReason {
    USER_REQUEST,
    SUPERSEDED_BY_NEW_TURN,
    SESSION_REPLACED,
    SERVICE_REQUEST,
    APP_SHUTDOWN,
}

data class ActiveAgentTask(
    val taskId: String,
    val registrationId: String,
    val sessionId: String,
    val taskType: TaskType,
    val startedAtMs: Long,
    val phase: AgentTaskPhase,
    val foregroundRequested: Boolean,
    val foregroundProtected: Boolean,
    val cancellationReason: AgentCancellationReason? = null,
)

class AgentTaskCancellationException(val reason: AgentCancellationReason) :
    CancellationException("Agent task cancelled: ${reason.name}")

/** Process-memory-only, application-scoped ownership for AgentRuntime jobs. */
class AgentTaskController(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    data class Registration(val taskId: String, val registrationId: String)
    sealed interface RegistrationAttempt {
        data class Registered(val registration: Registration) : RegistrationAttempt
        data class Busy(val conflictingTaskId: String) : RegistrationAttempt
    }

    private data class Entry(val task: ActiveAgentTask, val ordinal: Long, val job: Job? = null)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var nextOrdinal = 0L
    private val _activeTasks = MutableStateFlow<List<ActiveAgentTask>>(emptyList())
    val activeTasks: StateFlow<List<ActiveAgentTask>> = _activeTasks.asStateFlow()

    fun register(sessionId: String, taskType: TaskType, foregroundRequested: Boolean): Registration {
        val (registration, oldJobs) = synchronized(lock) {
            val jobs = entries.values.filter { it.task.sessionId == sessionId }.mapNotNull { old ->
                entries[old.task.taskId] = old.copy(task = old.task.copy(
                    phase = AgentTaskPhase.CANCELLING,
                    cancellationReason = AgentCancellationReason.SUPERSEDED_BY_NEW_TURN,
                ))
                old.job
            }
            val taskId = idFactory()
            val registrationId = idFactory()
            entries[taskId] = Entry(ActiveAgentTask(
                taskId = taskId,
                registrationId = registrationId,
                sessionId = sessionId,
                taskType = taskType,
                startedAtMs = clock(),
                phase = AgentTaskPhase.STARTING,
                foregroundRequested = foregroundRequested,
                foregroundProtected = false,
            ), ordinal = ++nextOrdinal)
            publishLocked()
            Registration(taskId, registrationId) to jobs
        }
        oldJobs.forEach { it.cancel(AgentTaskCancellationException(AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)) }
        return registration
    }

    /** Atomically checks and reserves an exclusive physical task type under the canonical lock. */
    fun tryRegisterExclusiveTaskType(
        sessionId: String,
        taskType: TaskType,
        foregroundRequested: Boolean,
    ): RegistrationAttempt {
        val (attempt, oldJobs) = synchronized(lock) {
            entries.values.firstOrNull { it.task.taskType == taskType }?.let {
                return@synchronized RegistrationAttempt.Busy(it.task.taskId) to emptyList()
            }
            val jobs = entries.values.filter { it.task.sessionId == sessionId }.mapNotNull { old ->
                entries[old.task.taskId] = old.copy(task = old.task.copy(
                    phase = AgentTaskPhase.CANCELLING,
                    cancellationReason = AgentCancellationReason.SUPERSEDED_BY_NEW_TURN,
                ))
                old.job
            }
            val taskId = idFactory()
            val registrationId = idFactory()
            entries[taskId] = Entry(ActiveAgentTask(
                taskId = taskId,
                registrationId = registrationId,
                sessionId = sessionId,
                taskType = taskType,
                startedAtMs = clock(),
                phase = AgentTaskPhase.STARTING,
                foregroundRequested = foregroundRequested,
                foregroundProtected = false,
            ), ordinal = ++nextOrdinal)
            publishLocked()
            RegistrationAttempt.Registered(Registration(taskId, registrationId)) to jobs
        }
        oldJobs.forEach { it.cancel(AgentTaskCancellationException(AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)) }
        return attempt
    }

    fun attachJob(registration: Registration, job: Job): Boolean = synchronized(lock) {
        val current = entries[registration.taskId]
        if (current?.task?.registrationId != registration.registrationId) return@synchronized false
        if (current.task.phase == AgentTaskPhase.CANCELLING) return@synchronized false
        entries[registration.taskId] = current.copy(job = job)
        true
    }

    fun markRunning(registration: Registration): Boolean = updateExact(registration) {
        it.copy(phase = AgentTaskPhase.RUNNING)
    }

    fun markForegroundProtected(taskId: String, protected: Boolean): Boolean = synchronized(lock) {
        val current = entries[taskId] ?: return@synchronized false
        entries[taskId] = current.copy(task = current.task.copy(foregroundProtected = protected))
        publishLocked()
        true
    }

    fun cancelTask(taskId: String, reason: AgentCancellationReason): Boolean {
        val job = synchronized(lock) {
            val current = entries[taskId] ?: return false
            if (current.task.phase == AgentTaskPhase.CANCELLING) return true
            entries[taskId] = current.copy(task = current.task.copy(
                phase = AgentTaskPhase.CANCELLING,
                cancellationReason = reason,
            ))
            publishLocked()
            current.job
        }
        job?.cancel(AgentTaskCancellationException(reason))
        return true
    }

    fun cancelSession(sessionId: String, reason: AgentCancellationReason): Boolean {
        val target = synchronized(lock) {
            entries.values.filter { it.task.sessionId == sessionId }
                .maxByOrNull { it.ordinal }?.task?.taskId
        } ?: return false
        return cancelTask(target, reason)
    }

    fun complete(registration: Registration): Boolean = synchronized(lock) {
        val current = entries[registration.taskId]
        if (current?.task?.registrationId != registration.registrationId) return@synchronized false
        entries.remove(registration.taskId)
        publishLocked()
        true
    }

    fun task(taskId: String): ActiveAgentTask? = synchronized(lock) { entries[taskId]?.task }

    fun sessionTask(sessionId: String): ActiveAgentTask? = synchronized(lock) {
        entries.values.filter { it.task.sessionId == sessionId }.maxByOrNull { it.ordinal }?.task
    }

    fun notificationTarget(): ActiveAgentTask? = synchronized(lock) {
        entries.values.filter { it.task.foregroundRequested }.maxByOrNull { it.ordinal }?.task
    }

    private fun updateExact(registration: Registration, transform: (ActiveAgentTask) -> ActiveAgentTask): Boolean = synchronized(lock) {
        val current = entries[registration.taskId]
        if (current?.task?.registrationId != registration.registrationId) return@synchronized false
        entries[registration.taskId] = current.copy(task = transform(current.task))
        publishLocked()
        true
    }

    private fun publishLocked() {
        _activeTasks.value = entries.values.map { it.task }
            .sortedWith(compareBy<ActiveAgentTask> { it.startedAtMs }.thenBy { it.taskId })
    }
}
