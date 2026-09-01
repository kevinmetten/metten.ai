package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ChatGptRefreshCoordinator(
    private val service: ChatGptAuthService,
    private val repository: ChatGptCredentialRepository,
    initial: Pair<ChatGptOAuthTokens, ChatGptAccountInfo>?,
    private val now: () -> Long = System::currentTimeMillis,
    private val onState: (ChatGptAuthState) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile var current = initial
        private set

    suspend fun credentials(): ChatGptBackendCredentials = mutex.withLock {
        var existing = current ?: throw ChatGptRefreshException.Permanent()
        if (ChatGptExpiration.requiresRefresh(existing.first.accessTokenExpiresAt, now())) {
            onState(ChatGptAuthState.Refreshing(existing.second))
            try {
                val refreshToken = existing.first.refreshToken ?: throw ChatGptRefreshException.Permanent()
                val response = service.refresh(refreshToken)
                val newAccess = response.accessToken?.takeIf(String::isNotBlank)
                    ?: throw ChatGptRefreshException.Transient("The authentication service did not return a fresh access token.")
                val tokens = ChatGptOAuthTokens(
                    response.idToken ?: existing.first.idToken,
                    newAccess,
                    response.refreshToken?.takeIf(String::isNotBlank) ?: refreshToken,
                    ChatGptExpiration.resolve(now(), response.expiresIn, newAccess),
                )
                val extracted = ChatGptJwtMetadata.extract(response.idToken, newAccess)
                val oldId = existing.second.chatGptAccountId
                val newId = extracted.chatGptAccountId
                if (oldId != null && newId != null && oldId != newId) throw ChatGptRefreshException.Permanent()
                val account = merge(existing.second, extracted)
                repository.save(tokens, account)
                existing = tokens to account
                current = existing
                onState(ChatGptAuthState.SignedIn(account))
            } catch (e: CancellationException) {
                onState(ChatGptAuthState.SignedIn(existing.second)); throw e
            } catch (e: ChatGptRefreshException.Permanent) {
                current = null; repository.clear(); onState(ChatGptAuthState.Error(e.message!!)); throw e
            } catch (e: ChatGptRefreshException.Transient) {
                onState(ChatGptAuthState.SignedIn(existing.second)); throw e
            } catch (_: Throwable) {
                onState(ChatGptAuthState.SignedIn(existing.second)); throw ChatGptRefreshException.Transient()
            }
        }
        if (ChatGptExpiration.requiresRefresh(existing.first.accessTokenExpiresAt, now()))
            throw ChatGptRefreshException.Transient("No usable ChatGPT access token is available.")
        ChatGptBackendCredentials(existing.first.accessToken, existing.second.chatGptAccountId, existing.second.computeResidency)
    }

    fun replace(tokens: ChatGptOAuthTokens, account: ChatGptAccountInfo) { current = tokens to account }
    fun clear() { current = null }

    private fun merge(old: ChatGptAccountInfo, new: ChatGptAccountInfo) = ChatGptAccountInfo(
        new.email ?: old.email, new.planType ?: old.planType, new.chatGptUserId ?: old.chatGptUserId,
        new.chatGptAccountId ?: old.chatGptAccountId, new.computeResidency ?: old.computeResidency,
        new.isFedRamp ?: old.isFedRamp,
    )
}
