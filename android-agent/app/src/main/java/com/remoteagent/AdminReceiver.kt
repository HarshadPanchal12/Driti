package com.remoteagent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin / Device Owner receiver.
 *
 * Provisioning (once per device, at mounting time):
 *   adb shell dpm set-device-owner com.remoteagent/.AdminReceiver
 *
 * Once this app is Device Owner:
 *  - MediaProjection consent dialog is suppressed automatically
 *  - Accessibility service can be granted silently via DPM
 *  - ADB can be toggled programmatically via DevicePolicyManager
 *  - All of this survives reboots with zero user interaction
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "Device Owner provisioning complete — enabling agent")
        // Auto-start the agent service after provisioning
        AgentService.start(context)
    }

    companion object {
        private const val TAG = "AdminReceiver"
    }
}
