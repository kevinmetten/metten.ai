package com.mobileclaw.ui

/** Exact, surface-specific run ownership; Aurora and the ordinary overlay never share a token. */
class RunSurfaceOwnership {
    private val agentOwners = mutableMapOf<String, String>()
    private val phoneOwners = mutableMapOf<String, String>()

    @Synchronized fun claimAgent(sessionId: String, taskId: String) { agentOwners[sessionId] = taskId }
    @Synchronized fun claimPhone(sessionId: String, taskId: String) { phoneOwners[sessionId] = taskId }
    @Synchronized fun releaseAgent(sessionId: String, taskId: String): Boolean = agentOwners.remove(sessionId, taskId)
    @Synchronized fun releasePhone(sessionId: String, taskId: String): Boolean = phoneOwners.remove(sessionId, taskId)
    @Synchronized fun releaseAgent(sessionId: String): String? = agentOwners.remove(sessionId)
    @Synchronized fun releasePhone(sessionId: String): String? = phoneOwners.remove(sessionId)
}
