package com.remoteagent.model

import com.google.gson.JsonElement

data class Command(
    val id: String,
    val type: String,
    val payload: JsonElement? = null,
    val status: String? = null,
    val createdAt: String? = null,
)

data class NoneCommand(
    val type: String = "NONE",
)

data class CommandResultRequest(
    val commandId: String,
    val success: Boolean,
    val output: String? = null,
)

data class ScreenshotUploadRequest(
    val imageBase64: String,
)
