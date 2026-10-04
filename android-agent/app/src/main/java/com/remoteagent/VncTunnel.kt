package com.remoteagent

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reverse VNC tunnel: maintains an outbound WebSocket to the backend.
 * Local VNC TCP (port 5900) is opened only when a viewer connects so the
 * RFB handshake is not consumed before a browser session starts.
 */
class VncTunnel(
    private val serverUrl: String,
    private val apiKey: String,
    private val deviceId: String,
    private val vncHost: String = DEFAULT_VNC_HOST,
    private val vncPort: Int = DEFAULT_VNC_PORT,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var tunnelJob: Job? = null
    private val running = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) {
            return
        }

        tunnelJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    runSession(scope)
                } catch (e: Exception) {
                    Log.w(TAG, "VNC tunnel session ended: ${e.message}")
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    fun stop() {
        running.set(false)
        tunnelJob?.cancel()
        tunnelJob = null
    }

    private suspend fun runSession(scope: CoroutineScope) {
        val sessionClosed = CompletableDeferred<Unit>()
        val bridge = TcpVncBridge(scope, vncHost, vncPort)

        val request = Request.Builder()
            .url(buildAgentWsUrl())
            .header("x-agent-key", apiKey)
            .build()

        val webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "VNC tunnel WebSocket open")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (parseControlType(text)) {
                        "viewer_connected" -> {
                            try {
                                bridge.connect(
                                    onVncData = { data -> webSocket.send(data.toByteString()) },
                                    onVncClosed = {
                                        webSocket.close(4002, "Local VNC closed")
                                        sessionClosed.complete(Unit)
                                    },
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Local VNC unavailable: ${e.message}")
                                webSocket.send(
                                    """{"type":"vnc_error","message":"${e.message ?: "connect failed"}"}""",
                                )
                            }
                        }

                        "viewer_disconnected" -> {
                            Log.i(TAG, "Viewer disconnected — closing local VNC session")
                            bridge.close()
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    bridge.write(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "VNC tunnel WebSocket closed: $code $reason")
                    bridge.close()
                    sessionClosed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "VNC tunnel WebSocket failure: ${t.message}")
                    bridge.close()
                    sessionClosed.complete(Unit)
                }
            },
        )

        try {
            sessionClosed.await()
        } finally {
            webSocket.close(1000, "Session ended")
            bridge.close()
        }
    }

    private fun parseControlType(text: String): String? {
        return try {
            val json = JsonParser.parseString(text).asJsonObject
            json.get("type")?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAgentWsUrl(): String {
        val wsBase = when {
            serverUrl.startsWith("https://") -> serverUrl.replaceFirst("https://", "wss://")
            serverUrl.startsWith("http://") -> serverUrl.replaceFirst("http://", "ws://")
            else -> "ws://$serverUrl"
        }
        return "$wsBase/vnc/agent/$deviceId"
    }

    private class TcpVncBridge(
        private val scope: CoroutineScope,
        private val host: String,
        private val port: Int,
    ) {
        private var socket: Socket? = null
        private var readerJob: Job? = null
        private val closed = AtomicBoolean(true)

        @Synchronized
        fun connect(
            onVncData: (ByteArray) -> Unit,
            onVncClosed: () -> Unit,
        ) {
            close()

            val activeSocket = Socket()
            activeSocket.tcpNoDelay = true
            activeSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = activeSocket
            closed.set(false)
            Log.i(TAG, "Connected to local VNC at $host:$port")

            val input = activeSocket.getInputStream()
            readerJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(32 * 1024)
                try {
                    while (!closed.get()) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        onVncData(buffer.copyOf(read))
                    }
                } catch (e: IOException) {
                    if (!closed.get()) {
                        Log.w(TAG, "VNC read error: ${e.message}")
                    }
                } finally {
                    onVncClosed()
                }
            }
        }

        fun write(data: ByteArray) {
            val activeSocket = socket ?: return
            if (closed.get() || activeSocket.isClosed) {
                return
            }

            try {
                activeSocket.getOutputStream().write(data)
                activeSocket.getOutputStream().flush()
            } catch (e: IOException) {
                Log.w(TAG, "VNC write error: ${e.message}")
                close()
            }
        }

        @Synchronized
        fun close() {
            if (closed.get()) {
                return
            }
            closed.set(true)

            readerJob?.cancel()
            readerJob = null

            try {
                socket?.close()
            } catch (_: IOException) {
            }
            socket = null
        }
    }

    companion object {
        private const val TAG = "VncTunnel"
        private const val DEFAULT_VNC_HOST = "127.0.0.1"
        private const val DEFAULT_VNC_PORT = 5900
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
