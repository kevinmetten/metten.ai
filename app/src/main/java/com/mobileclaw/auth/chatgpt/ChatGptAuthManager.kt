package com.mobileclaw.auth.chatgpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

class ChatGptAuthManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ChatGptCredentialStore(context)
    private val api = ChatGptAuthApi()
    private val _state = MutableStateFlow<ChatGptAuthState>(ChatGptAuthState.SignedOut)
    val state: StateFlow<ChatGptAuthState> = _state.asStateFlow()
    private val refreshCoordinator: ChatGptRefreshCoordinator
    private var loginJob: Job? = null
    private var loginGeneration = 0L
    private var listener: Pair<Long, ServerSocket>? = null

    init {
        val restored = store.load()
        refreshCoordinator = ChatGptRefreshCoordinator(api, store, restored, onState = { _state.value = it })
        restored?.let { _state.value = ChatGptAuthState.SignedIn(it.second) }
    }

    fun signInWithBrowser() = startLogin { generation ->
        val socket = bindLoopback()
        synchronized(this) {
            if (loginGeneration != generation) { socket.close(); throw CancellationException() }
            listener = generation to socket
        }
        val redirect = "http://localhost:${socket.localPort}/auth/callback"
        val pkce = ChatGptPkce.generate()
        val oauthState = ChatGptOAuthProtocol.generateState()
        _state.value = ChatGptAuthState.AwaitingBrowser()
        openBrowser(ChatGptOAuthProtocol.authorizationUrl(redirect, pkce, oauthState))
        val code = withTimeout(5 * 60 * 1000L) { receiveCallback(socket, CallbackValidator(oauthState)) }
        complete(api.exchange(code, redirect, pkce.verifier))
    }

    fun signInWithDeviceCode() = startLogin { _ ->
        val device = api.deviceCode()
        _state.value = ChatGptAuthState.AwaitingDeviceCode(device.userCode)
        val result = withTimeout(15 * 60 * 1000L) {
            while (true) {
                delay(device.interval.coerceAtLeast(1) * 1000L + 3000L)
                api.pollDevice(device.deviceAuthId, device.userCode)?.let { return@withTimeout it }
            }
            error("unreachable")
        }
        complete(api.exchange(result.authorizationCode, ChatGptOAuth.DEVICE_REDIRECT_URI, result.codeVerifier))
    }

    fun openDeviceVerificationPage() = openBrowser(ChatGptOAuth.DEVICE_VERIFICATION_URL)

    @Synchronized fun cancelLogin() {
        loginGeneration++
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

    @Synchronized private fun startLogin(block: suspend (Long) -> Unit) {
        if (loginJob?.isActive == true) return
        val generation = ++loginGeneration
        val job = scope.launch {
                _state.value = ChatGptAuthState.SigningIn
                try { block(generation) }
                catch (_: TimeoutCancellationException) { _state.value = ChatGptAuthState.Error("ChatGPT sign-in timed out.") }
                catch (_: CancellationException) { }
                catch (e: ChatGptAuthException) { _state.value = ChatGptAuthState.Error(e.message ?: "Could not complete ChatGPT sign-in.") }
                catch (_: Throwable) { _state.value = ChatGptAuthState.Error("Could not reach the ChatGPT authentication service.") }
                finally { synchronized(this@ChatGptAuthManager) {
                    if (loginGeneration == generation) {
                        listener?.takeIf { it.first == generation }?.second?.runCatching { close() }
                        listener = null; loginJob = null
                    }
                } }
        }
        loginJob = job
    }

    private fun bindLoopback(): ServerSocket {
        for (port in ChatGptOAuth.CALLBACK_PORTS) runCatching {
            return ServerSocket(port, 1, InetAddress.getByName(ChatGptOAuth.LOOPBACK_ADDRESS))
        }
        throw ChatGptAuthException("Could not start the secure sign-in callback.")
    }

    private suspend fun receiveCallback(server: ServerSocket, validator: CallbackValidator): String = withContext(Dispatchers.IO) {
        server.accept().use { client ->
            val line = BufferedReader(InputStreamReader(client.getInputStream())).readLine().orEmpty()
            val target = line.split(' ').getOrNull(1).orEmpty()
            val uri = Uri.parse(target)
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
            val result = runCatching { validator.validate(uri.path.orEmpty(), params) }
            val ok = result.isSuccess
            val page = if (ok) "ChatGPT sign-in complete. You can close this page and return to MobileClaw." else "ChatGPT sign-in could not be completed. Return to MobileClaw and try again."
            val bytes = "<html><body><h2>$page</h2></body></html>".toByteArray()
            client.getOutputStream().write("HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            result.getOrThrow()
        }
    }

    private fun complete(response: TokenResponse) {
        val access = response.accessToken?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
        val refresh = response.refreshToken?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
        val tokens = ChatGptOAuthTokens(response.idToken, access, refresh, ChatGptExpiration.resolve(System.currentTimeMillis(), response.expiresIn, access))
        val account = ChatGptJwtMetadata.extract(response.idToken, access)
        try { refreshCoordinator.replace(tokens, account) } catch (_: Throwable) { throw ChatGptAuthException("Could not save credentials securely.") }
        _state.value = ChatGptAuthState.SignedIn(account)
    }

    private fun openBrowser(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
