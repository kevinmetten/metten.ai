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
    LISTENER_STARTED, CONNECTION_RECEIVED, IRRELEVANT_ROUTE, MALFORMED_CONNECTION,
    CONNECTION_READ_TIMEOUT, CALLBACK_VALIDATED, CALLBACK_REJECTED, LISTENER_CLOSED,
}

internal class ChatGptCallbackException(message: String) : ChatGptAuthException(message)

/** Loopback-only HTTP callback server. The advertised host is exactly the bound address. */
internal class ChatGptLoopbackCallbackServer private constructor(
    private val server: ServerSocket,
    val listenerHost: String,
    private val connectionReadTimeoutMs: Int,
    private val diagnostic: (CallbackDiagnostic) -> Unit,
) : AutoCloseable {
    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://$listenerHost:$port/auth/callback"
    val isLoopbackOnly: Boolean get() = server.inetAddress.isLoopbackAddress
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    suspend fun awaitCallback(validator: CallbackValidator): BrowserCallbackResponder = withContext(Dispatchers.IO) {
        while (true) {
            ensureActive()
            val client = try { server.accept() } catch (_: SocketTimeoutException) {
                continue
            } catch (failure: Throwable) {
                if (server.isClosed) throw CancellationException("Browser callback listener closed.").also { it.initCause(failure) }
                throw ChatGptCallbackException("Could not receive the browser sign-in callback.")
            }
            clients += client
            diagnostic(CallbackDiagnostic.CONNECTION_RECEIVED)
            val result = handle(client, validator)
            if (result != null) return@withContext result
        }
        @Suppress("UNREACHABLE_CODE") throw CancellationException()
    }

    private fun handle(client: Socket, validator: CallbackValidator): BrowserCallbackResponder? {
        try {
            client.soTimeout = connectionReadTimeoutMs
            val line = BufferedReader(InputStreamReader(client.getInputStream())).readLine()
            val request = parseRequestLine(line) ?: run {
                diagnostic(CallbackDiagnostic.MALFORMED_CONNECTION)
                respondAndClose(client, 400, BrowserCallbackPages.INVALID_REQUEST)
                return null
            }
            if (request.path != "/auth/callback") {
                diagnostic(CallbackDiagnostic.IRRELEVANT_ROUTE)
                respondAndClose(client, 404, BrowserCallbackPages.NOT_FOUND)
                return null
            }
            val code = try {
                validator.validate(request.path, request.parameters)
            } catch (failure: Throwable) {
                diagnostic(CallbackDiagnostic.CALLBACK_REJECTED)
                runCatching { SocketBrowserCallback(client, "", ::clientFinished).failure() }
                throw failure
            }
            diagnostic(CallbackDiagnostic.CALLBACK_VALIDATED)
            return SocketBrowserCallback(client, code, ::clientFinished)
        } catch (_: SocketTimeoutException) {
            diagnostic(CallbackDiagnostic.CONNECTION_READ_TIMEOUT)
            clientFinished(client)
            return null
        } catch (failure: ChatGptAuthException) {
            throw failure
        } catch (_: Throwable) {
            diagnostic(CallbackDiagnostic.MALFORMED_CONNECTION)
            clientFinished(client)
            return null
        }
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } }; clients.clear() }
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
            listenerHost: String = ChatGptOAuth.LOOPBACK_ADDRESS,
            connectionReadTimeoutMs: Int = 1_500,
            acceptPollTimeoutMs: Int = 500,
            diagnostic: (CallbackDiagnostic) -> Unit = {},
        ): ChatGptLoopbackCallbackServer {
            val address = InetAddress.getByName(listenerHost)
            require(address.isLoopbackAddress) { "OAuth callback address must be loopback-only." }
            for (port in ports) {
                val socket = runCatching { ServerSocket(port, 8, address) }.getOrNull() ?: continue
                socket.soTimeout = acceptPollTimeoutMs
                return ChatGptLoopbackCallbackServer(socket, address.hostAddress, connectionReadTimeoutMs, diagnostic).also {
                    diagnostic(CallbackDiagnostic.LISTENER_STARTED)
                }
            }
            throw ChatGptCallbackException("Could not start the secure browser sign-in callback.")
        }
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
