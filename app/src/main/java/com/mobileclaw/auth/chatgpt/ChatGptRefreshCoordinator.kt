package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ChatGptSessionSnapshot(
    val generation: Long,
    val tokens: ChatGptOAuthTokens,
    val account: ChatGptAccountInfo,
)

internal class ChatGptRefreshCoordinator(
    private val service: ChatGptAuthService,
    private val repository: ChatGptCredentialRepository,
    initial: Pair<ChatGptOAuthTokens, ChatGptAccountInfo>?,
    private val now: () -> Long = System::currentTimeMillis,
    private val onState: (ChatGptAuthState) -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val sessionLock = Any()
    private var generation = 0L
    private var session = initial?.let { ChatGptSessionSnapshot(generation, it.first, it.second) }

    fun snapshot(): ChatGptSessionSnapshot? = synchronized(sessionLock) { session }

    suspend fun credentials(): ChatGptBackendCredentials = refreshMutex.withLock {
        val started = snapshot() ?: throw ChatGptRefreshException.Permanent()
        if (!ChatGptExpiration.requiresRefresh(started.tokens.accessTokenExpiresAt, now())) return@withLock started.backend()
        onStateIfCurrent(started, ChatGptAuthState.Refreshing(started.account))
        try {
            val refreshToken = started.tokens.refreshToken ?: throw ChatGptRefreshException.Permanent()
            val response = service.refresh(refreshToken)
            val newAccess = response.accessToken?.takeIf(String::isNotBlank)
                ?: throw ChatGptRefreshException.Transient("The authentication service did not return a fresh access token.")
            val tokens = ChatGptOAuthTokens(
                response.idToken ?: started.tokens.idToken,
                newAccess,
                response.refreshToken?.takeIf(String::isNotBlank) ?: refreshToken,
                ChatGptExpiration.resolve(now(), response.expiresIn, newAccess),
            )
            val extracted = ChatGptJwtMetadata.extract(response.idToken, newAccess)
            val oldId = started.account.chatGptAccountId
            val newId = extracted.chatGptAccountId
            if (oldId != null && newId != null && oldId != newId) throw ChatGptRefreshException.Permanent()
            val account = merge(started.account, extracted)
            val refreshed = synchronized(sessionLock) {
                ensureCurrent(started)
                repository.save(tokens, account)
                ensureCurrent(started)
                ChatGptSessionSnapshot(generation, tokens, account).also { session = it }
            }
            onStateIfCurrent(refreshed, ChatGptAuthState.SignedIn(account))
            synchronized(sessionLock) { ensureCurrent(refreshed); refreshed.backend() }
        } catch (e: ChatGptSessionChangedException) {
            throw e
        } catch (e: CancellationException) {
            onStateIfCurrent(started, ChatGptAuthState.SignedIn(started.account)); throw e
        } catch (e: ChatGptRefreshException.Permanent) {
            destroyIfCurrent(started)
            throw e
        } catch (e: ChatGptRefreshException.Transient) {
            onStateIfCurrent(started, ChatGptAuthState.SignedIn(started.account)); throw e
        } catch (_: Throwable) {
            onStateIfCurrent(started, ChatGptAuthState.SignedIn(started.account)); throw ChatGptRefreshException.Transient()
        }
    }

    fun replace(tokens: ChatGptOAuthTokens, account: ChatGptAccountInfo) {
        synchronized(sessionLock) {
            repository.save(tokens, account)
            generation++
            session = ChatGptSessionSnapshot(generation, tokens, account)
        }
    }

    fun destroy() {
        synchronized(sessionLock) {
            generation++
            session = null
            repository.destroy()
        }
    }

    private fun destroyIfCurrent(started: ChatGptSessionSnapshot) {
        synchronized(sessionLock) {
            ensureCurrent(started)
            generation++
            session = null
            repository.destroy()
        }
        onState(ChatGptAuthState.Error("Your ChatGPT session expired. Please sign in again."))
    }

    private fun ensureCurrent(started: ChatGptSessionSnapshot) {
        if (session !== started || generation != started.generation) throw ChatGptSessionChangedException()
    }

    private fun onStateIfCurrent(expected: ChatGptSessionSnapshot, state: ChatGptAuthState) {
        val current = synchronized(sessionLock) { session === expected && generation == expected.generation }
        if (current) onState(state)
    }

    private fun ChatGptSessionSnapshot.backend() = ChatGptBackendCredentials(tokens.accessToken, account.chatGptAccountId, account.computeResidency)
    private fun merge(old: ChatGptAccountInfo, new: ChatGptAccountInfo) = ChatGptAccountInfo(
        new.email ?: old.email, new.planType ?: old.planType, new.chatGptUserId ?: old.chatGptUserId,
        new.chatGptAccountId ?: old.chatGptAccountId, new.computeResidency ?: old.computeResidency,
        new.isFedRamp ?: old.isFedRamp,
    )
}
