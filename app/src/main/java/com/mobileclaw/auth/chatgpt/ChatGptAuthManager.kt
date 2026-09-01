package com.mobileclaw.auth.chatgpt

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

class ChatGptAuthManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ChatGptCredentialStore(context)
    private val api = ChatGptAuthApi()
    private val loginMutex = Mutex()
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow<ChatGptAuthState>(ChatGptAuthState.SignedOut)
    val state: StateFlow<ChatGptAuthState> = _state.asStateFlow()
    @Volatile private var credentials: Pair<ChatGptOAuthTokens, ChatGptAccountInfo>? = null
    private var loginJob: Job? = null
    private var listener: ServerSocket? = null

    init {
        credentials = store.load()
        credentials?.let { _state.value = ChatGptAuthState.SignedIn(it.second) }
    }

    fun signInWithBrowser() = startLogin {
        val socket = bindLoopback()
        listener = socket
        val redirect = "http://localhost:${socket.localPort}/auth/callback"
        val pkce = ChatGptPkce.generate()
        val oauthState = ChatGptOAuthProtocol.generateState()
        _state.value = ChatGptAuthState.AwaitingBrowser()
        openBrowser(ChatGptOAuthProtocol.authorizationUrl(redirect, pkce, oauthState))
        val code = withTimeout(5 * 60 * 1000L) { receiveCallback(socket, CallbackValidator(oauthState)) }
        complete(api.exchange(code, redirect, pkce.verifier))
    }

    fun signInWithDeviceCode() = startLogin {
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

    fun cancelLogin() {
        loginJob?.cancel()
        listener?.runCatching { close() }
        listener = null
        loginJob = null
        _state.value = credentials?.let { ChatGptAuthState.SignedIn(it.second) } ?: ChatGptAuthState.SignedOut
    }

    fun signOut() {
        cancelLogin()
        scope.launch {
            val old = credentials
            runCatching { old?.let { api.revoke(it.first) } }
            credentials = null
            store.clear()
            _state.value = ChatGptAuthState.SignedOut
        }
    }

    suspend fun getValidBackendCredentials(): ChatGptBackendCredentials = refreshMutex.withLock {
        var current = credentials ?: throw ChatGptAuthException("Your ChatGPT session has expired. Please sign in again.")
        if (ChatGptExpiration.requiresRefresh(current.first.accessTokenExpiresAt, System.currentTimeMillis())) {
            _state.value = ChatGptAuthState.Refreshing(current.second)
            val refresh = current.first.refreshToken ?: return@withLock expireSession()
            try {
                val response = api.refresh(refresh)
                val access = response.accessToken ?: current.first.accessToken
                val tokens = ChatGptOAuthTokens(response.idToken ?: current.first.idToken, access,
                    response.refreshToken ?: current.first.refreshToken,
                    ChatGptExpiration.resolve(System.currentTimeMillis(), response.expiresIn, access))
                val extracted = ChatGptJwtMetadata.extract(response.idToken, response.accessToken)
                val oldId = current.second.chatGptAccountId
                val newId = extracted.chatGptAccountId
                if (oldId != null && newId != null && oldId != newId) return@withLock expireSession()
                val account = mergeAccount(current.second, extracted)
                store.save(tokens, account)
                credentials = tokens to account
                current = tokens to account
                _state.value = ChatGptAuthState.SignedIn(account)
            } catch (_: Throwable) { return@withLock expireSession() }
        }
        ChatGptBackendCredentials(current.first.accessToken, current.second.chatGptAccountId, current.second.computeResidency)
    }

    private fun expireSession(): Nothing {
        credentials = null; store.clear(); _state.value = ChatGptAuthState.Error("Your ChatGPT session has expired. Please sign in again.")
        throw ChatGptAuthException("Your ChatGPT session has expired. Please sign in again.")
    }

    private fun startLogin(block: suspend () -> Unit) {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            loginMutex.withLock {
                _state.value = ChatGptAuthState.SigningIn
                try { block() }
                catch (_: TimeoutCancellationException) { _state.value = ChatGptAuthState.Error("ChatGPT sign-in timed out.") }
                catch (_: CancellationException) { }
                catch (e: ChatGptAuthException) { _state.value = ChatGptAuthState.Error(e.message ?: "Could not complete ChatGPT sign-in.") }
                catch (_: Throwable) { _state.value = ChatGptAuthState.Error("Could not reach the ChatGPT authentication service.") }
                finally { listener?.runCatching { close() }; listener = null; loginJob = null }
            }
        }
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
        try { store.save(tokens, account) } catch (_: Throwable) { throw ChatGptAuthException("Could not save credentials securely.") }
        credentials = tokens to account
        _state.value = ChatGptAuthState.SignedIn(account)
    }

    private fun openBrowser(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun mergeAccount(old: ChatGptAccountInfo, new: ChatGptAccountInfo) = ChatGptAccountInfo(new.email ?: old.email, new.planType ?: old.planType, new.chatGptUserId ?: old.chatGptUserId, new.chatGptAccountId ?: old.chatGptAccountId, new.computeResidency ?: old.computeResidency, new.isFedRamp ?: old.isFedRamp)
}
