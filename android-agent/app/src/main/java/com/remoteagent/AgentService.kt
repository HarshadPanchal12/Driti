package com.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var pollJob: Job? = null

    private lateinit var deviceId: String
    private lateinit var agentClient: AgentClient
    private lateinit var vncTunnel: VncTunnel
    private lateinit var commandExecutor: CommandExecutor
    private val screenCapture = ScreenCapture()

    private var lastScreenshotUploadMs = 0L

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        agentClient = AgentClient(
            serverUrl = BuildConfig.SERVER_URL,
            apiKey = BuildConfig.API_KEY,
        )
        vncTunnel = VncTunnel(
            serverUrl = BuildConfig.SERVER_URL,
            apiKey = BuildConfig.API_KEY,
            deviceId = deviceId,
        )
        commandExecutor = CommandExecutor(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startPolling()
        vncTunnel.start(serviceScope)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        vncTunnel.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) {
            return
        }

        pollJob = serviceScope.launch {
            while (isActive) {
                runAgentCycle()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun runAgentCycle() {
        var captureScreen = false

        try {
            val heartbeat = agentClient.sendHeartbeat(deviceId)
            captureScreen = heartbeat.captureScreen
        } catch (e: Exception) {
            AgentState.setLastHeartbeat("Failed: ${e.message}")
        }

        if (captureScreen) {
            maybeUploadScreenshot()
        }

        try {
            when (val pollResult = agentClient.pollCommand(deviceId)) {
                PollResult.None -> Unit

                is PollResult.CommandAvailable -> {
                    val command = pollResult.command
                    AgentState.setLastCommand("${command.type} (${command.id})")

                    val result = commandExecutor.execute(command)
                    val finalResult = finalizeCommandResult(command.type, result)

                    val resultSummary = if (finalResult.success) {
                        "SUCCESS: ${finalResult.output}"
                    } else {
                        "FAILED: ${finalResult.output}"
                    }
                    AgentState.setLastResult(resultSummary)

                    agentClient.submitResult(
                        deviceId = deviceId,
                        commandId = command.id,
                        success = finalResult.success,
                        output = finalResult.output,
                    )
                }
            }
        } catch (e: Exception) {
            AgentState.setLastResult("Poll/execute error: ${e.message}")
        }
    }

    private fun finalizeCommandResult(
        commandType: String,
        result: ExecutionResult,
    ): ExecutionResult {
        val screenshotBase64 = result.screenshotBase64
        if (commandType == "SCREENSHOT" && result.success && !screenshotBase64.isNullOrBlank()) {
            val uploaded = agentClient.uploadScreenshot(deviceId, screenshotBase64)
            return ExecutionResult(
                success = uploaded,
                output = if (uploaded) "Screenshot uploaded" else "Screenshot upload failed",
            )
        }

        return result
    }

    private fun maybeUploadScreenshot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotUploadMs < SCREENSHOT_INTERVAL_MS) {
            return
        }

        val capture = screenCapture.capture()
        if (!capture.success) {
            return
        }

        val uploaded = agentClient.uploadScreenshot(deviceId, capture.base64)
        if (uploaded) {
            lastScreenshotUploadMs = now
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_service"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 10_000L
        private const val SCREENSHOT_INTERVAL_MS = 3_000L

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
