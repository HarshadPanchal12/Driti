package com.remoteagent

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import com.remoteagent.model.Command
import java.io.BufferedReader
import java.io.InputStreamReader

class CommandExecutor(
    private val context: Context,
    private val rootExecutor: RootExecutor = RootExecutor(),
    private val screenCapture: ScreenCapture = ScreenCapture(),
    private val apkInstaller: ApkInstaller = ApkInstaller(context),
) {

    fun execute(command: Command): ExecutionResult {
        return when (command.type) {
            "ENABLE_ADB" -> {
                ExecutionResult(
                    success = false, 
                    output = "Wireless ADB configuration requires a rooted device or an external USB shell loop connection."
                )
            }

            "DISABLE_ADB" -> {
                ExecutionResult(
                    success = false, 
                    output = "Disabling system ADB daemons is restricted on non-rooted hardware platforms."
                )
            }

            "REBOOT" -> {
                ExecutionResult(
                    success = false, 
                    output = "Hardware reboot execution pathways are protected by Android security levels on standard user devices."
                )
            }

            "SHELL" -> {
                val shellCommand = payloadString(command, "command")
                if (shellCommand.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.command")
                } else {
                    // Fallback to a standard root-free shell environment context execution loop
                    executeNonRootShell(shellCommand)
                }
            }

            "SCREENSHOT" -> {
                val capture = screenCapture.capture()
                if (capture.success) {
                    ExecutionResult(
                        success = true,
                        output = "Screenshot captured",
                        screenshotBase64 = capture.base64,
                    )
                } else {
                    ExecutionResult(success = false, output = capture.error)
                }
            }

            "INSTALL_APP" -> {
                ExecutionResult(
                    success = false, 
                    output = "Silent background package installation requires system superuser binary credentials."
                )
            }

            "INSTALL_FROM_URL" -> {
                val url = payloadString(command, "url")
                if (url.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.url")
                } else {
                    apkInstaller.installFromUrl(url)
                }
            }

            "UNINSTALL_APP" -> {
                ExecutionResult(
                    success = false, 
                    output = "Automated package removal requires system root capabilities. Use standard phone settings instead."
                )
            }

            "OPEN_APP" -> {
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    val activity = payloadString(command, "activity")
                    if (!activity.isNullOrBlank()) {
                        // Launch targeted activity using root-free Android Intents
                        try {
                            val intent = Intent().apply {
                                setClassName(packageName, "$packageName.$activity")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            ExecutionResult(success = true, output = "Launched explicit activity: $packageName/$activity")
                        } catch (e: Exception) {
                            ExecutionResult(success = false, output = "Failed to launch intent activity target: ${e.message}")
                        }
                    } else {
                        // Launch the app's default launcher intent safely without root/monkey runners
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            ExecutionResult(success = true, output = "Successfully launched app package: $packageName")
                        } else {
                            ExecutionResult(success = false, output = "Application target metadata missing default entry point.")
                        }
                    }
                }
            }

            "CLOSE_APP" -> {
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    // Standard apps cannot force-stop other apps via shell without root.
                    // Fallback to killing background processes (won't stop foreground apps, but won't crash).
                    try {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(packageName)
                        ExecutionResult(success = true, output = "Sent kill signal to background threads of package: $packageName")
                    } catch (e: Exception) {
                        ExecutionResult(success = false, output = "Process closure execution failed: ${e.message}")
                    }
                }
            }

            "HIDE_NAV" -> ExecutionResult(success = false, output = "System UI manipulation bars are bound to Kiayo proprietary rooted devices.")

            "SHOW_NAV" -> ExecutionResult(success = false, output = "System UI manipulation bars are bound to Kiayo proprietary rooted devices.")

            else -> ExecutionResult(
                success = false,
                output = "Unknown command type: ${command.type}",
            )
        }
    }

    private fun executeNonRootShell(command: String): ExecutionResult {
        return try {
            // Force the runtime engine to execute commands inside the standard unprivileged user shell profile
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            ExecutionResult(success = true, output = output.toString().trim())
        } catch (e: Exception) {
            ExecutionResult(success = false, output = "Standard shell context exception tracking: ${e.message}")
        }
    }

    private fun payloadString(command: Command, key: String): String? {
        return command.payload
            ?.asJsonObject
            ?.get(key)
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
