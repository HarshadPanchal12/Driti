package com.remoteagent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ApkInstaller(
    context: Context,
    private val rootExecutor: RootExecutor = RootExecutor(),
) {
    private val downloadDir = File(context.cacheDir, "apk-downloads").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun installFromUrl(url: String): ExecutionResult {
        val scheme = try {
            java.net.URI(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        }

        if (scheme != "https" && scheme != "http") {
            return ExecutionResult(
                success = false,
                output = "URL must use http or https",
            )
        }

        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank {
            "download.apk"
        }.let { name ->
            if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"
        }

        val destination = File(downloadDir, fileName)

        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ExecutionResult(
                        success = false,
                        output = "Download failed: HTTP ${response.code}",
                    )
                }

                val body = response.body
                    ?: return ExecutionResult(success = false, output = "Download failed: empty body")

                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!destination.exists() || destination.length() == 0L) {
                return ExecutionResult(success = false, output = "Download failed: file empty")
            }

            val installResult = rootExecutor.execute(
                "pm install -r -g \"${destination.absolutePath}\"",
            )

            destination.delete()

            if (installResult.success) {
                ExecutionResult(
                    success = true,
                    output = installResult.output.ifBlank { "Install succeeded" },
                )
            } else {
                ExecutionResult(
                    success = false,
                    output = installResult.output.ifBlank { "pm install failed" },
                )
            }
        } catch (e: Exception) {
            destination.delete()
            ExecutionResult(
                success = false,
                output = e.message ?: "Install from URL failed",
            )
        }
    }
}
