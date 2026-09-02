package com.mobileclaw.auth.chatgpt

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class CallbackDiagnostic {
    LISTENER_STARTED, IPV4_LISTENER_STARTED, IPV6_LISTENER_STARTED, IPV6_UNAVAILABLE,
    BROWSER_OPENED, CONNECTION_IPV4, CONNECTION_IPV6, IRRELEVANT_ROUTE, MALFORMED_REQUEST,
    CLIENT_TIMEOUT, OAUTH_CALLBACK_RECEIVED, STATE_VALID, CALLBACK_REJECTED,
    TOKEN_EXCHANGE_STARTED, CREDENTIALS_COMMITTED, LISTENER_CLOSED, OVERALL_TIMEOUT,
}

internal class ChatGptCallbackException(message: String) : ChatGptAuthException(message)

/** One logical localhost HTTP server backed exclusively by available loopback families. */
internal class ChatGptLoopbackCallbackServer private constructor(
    private val servers: List<ServerSocket>,
    private val connectionReadTimeoutMs: Int,
    private val queuePollTimeoutMs: Long,
    private val diagnostic: (CallbackDiagnostic) -> Unit,
) : AutoCloseable {
    val port: Int = servers.first().localPort
    val redirectUri: String = "http://localhost:$port/auth/callback"
    val boundLoopbackAddresses: List<String> = servers.map { it.inetAddress.hostAddress }
    val isLoopbackOnly: Boolean get() = servers.isNotEmpty() && servers.all { it.inetAddress.isLoopbackAddress }
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val accepted = java.util.concurrent.LinkedBlockingQueue<Socket>()
    @Volatile private var closed = false
    private val workers = servers.map { listener ->
        Thread({ acceptLoop(listener) }, "chatgpt-callback-${listener.inetAddress.hostAddress}").apply {
            isDaemon = true
        }
    }

    init {
        workers.forEach(Thread::start)
    }

    suspend fun awaitCallback(validator: CallbackValidator): BrowserCallbackResponder = withContext(Dispatchers.IO) {
        while (true) {
            ensureActive()
            if (closed) throw CancellationException("Browser callback listener closed.")
            val client = accepted.poll(queuePollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            clients += client
            diagnostic(if (client.localAddress is java.net.Inet6Address) CallbackDiagnostic.CONNECTION_IPV6 else CallbackDiagnostic.CONNECTION_IPV4)
            val result = handle(client, validator)
            if (result != null) return@withContext result
        }
        @Suppress("UNREACHABLE_CODE") throw CancellationException()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!closed && !listener.isClosed) {
            try {
                val client = listener.accept()
                if (closed) client.close() else accepted.put(client)
            } catch (_: SocketTimeoutException) {
                // Periodically observe close without blocking another address family.
            } catch (_: Throwable) {
                if (!closed) close()
            }
        }
    }

    private fun handle(client: Socket, validator: CallbackValidator): BrowserCallbackResponder? {
        try {
            client.soTimeout = connectionReadTimeoutMs
            val line = BufferedReader(InputStreamReader(client.getInputStream())).readLine()
            val request = parseRequestLine(line) ?: run {
                diagnostic(CallbackDiagnostic.MALFORMED_REQUEST)
                respondAndClose(client, 400, BrowserCallbackPages.INVALID_REQUEST)
                return null
            }
            if (request.path != "/auth/callback") {
                diagnostic(CallbackDiagnostic.IRRELEVANT_ROUTE)
                respondAndClose(client, 404, BrowserCallbackPages.NOT_FOUND)
                return null
            }
            diagnostic(CallbackDiagnostic.OAUTH_CALLBACK_RECEIVED)
            val code = try {
                validator.validate(request.path, request.parameters).also { diagnostic(CallbackDiagnostic.STATE_VALID) }
            } catch (failure: Throwable) {
                diagnostic(CallbackDiagnostic.CALLBACK_REJECTED)
                runCatching { SocketBrowserCallback(client, "", ::clientFinished).failure() }
                throw failure
            }
            return SocketBrowserCallback(client, code, ::clientFinished)
        } catch (_: SocketTimeoutException) {
            diagnostic(CallbackDiagnostic.CLIENT_TIMEOUT)
            clientFinished(client)
            return null
        } catch (failure: ChatGptAuthException) {
            throw failure
        } catch (_: Throwable) {
            diagnostic(CallbackDiagnostic.MALFORMED_REQUEST)
            clientFinished(client)
            return null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        servers.forEach { runCatching { it.close() } }
        accepted.toList().forEach { runCatching { it.close() } }
        accepted.clear()
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } }; clients.clear() }
        workers.forEach { it.interrupt() }
        diagnostic(CallbackDiagnostic.LISTENER_CLOSED)
    }

    private fun clientFinished(socket: Socket) {
        clients.remove(socket)
        runCatching { socket.close() }
    }

    private fun respondAndClose(socket: Socket, status: Int, message: String) {
        SocketBrowserCallback(socket, "", ::clientFinished).respond(status, message)
    }

    private data class CallbackRequest(val path: String, val parameters: Map<String, String>)

    private fun parseRequestLine(line: String?): CallbackRequest? {
        val parts = line?.trim()?.split(Regex("\\s+"), limit = 3) ?: return null
        if (parts.size != 3 || parts[0] != "GET" || !parts[2].startsWith("HTTP/")) return null
        val uri = runCatching { URI(parts[1]) }.getOrNull() ?: return null
        val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { pair ->
            val split = pair.split('=', limit = 2)
            decode(split[0]) to decode(split.getOrElse(1) { "" })
        }
        return CallbackRequest(uri.path.orEmpty(), params)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    companion object {
        fun open(
            ports: IntArray = ChatGptOAuth.CALLBACK_PORTS,
            connectionReadTimeoutMs: Int = 1_500,
            acceptPollTimeoutMs: Int = 500,
            diagnostic: (CallbackDiagnostic) -> Unit = {},
        ): ChatGptLoopbackCallbackServer {
            for (candidate in ports) {
                val listeners = mutableListOf<ServerSocket>()
                val ipv4 = bind("127.0.0.1", candidate, acceptPollTimeoutMs)
                if (ipv4 != null) {
                    listeners += ipv4
                    diagnostic(CallbackDiagnostic.IPV4_LISTENER_STARTED)
                }
                val logicalPort = ipv4?.localPort ?: candidate
                val ipv6 = bind("::1", logicalPort, acceptPollTimeoutMs)
                if (ipv6 != null) {
                    listeners += ipv6
                    diagnostic(CallbackDiagnostic.IPV6_LISTENER_STARTED)
                } else diagnostic(CallbackDiagnostic.IPV6_UNAVAILABLE)
                if (listeners.isNotEmpty()) {
                    require(listeners.all { it.inetAddress.isLoopbackAddress })
                    return ChatGptLoopbackCallbackServer(listeners, connectionReadTimeoutMs, 100, diagnostic).also {
                        diagnostic(CallbackDiagnostic.LISTENER_STARTED)
                    }
                }
            }
            throw ChatGptCallbackException("Could not start the secure browser sign-in callback.")
        }

        private fun bind(host: String, port: Int, timeoutMs: Int): ServerSocket? = runCatching {
            val address = InetAddress.getByName(host)
            require(address.isLoopbackAddress)
            ServerSocket(port, 8, address).apply { soTimeout = timeoutMs }
        }.getOrNull()
    }
}

private class SocketBrowserCallback(
    private val socket: Socket,
    override val authorizationCode: String,
    private val finished: (Socket) -> Unit,
) : BrowserCallbackResponder {
    override fun success() = respond(200, BrowserCallbackPages.SUCCESS)
    override fun failure() = respond(400, BrowserCallbackPages.FAILURE)

    fun respond(status: Int, message: String) {
        try {
            val bytes = "<html><body><h2>$message</h2></body></html>".toByteArray()
            socket.getOutputStream().write(
                "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes,
            )
            socket.getOutputStream().flush()
        } finally {
            finished(socket)
        }
    }
}
