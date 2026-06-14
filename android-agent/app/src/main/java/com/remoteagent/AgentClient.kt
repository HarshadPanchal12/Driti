package com.remoteagent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.remoteagent.model.Command
import com.remoteagent.model.CommandResultRequest
import com.remoteagent.model.ScreenshotUploadRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class PollResult {
    data object None : PollResult()

    data class CommandAvailable(val command: Command) : PollResult()
}

data class HeartbeatResult(
    val success: Boolean,
    val captureScreen: Boolean = false,
)

class AgentClient(
    private val serverUrl: String,
    private val apiKey: String,
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun sendHeartbeat(deviceId: String): HeartbeatResult {
        val request = Request.Builder()
            .url("$serverUrl/devices/$deviceId/heartbeat")
            .post("".toRequestBody(jsonMediaType))
            .header("x-agent-key", apiKey)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return HeartbeatResult(success = false)
            }

            val body = response.body?.string().orEmpty()
            val json = gson.fromJson(body, JsonObject::class.java)
            val lastHeartbeatAt = json?.get("lastHeartbeatAt")?.asString
            if (!lastHeartbeatAt.isNullOrBlank()) {
                AgentState.setLastHeartbeat(lastHeartbeatAt)
            }

            val captureScreen = json?.get("captureScreen")?.asBoolean ?: false
            HeartbeatResult(success = true, captureScreen = captureScreen)
        }
    }

    fun pollCommand(deviceId: String): PollResult {
        val request = Request.Builder()
            .url("$serverUrl/devices/$deviceId/command")
            .get()
            .header("x-agent-key", apiKey)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Command poll failed: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            val json = gson.fromJson(body, JsonObject::class.java)
            val type = json?.get("type")?.asString ?: "NONE"

            if (type == "NONE") {
                PollResult.None
            } else {
                PollResult.CommandAvailable(gson.fromJson(body, Command::class.java))
            }
        }
    }

    fun submitResult(
        deviceId: String,
        commandId: String,
        success: Boolean,
        output: String,
    ): Boolean {
        val payload = CommandResultRequest(
            commandId = commandId,
            success = success,
            output = output,
        )
        val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$serverUrl/devices/$deviceId/result")
            .post(requestBody)
            .header("x-agent-key", apiKey)
            .build()

        return client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    fun uploadScreenshot(deviceId: String, imageBase64: String): Boolean {
        val payload = ScreenshotUploadRequest(imageBase64 = imageBase64)
        val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$serverUrl/devices/$deviceId/screenshot")
            .post(requestBody)
            .header("x-agent-key", apiKey)
            .build()

        return client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }
}
