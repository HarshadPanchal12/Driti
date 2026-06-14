package com.remoteagent

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class RootExecutor {

    fun execute(command: String): ExecutionResult {
        return executeScript(listOf(command))
    }

    fun executeScript(commands: List<String>): ExecutionResult {
        if (commands.isEmpty()) {
            return ExecutionResult(success = true, output = "")
        }

        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { outputStream ->
                commands.forEach { command ->
                    outputStream.writeBytes("$command\n")
                }
                outputStream.writeBytes("exit\n")
                outputStream.flush()
            }

            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            val exitCode = process.waitFor()

            val combined = listOf(stdout, stderr)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()

            ExecutionResult(
                success = exitCode == 0,
                output = combined.ifEmpty { "Exit code: $exitCode" },
            )
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                output = e.message ?: "Root execution failed",
            )
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readText()
        }
    }
}

data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val screenshotBase64: String? = null,
)
