package com.remoteagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ScreenCapture(
    private val rootExecutor: RootExecutor = RootExecutor(),
) {
    private val tempPath = "/data/local/tmp/agent_screen.png"

    fun capture(): CaptureResult {
        val captureResult = rootExecutor.execute("screencap -p $tempPath")
        if (!captureResult.success) {
            return CaptureResult(
                success = false,
                error = captureResult.output.ifBlank { "screencap failed" },
            )
        }

        val bytes = readFileAsRoot(tempPath)
            ?: return CaptureResult(success = false, error = "Failed to read screenshot file")

        if (bytes.isEmpty()) {
            return CaptureResult(success = false, error = "Screenshot file empty")
        }

        rootExecutor.execute("rm -f $tempPath")

        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return CaptureResult(success = false, error = "Failed to decode screenshot")

            val scaled = scaleBitmap(bitmap)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }

            val jpegBytes = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                stream.toByteArray()
            }
            scaled.recycle()

            CaptureResult(
                success = true,
                base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
            )
        } catch (e: Exception) {
            CaptureResult(success = false, error = e.message ?: "Capture failed")
        }
    }

    private fun readFileAsRoot(path: String): ByteArray? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { outputStream ->
                outputStream.writeBytes("cat $path\n")
                outputStream.writeBytes("exit\n")
                outputStream.flush()
            }

            val bytes = process.inputStream.readBytes()
            val exitCode = process.waitFor()
            if (exitCode != 0 || bytes.isEmpty()) {
                null
            } else {
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val maxWidth = 720
        if (bitmap.width <= maxWidth) {
            return bitmap
        }

        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val height = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    }
}

data class CaptureResult(
    val success: Boolean,
    val base64: String = "",
    val error: String = "",
)
