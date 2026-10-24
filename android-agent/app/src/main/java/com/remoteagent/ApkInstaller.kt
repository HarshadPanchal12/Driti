package com.remoteagent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ApkInstaller(
    private val context: Context,
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

        val rawFileName = url.substringAfterLast('/').substringBefore('?').ifBlank {
            "download.apk"
        }
        val fileName = if (rawFileName.endsWith(".apk", ignoreCase = true)) rawFileName else "$rawFileName.apk"

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
                return ExecutionResult(success = false, output = "Download failed: file empty (0 bytes)")
            }

            // Install using unified PackageHelper (handles validation, Device Owner silent session, root, and a11y auto-approval)
            PackageHelper.installApk(context, destination, rootExecutor)
        } catch (e: Exception) {
            destination.delete()
            ExecutionResult(
                success = false,
                output = e.message ?: "Install from URL failed",
            )
        }
    }
}
