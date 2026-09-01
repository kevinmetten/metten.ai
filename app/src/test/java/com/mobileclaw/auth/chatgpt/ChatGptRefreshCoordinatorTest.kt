package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Test

class ChatGptRefreshCoordinatorTest {
    private val account = ChatGptAccountInfo(email = "test@example.invalid", chatGptAccountId = "account-a")
    private fun tokens(access: String = "old-access", refresh: String = "old-refresh", expiry: Long = 1) =
        ChatGptOAuthTokens(null, access, refresh, expiry)

    @Test fun `request forms and revoke shapes are exact`() {
        val exchange = ChatGptAuthRequests.exchange("code", "http://localhost:1455/auth/callback", "verifier").body as FormBody
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "code", "redirect_uri" to "http://localhost:1455/auth/callback", "client_id" to ChatGptOAuth.CLIENT_ID, "code_verifier" to "verifier"), exchange.fields())
        val refresh = ChatGptAuthRequests.refresh("refresh-secret").body as FormBody
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-secret", "client_id" to ChatGptOAuth.CLIENT_ID), refresh.fields())
        val refreshRevoke = ChatGptAuthRequests.revoke(tokens()).body!!.toStringBody()
        assertTrue(refreshRevoke.contains("client_id")); assertTrue(refreshRevoke.contains("refresh_token"))
        val accessRevoke = ChatGptAuthRequests.revoke(ChatGptOAuthTokens(null, "access", null, 1)).body!!.toStringBody()
        assertFalse(accessRevoke.contains("client_id")); assertTrue(accessRevoke.contains("access_token"))
    }

    @Test fun `transient and cancellation preserve session`() = runBlocking {
        for (failure in listOf(ChatGptRefreshException.Transient(), CancellationException())) {
            val repo = FakeRepository(tokens() to account)
            val coordinator = ChatGptRefreshCoordinator(FakeService { throw failure }, repo, repo.value, now = { 100 })
            val result = runCatching { coordinator.credentials() }
            assertSame(failure, result.exceptionOrNull())
            assertNotNull(coordinator.snapshot()); assertEquals(0, repo.destroyCount)
        }
    }

    @Test fun `permanent failure and account mismatch clear session`() = runBlocking {
        val invalidRepo = FakeRepository(tokens() to account)
        val invalid = ChatGptRefreshCoordinator(FakeService { throw ChatGptRefreshException.Permanent() }, invalidRepo, invalidRepo.value, now = { 100 })
        assertTrue(runCatching { invalid.credentials() }.exceptionOrNull() is ChatGptRefreshException.Permanent)
        assertNull(invalid.snapshot()); assertEquals(1, invalidRepo.destroyCount)

        val mismatchRepo = FakeRepository(tokens() to account)
        val mismatchAccess = jwt(mapOf("chatgpt_account_id" to "account-b"))
        val mismatch = ChatGptRefreshCoordinator(FakeService { TokenResponse(null, mismatchAccess, null, 3600) }, mismatchRepo, mismatchRepo.value, now = { 100 })
        assertTrue(runCatching { mismatch.credentials() }.exceptionOrNull() is ChatGptRefreshException.Permanent)
        assertNull(mismatch.snapshot())
    }

    @Test fun `missing access does not extend old token`() = runBlocking {
        val old = tokens(expiry = 150)
        val repo = FakeRepository(old to account)
        val coordinator = ChatGptRefreshCoordinator(FakeService { TokenResponse(null, null, "rotated", 7200) }, repo, repo.value, now = { 100 })
        assertTrue(runCatching { coordinator.credentials() }.exceptionOrNull() is ChatGptRefreshException.Transient)
        assertSame(old, coordinator.snapshot()!!.tokens); assertEquals(150, coordinator.snapshot()!!.tokens.accessTokenExpiresAt)
        assertEquals(0, repo.saveCount)
    }

    @Test fun `concurrent refresh is single flight and rotation persists once`() = runBlocking {
        val repo = FakeRepository(tokens() to account)
        val service = FakeService { delay(40); TokenResponse(null, "new-access", "new-refresh", 3600) }
        val coordinator = ChatGptRefreshCoordinator(service, repo, repo.value, now = { 100 })
        val results = List(8) { async { coordinator.credentials() } }.awaitAll()
        assertEquals(1, service.refreshCalls); assertEquals(1, repo.saveCount)
        assertTrue(results.all { it.accessToken == "new-access" })
        assertEquals("new-refresh", repo.value!!.first.refreshToken)
    }

    @Test fun `persistence failure never publishes new credentials`() = runBlocking {
        val old = tokens()
        val repo = FakeRepository(old to account, failSave = true)
        val coordinator = ChatGptRefreshCoordinator(FakeService { TokenResponse(null, "new-access", "new-refresh", 3600) }, repo, repo.value, now = { 100 })
        assertTrue(runCatching { coordinator.credentials() }.exceptionOrNull() is ChatGptRefreshException.Transient)
        assertSame(old, coordinator.snapshot()!!.tokens)
    }

    @Test fun `refresh completion after sign out cannot resurrect session`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val states = mutableListOf<ChatGptAuthState>()
        val repo = FakeRepository(tokens() to account)
        val service = FakeService { entered.complete(Unit); release.await(); TokenResponse(null, "stale-access", "stale-refresh", 3600) }
        val coordinator = ChatGptRefreshCoordinator(service, repo, repo.value, now = { 100 }, onState = states::add)
        val caller = async { runCatching { coordinator.credentials() } }
        entered.await()
        coordinator.destroy()
        val savesBeforeRelease = repo.saveCount
        states.clear()
        release.complete(Unit)
        assertTrue(caller.await().exceptionOrNull() is ChatGptSessionChangedException)
        assertNull(coordinator.snapshot()); assertNull(repo.value)
        assertEquals(savesBeforeRelease, repo.saveCount)
        assertTrue(states.none { it is ChatGptAuthState.SignedIn })
    }

    @Test fun `old account refresh cannot overwrite replacement login`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val states = mutableListOf<ChatGptAuthState>()
        val repo = FakeRepository(tokens() to account)
        val coordinator = ChatGptRefreshCoordinator(FakeService {
            entered.complete(Unit); release.await(); TokenResponse(null, "account-a-new-access", "account-a-new-refresh", 3600)
        }, repo, repo.value, now = { 100 }, onState = states::add)
        val caller = async { runCatching { coordinator.credentials() } }
        entered.await()
        coordinator.destroy()
        val accountB = ChatGptAccountInfo(email = "b@example.invalid", chatGptAccountId = "account-b")
        val tokensB = tokens("account-b-access", "account-b-refresh", 99_999)
        coordinator.replace(tokensB, accountB)
        states.clear()
        release.complete(Unit)
        assertTrue(caller.await().exceptionOrNull() is ChatGptSessionChangedException)
        assertSame(tokensB, coordinator.snapshot()!!.tokens); assertSame(tokensB, repo.value!!.first)
        assertTrue(states.none { it is ChatGptAuthState.SignedIn && it.account.chatGptAccountId == "account-a" })
    }

    @Test fun `durable destruction failure is reported and cannot claim success`() {
        val repo = FakeRepository(tokens() to account, failDestroy = true)
        val coordinator = ChatGptRefreshCoordinator(FakeService { error("unused") }, repo, repo.value)
        assertTrue(runCatching { coordinator.destroy() }.isFailure)
        assertNull(coordinator.snapshot())
        assertNotNull(repo.value)
    }

    @Test fun `credential destruction accepts either durable invalidation mechanism`() {
        ChatGptCredentialDestruction.requireSecure(preferencesCleared = true, keyDestroyed = false)
        ChatGptCredentialDestruction.requireSecure(preferencesCleared = false, keyDestroyed = true)
        assertTrue(runCatching { ChatGptCredentialDestruction.requireSecure(false, false) }.isFailure)
    }

    @Test fun `permanent invalidation reaches terminal error when destruction fails`() = runBlocking {
        val states = mutableListOf<ChatGptAuthState>()
        val repo = FakeRepository(tokens() to account, failDestroy = true)
        val coordinator = ChatGptRefreshCoordinator(
            FakeService { throw ChatGptRefreshException.Permanent() }, repo, repo.value,
            now = { 100 }, onState = states::add,
        )
        assertTrue(runCatching { coordinator.credentials() }.isFailure)
        val terminal = states.last()
        assertTrue(terminal is ChatGptAuthState.Error)
        assertEquals("Could not securely remove ChatGPT credentials.", (terminal as ChatGptAuthState.Error).message)
    }

    private class FakeRepository(overrideValue: Pair<ChatGptOAuthTokens, ChatGptAccountInfo>?, private val failSave: Boolean = false, private val failDestroy: Boolean = false) : ChatGptCredentialRepository {
        var value = overrideValue; var saveCount = 0; var destroyCount = 0
        override fun save(tokens: ChatGptOAuthTokens, account: ChatGptAccountInfo) { if (failSave) error("storage unavailable"); saveCount++; value = tokens to account }
        override fun load() = value
        override fun destroy() { destroyCount++; if (failDestroy) error("durable destruction unavailable"); value = null }
    }
    private class FakeService(private val refreshBlock: suspend () -> TokenResponse) : ChatGptAuthService {
        var refreshCalls = 0
        override suspend fun refresh(refreshToken: String): TokenResponse { refreshCalls++; return refreshBlock() }
        override suspend fun exchange(code: String, redirect: String, verifier: String) = error("unused")
        override suspend fun deviceCode(): DeviceCode = error("unused")
        override suspend fun pollDevice(id: String, code: String): DeviceToken? = error("unused")
        override suspend fun revoke(tokens: ChatGptOAuthTokens) = Unit
    }
    private fun FormBody.fields() = (0 until size).associate { name(it) to value(it) }
    private fun okhttp3.RequestBody.toStringBody(): String { val buffer = okio.Buffer(); writeTo(buffer); return buffer.readUtf8() }
    private fun jwt(payload: Any) = "x.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(com.google.gson.Gson().toJson(payload).toByteArray())}.x"
}
