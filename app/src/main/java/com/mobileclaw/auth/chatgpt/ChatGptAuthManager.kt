package com.mobileclaw.auth.chatgpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatGptAuthManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ChatGptCredentialStore(context)
    private val api = ChatGptAuthApi()
    private val _state = MutableStateFlow<ChatGptAuthState>(ChatGptAuthState.SignedOut)
    val state: StateFlow<ChatGptAuthState> = _state.asStateFlow()
    private val refreshCoordinator: ChatGptRefreshCoordinator
    private val loginGate = LoginAttemptGate()
    private var loginJob: Job? = null
    private var listener: Pair<Long, ChatGptLoopbackCallbackServer>? = null

    init {
        val restored = store.load()
        refreshCoordinator = ChatGptRefreshCoordinator(api, store, restored, onState = { _state.value = it })
        restored?.let { _state.value = ChatGptAuthState.SignedIn(it.second) }
    }

    fun signInWithBrowser() = startLogin { generation ->
        val callbackServer = ChatGptLoopbackCallbackServer.open(diagnostic = ::recordCallbackDiagnostic)
        Log.i("ChatGptAuth", "browser_callback_listener host=${callbackServer.listenerHost} port=${callbackServer.port}")
        synchronized(this) {
            if (!loginGate.isCurrent(generation)) { callbackServer.close(); throw CancellationException() }
            listener = generation to callbackServer
        }
        val redirect = callbackServer.redirectUri
        val pkce = ChatGptPkce.generate()
        val oauthState = ChatGptOAuthProtocol.generateState()
        _state.value = ChatGptAuthState.AwaitingBrowser()
        openBrowser(ChatGptOAuthProtocol.authorizationUrl(redirect, pkce, oauthState))
        val callback = withTimeout(5 * 60 * 1000L) { callbackServer.awaitCallback(CallbackValidator(oauthState)) }
        BrowserCallbackCompletion.complete(callback) {
            Log.i("ChatGptAuth", "browser_callback_stage=token_exchange_started")
            completeLogin(generation, api.exchange(callback.authorizationCode, redirect, pkce.verifier))
            Log.i("ChatGptAuth", "browser_callback_stage=credentials_committed")
        }
    }

    fun signInWithDeviceCode() = startLogin { generation ->
        val device = api.deviceCode()
        _state.value = ChatGptAuthState.AwaitingDeviceCode(device.userCode)
        val result = withTimeout(15 * 60 * 1000L) {
            DeviceCodePoller(api).poll(device)
        }
        completeLogin(generation, api.exchange(result.authorizationCode, ChatGptOAuth.DEVICE_REDIRECT_URI, result.codeVerifier))
    }

    fun openDeviceVerificationPage() = openBrowser(ChatGptOAuth.DEVICE_VERIFICATION_URL)

    @Synchronized fun cancelLogin() {
        loginGate.invalidate()
        val oldJob = loginJob
        val oldListener = listener
        loginJob = null; listener = null
        oldJob?.cancel()
        oldListener?.second?.runCatching { close() }
        _state.value = refreshCoordinator.snapshot()?.let { ChatGptAuthState.SignedIn(it.account) } ?: ChatGptAuthState.SignedOut
    }

    fun signOut() {
        cancelLogin()
        val old = refreshCoordinator.snapshot()?.tokens
        val destroyed = runCatching { refreshCoordinator.destroy() }
        _state.value = if (destroyed.isSuccess) ChatGptAuthState.SignedOut
            else ChatGptAuthState.Error("Could not securely remove ChatGPT credentials.")
        scope.launch { runCatching { old?.let { api.revoke(it) } } }
    }

    suspend fun getValidBackendCredentials(): ChatGptBackendCredentials = refreshCoordinator.credentials()

    /** Non-secret readiness snapshot; remains true while a saved session is refreshing. */
    fun hasUsableSession(): Boolean = refreshCoordinator.snapshot() != null

    @Synchronized private fun startLogin(block: suspend (Long) -> Unit) {
        if (loginJob?.isActive == true) return
        val generation = loginGate.begin()
        val job = scope.launch {
                _state.value = ChatGptAuthState.SigningIn
                try { block(generation) }
                catch (_: TimeoutCancellationException) { _state.value = ChatGptAuthState.Error("Timed out waiting for the browser sign-in callback.") }
                catch (_: CancellationException) { }
                catch (e: ChatGptCallbackException) { _state.value = ChatGptAuthState.Error(e.message ?: "Could not receive the browser sign-in callback.") }
                catch (e: ChatGptAuthException) { _state.value = ChatGptAuthState.Error(e.message ?: "Could not complete ChatGPT sign-in.") }
                catch (_: Throwable) { _state.value = ChatGptAuthState.Error("Could not reach the ChatGPT authentication service.") }
                finally { synchronized(this@ChatGptAuthManager) {
                    if (loginGate.isCurrent(generation)) {
                        listener?.takeIf { it.first == generation }?.second?.runCatching { close() }
                        listener = null; loginJob = null
                    }
                } }
        }
        loginJob = job
    }

    private fun completeLogin(generation: Long, response: TokenResponse) = loginGate.runIfCurrent(generation) {
        val access = response.accessToken?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
        val refresh = response.refreshToken?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
        val tokens = ChatGptOAuthTokens(response.idToken, access, refresh, ChatGptExpiration.resolve(System.currentTimeMillis(), response.expiresIn, access))
        val account = ChatGptJwtMetadata.extract(response.idToken, access)
        try { refreshCoordinator.replace(tokens, account) } catch (_: Throwable) { throw ChatGptAuthException("Could not save credentials securely.") }
        _state.value = ChatGptAuthState.SignedIn(account)
    }

    private fun openBrowser(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun recordCallbackDiagnostic(stage: CallbackDiagnostic) {
        Log.i("ChatGptAuth", "browser_callback_stage=${stage.name.lowercase()}")
    }
}
