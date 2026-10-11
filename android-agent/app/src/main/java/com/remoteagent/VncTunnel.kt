package com.remoteagent

import android.util.Log
import com.google.gson.Gson
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reverse screen-share tunnel: maintains an outbound WebSocket to the backend.
 *
 * Architecture (no root, no VNC binary required):
 *  - Device captures frames via [MediaProjectionCaptureService] → [ScreenFrameRelay]
 *  - This tunnel subscribes to [ScreenFrameRelay] and sends each JPEG frame
 *    as a binary WebSocket message to the backend relay
 *  - Backend relay forwards frames to the browser viewer
 *  - Browser viewer sends back JSON input events (tap/swipe)
 *  - We forward those to [DrishtiAccessibilityService] for injection
 *
 * Control messages (JSON text frames):
 *  - Backend → Agent: {"type":"viewer_connected"} / {"type":"viewer_disconnected"}
 *  - Agent → Backend: frames are raw binary (JPEG bytes)
 *  - Viewer → Agent (via relay): {"type":"tap","nx":0.5,"ny":0.3}
 *                                {"type":"swipe","nx1":0.5,"ny1":0.8,"nx2":0.5,"ny2":0.2,"duration":300}
 *                                {"type":"text","value":"hello"}
 */
class VncTunnel(
    private val serverUrl: String,
    private val apiKey: String,
    private val deviceId: String,
) {
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var tunnelJob: Job? = null
    private val running = AtomicBoolean(false)

    // Current active WebSocket — for the frame relay listener to use
    private val activeSocket = AtomicReference<WebSocket?>(null)

    // Listener registered with ScreenFrameRelay while a viewer is connected
    private var frameListener: ((ByteArray) -> Unit)? = null

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return

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
        stopStreaming()
    }

    // ── Session ───────────────────────────────────────────────────────────────

    private suspend fun runSession(scope: CoroutineScope) {
        val sessionClosed = CompletableDeferred<Unit>()

        val request = Request.Builder()
            .url(buildAgentWsUrl())
            .header("x-agent-key", apiKey)
            .build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "VNC tunnel WebSocket open")
                activeSocket.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControlMessage(text, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Input events from viewer arrive as binary JSON (rare path)
                handleControlMessage(bytes.utf8(), webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "VNC tunnel WebSocket closed: $code $reason")
                activeSocket.set(null)
                stopStreaming()
                sessionClosed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "VNC tunnel failure: ${t.message}")
                activeSocket.set(null)
                stopStreaming()
                sessionClosed.complete(Unit)
            }
        })

        try {
            sessionClosed.await()
        } finally {
            activeSocket.set(null)
            stopStreaming()
            webSocket.close(1000, "Session ended")
        }
    }

    // ── Control messages ──────────────────────────────────────────────────────

    private fun handleControlMessage(text: String, webSocket: WebSocket) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }

        when (json.get("type")?.asString) {
            "viewer_connected" -> {
                Log.i(TAG, "Viewer connected — starting frame stream")
                startStreaming(webSocket)
            }

            "viewer_disconnected" -> {
                Log.i(TAG, "Viewer disconnected — stopping frame stream")
                stopStreaming()
            }

            "tap" -> {
                val nx = json.get("nx")?.asFloat ?: return
                val ny = json.get("ny")?.asFloat ?: return
                DrishtiAccessibilityService.instance?.injectTap(nx, ny)
                    ?: Log.w(TAG, "Tap received but AccessibilityService not active")
            }

            "swipe" -> {
                val nx1 = json.get("nx1")?.asFloat ?: return
                val ny1 = json.get("ny1")?.asFloat ?: return
                val nx2 = json.get("nx2")?.asFloat ?: return
                val ny2 = json.get("ny2")?.asFloat ?: return
                val dur = json.get("duration")?.asLong ?: 300L
                DrishtiAccessibilityService.instance?.injectSwipe(nx1, ny1, nx2, ny2, dur)
            }

            "text" -> {
                val value = json.get("value")?.asString ?: return
                DrishtiAccessibilityService.instance?.injectText(value)
            }
        }
    }

    // ── Frame streaming ───────────────────────────────────────────────────────

    private fun startStreaming(webSocket: WebSocket) {
        stopStreaming() // clear any existing listener

        val listener: (ByteArray) -> Unit = { jpeg ->
            if (webSocket.send(jpeg.toByteString())) {
                // sent OK
            } else {
                Log.w(TAG, "Frame send failed — viewer may have disconnected")
            }
        }

        frameListener = listener
        ScreenFrameRelay.addListener(listener)

        // Send the most recent frame immediately so the viewer doesn't wait
        ScreenFrameRelay.getLatest()?.let { frame ->
            webSocket.send(frame.toByteString())
        }
    }

    private fun stopStreaming() {
        val listener = frameListener ?: return
        ScreenFrameRelay.removeListener(listener)
        frameListener = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildAgentWsUrl(): String {
        val wsBase = when {
            serverUrl.startsWith("https://") -> serverUrl.replaceFirst("https://", "wss://")
            serverUrl.startsWith("http://") -> serverUrl.replaceFirst("http://", "ws://")
            else -> "ws://$serverUrl"
        }
        return "$wsBase/vnc/agent/$deviceId"
    }

    companion object {
        private const val TAG = "VncTunnel"
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
