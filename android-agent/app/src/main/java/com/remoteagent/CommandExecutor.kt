package com.remoteagent

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.net.Uri
import androidx.core.content.FileProvider
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
                // Prefer MediaProjection frame (no root required)
                val jpeg = ScreenFrameRelay.getLatest()
                if (jpeg != null) {
                    val base64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                    ExecutionResult(
                        success = true,
                        output = "Screenshot captured via MediaProjection",
                        screenshotBase64 = base64,
                    )
                } else {
                    // Fallback: root-based screencap
                    val capture = screenCapture.capture()
                    if (capture.success) {
                        ExecutionResult(
                            success = true,
                            output = "Screenshot captured via screencap (root)",
                            screenshotBase64 = capture.base64,
                        )
                    } else {
                        ExecutionResult(
                            success = false,
                            output = "No screen capture available. Start screen sharing from the app first (or root required for screencap fallback).",
                        )
                    }
                }
            }

            "INSTALL_APP" -> {
                val apkPath = payloadString(command, "apkPath") ?: payloadString(command, "path")
                if (apkPath.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.apkPath")
                } else {
                    val file = java.io.File(apkPath)
                    if (!file.exists()) {
                        ExecutionResult(success = false, output = "APK file not found on device at path: $apkPath")
                    } else if (rootExecutor.isSuAvailable()) {
                        val installResult = rootExecutor.execute("pm install -r -g \"${file.absolutePath}\"")
                        ExecutionResult(
                            success = installResult.success,
                            output = installResult.output.ifBlank { if (installResult.success) "Package installed successfully (via root)" else "pm install failed" }
                        )
                    } else {
                        try {
                            val contentUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            ExecutionResult(
                                success = true,
                                output = "Launched system package installer for ${file.name}. Confirm installation prompt on device screen."
                            )
                        } catch (e: Exception) {
                            ExecutionResult(
                                success = false,
                                output = "Failed to launch package installer for $apkPath: ${e.message}"
                            )
                        }
                    }
                }
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
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    if (rootExecutor.isSuAvailable()) {
                        val uninsResult = rootExecutor.execute("pm uninstall $packageName")
                        ExecutionResult(
                            success = uninsResult.success,
                            output = uninsResult.output.ifBlank { if (uninsResult.success) "Uninstalled package $packageName (via root)" else "pm uninstall failed" }
                        )
                    } else {
                        try {
                            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(uninstallIntent)
                            ExecutionResult(
                                success = true,
                                output = "Launched system uninstaller dialog for $packageName. Confirm uninstallation on device screen."
                            )
                        } catch (e: Exception) {
                            ExecutionResult(
                                success = false,
                                output = "Failed to launch uninstaller: ${e.message}"
                            )
                        }
                    }
                }
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
                            // Fallback 1: Query launcher activity directly
                            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setPackage(packageName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val resolveInfos = context.packageManager.queryIntentActivities(mainIntent, 0)
                            if (resolveInfos.isNotEmpty()) {
                                val entry = resolveInfos[0].activityInfo
                                mainIntent.component = android.content.ComponentName(entry.packageName, entry.name)
                                context.startActivity(mainIntent)
                                ExecutionResult(success = true, output = "Launched via activity: ${entry.name}")
                            } else {
                                // Fallback 2: monkey runner (native tool on all Android devices, works without root)
                                val monkeyRes = executeNonRootShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                                if (monkeyRes.success && !monkeyRes.output.contains("No activities found", ignoreCase = true)) {
                                    ExecutionResult(success = true, output = "Launched $packageName via runner")
                                } else {
                                    ExecutionResult(success = false, output = "Package $packageName not found or has no launchable launcher activity.")
                                }
                            }
                        }
                    }
                }
            }

            "CLOSE_APP" -> {
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    if (rootExecutor.isSuAvailable()) {
                        val rootRes = rootExecutor.execute("am force-stop $packageName")
                        ExecutionResult(
                            success = rootRes.success,
                            output = rootRes.output.ifBlank { if (rootRes.success) "Force stopped $packageName (via root)" else "am force-stop failed" }
                        )
                    } else {
                        // Dismiss foreground app to home screen via Accessibility
                        DrishtiAccessibilityService.instance?.injectHome()
                        try {
                            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.killBackgroundProcesses(packageName)
                            ExecutionResult(
                                success = true,
                                output = "Dismissed $packageName to home screen and killed background processes."
                            )
                        } catch (e: Exception) {
                            ExecutionResult(
                                success = true,
                                output = "Dismissed $packageName to home screen."
                            )
                        }
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
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr]: ").append(stderr.trim())
                }
            }.trim()

            val output = if (combined.isNotBlank()) {
                combined
            } else if (exitCode == 0) {
                "(Command executed successfully with no output)"
            } else {
                "(Command exited with code $exitCode)"
            }

            ExecutionResult(success = exitCode == 0, output = output)
        } catch (e: Exception) {
            ExecutionResult(success = false, output = "Shell execution error: ${e.message}")
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
