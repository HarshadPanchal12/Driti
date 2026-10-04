package com.remoteagent

import android.content.Context
import com.remoteagent.model.Command

class CommandExecutor(
    context: Context,
    private val rootExecutor: RootExecutor = RootExecutor(),
    private val screenCapture: ScreenCapture = ScreenCapture(),
    private val apkInstaller: ApkInstaller = ApkInstaller(context),
) {

    fun execute(command: Command): ExecutionResult {
        return when (command.type) {
            "ENABLE_ADB" -> rootExecutor.executeScript(
                listOf(
                    "setprop service.adb.tcp.port 9898",
                    "start adbd",
                ),
            )

            "DISABLE_ADB" -> rootExecutor.execute("stop adbd")

            "REBOOT" -> rootExecutor.execute("reboot")

            "SHELL" -> {
                val shellCommand = payloadString(command, "command")
                if (shellCommand.isNullOrBlank()) {
                    ExecutionResult(
                        success = false,
                        output = "Missing payload.command",
                    )
                } else {
                    rootExecutor.execute(shellCommand)
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
                    ExecutionResult(
                        success = false,
                        output = capture.error,
                    )
                }
            }

            "INSTALL_APP" -> {
                val apkPath = payloadString(command, "apkPath")
                if (apkPath.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.apkPath")
                } else {
                    rootExecutor.execute("pm install -r -g \"$apkPath\"")
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
                    rootExecutor.execute("pm uninstall --user 0 \"$packageName\"")
                }
            }

            "OPEN_APP" -> {
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    val activity = payloadString(command, "activity")
                    val launchCommand = if (!activity.isNullOrBlank()) {
                        "am start -n \"$packageName/$activity\""
                    } else {
                        "monkey -p \"$packageName\" -c android.intent.category.LAUNCHER 1"
                    }
                    rootExecutor.execute(launchCommand)
                }
            }

            "CLOSE_APP" -> {
                val packageName = payloadString(command, "package")
                if (packageName.isNullOrBlank()) {
                    ExecutionResult(success = false, output = "Missing payload.package")
                } else {
                    rootExecutor.execute("am force-stop \"$packageName\"")
                }
            }

            "HIDE_NAV" -> KiayoSystemBarsHelper.hideNavigationBars(rootExecutor)

            "SHOW_NAV" -> KiayoSystemBarsHelper.showNavigationBars(rootExecutor)

            else -> ExecutionResult(
                success = false,
                output = "Unknown command type: ${command.type}",
            )
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
