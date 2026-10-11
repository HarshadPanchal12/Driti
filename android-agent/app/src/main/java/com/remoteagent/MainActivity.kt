package com.remoteagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var deviceIdText: TextView
    private lateinit var lastHeartbeatText: TextView
    private lateinit var lastCommandText: TextView
    private lateinit var lastResultText: TextView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var screenCaptureStatusText: TextView
    private lateinit var grantAccessibilityButton: Button
    private lateinit var startScreenShareButton: Button

    private val stateListener = { runOnUiThread { renderState() } }

    // ── MediaProjection permission launcher ──────────────────────────────────
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MediaProjectionCaptureService.start(this, result.resultCode, result.data!!)
            renderState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdText = findViewById(R.id.deviceIdText)
        lastHeartbeatText = findViewById(R.id.lastHeartbeatText)
        lastCommandText = findViewById(R.id.lastCommandText)
        lastResultText = findViewById(R.id.lastResultText)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        screenCaptureStatusText = findViewById(R.id.screenCaptureStatusText)
        grantAccessibilityButton = findViewById(R.id.grantAccessibilityButton)
        startScreenShareButton = findViewById(R.id.startScreenShareButton)

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        deviceIdText.text = deviceId

        grantAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        startScreenShareButton.setOnClickListener { requestScreenCapture() }

        requestNotificationPermissionIfNeeded()
        AgentService.start(this)
        renderState()
    }

    override fun onStart() {
        super.onStart()
        AgentState.addListener(stateListener)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        // Re-render so accessibility status updates after user comes back from settings
        renderState()
    }

    override fun onStop() {
        AgentState.removeListener(stateListener)
        super.onStop()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun renderState() {
        lastHeartbeatText.text = AgentState.getLastHeartbeat() ?: getString(R.string.not_available)
        lastCommandText.text = AgentState.getLastCommand() ?: getString(R.string.not_available)
        lastResultText.text = AgentState.getLastResult() ?: getString(R.string.not_available)

        val a11yActive = DrishtiAccessibilityService.isActive
        accessibilityStatusText.text = if (a11yActive) "✅ Enabled" else "⚠️ Not enabled"
        accessibilityStatusText.setTextColor(
            if (a11yActive) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt(),
        )
        grantAccessibilityButton.visibility = if (a11yActive) View.GONE else View.VISIBLE

        screenCaptureStatusText.text = "Screen sharing active (frames streaming)"
        startScreenShareButton.visibility = View.VISIBLE
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /** Open the system Accessibility settings page so user can enable our service */
    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Launch the system MediaProjection consent dialog */
    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) return
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
