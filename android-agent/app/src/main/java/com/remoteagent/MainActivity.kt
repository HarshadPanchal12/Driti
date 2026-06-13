package com.remoteagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var deviceIdText: TextView
    private lateinit var lastHeartbeatText: TextView
    private lateinit var lastCommandText: TextView
    private lateinit var lastResultText: TextView

    private val stateListener = {
        runOnUiThread { renderState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdText = findViewById(R.id.deviceIdText)
        lastHeartbeatText = findViewById(R.id.lastHeartbeatText)
        lastCommandText = findViewById(R.id.lastCommandText)
        lastResultText = findViewById(R.id.lastResultText)

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        deviceIdText.text = deviceId

        requestNotificationPermissionIfNeeded()
        AgentService.start(this)
        renderState()
    }

    override fun onStart() {
        super.onStart()
        AgentState.addListener(stateListener)
        renderState()
    }

    override fun onStop() {
        AgentState.removeListener(stateListener)
        super.onStop()
    }

    private fun renderState() {
        lastHeartbeatText.text = AgentState.getLastHeartbeat() ?: getString(R.string.not_available)
        lastCommandText.text = AgentState.getLastCommand() ?: getString(R.string.not_available)
        lastResultText.text = AgentState.getLastResult() ?: getString(R.string.not_available)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION,
        )
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}
