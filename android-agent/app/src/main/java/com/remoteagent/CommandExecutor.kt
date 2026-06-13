package com.remoteagent

import com.remoteagent.model.Command

class CommandExecutor(
    private val rootExecutor: RootExecutor = RootExecutor(),
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
                val shellCommand = command.payload
                    ?.asJsonObject
                    ?.get("command")
                    ?.asString

                if (shellCommand.isNullOrBlank()) {
                    ExecutionResult(
                        success = false,
                        output = "Missing payload.command",
                    )
                } else {
                    rootExecutor.execute(shellCommand)
                }
            }

            else -> ExecutionResult(
                success = false,
                output = "Unknown command type: ${command.type}",
            )
        }
    }
}
