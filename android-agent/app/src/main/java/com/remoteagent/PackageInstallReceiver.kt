package com.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * BroadcastReceiver for PackageInstaller session installation status callbacks.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "No details"
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package installation succeeded: $packageName")
                AgentState.setLastResult("Package $packageName installed successfully (silent PackageInstaller)")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "User action required for package installation of $packageName")
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    try {
                        val a11y = DrishtiAccessibilityService.instance
                        if (a11y != null) {
                            a11y.startActivity(confirmIntent)
                        } else {
                            context.startActivity(confirmIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch pending user action: ${e.message}")
                    }
                }
            }

            else -> {
                Log.e(TAG, "Package installation failed ($status): $message")
                AgentState.setLastResult("Installation failed ($status): $message")
            }
        }
    }

    companion object {
        private const val TAG = "PackageInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.remoteagent.ACTION_INSTALL_STATUS"
    }
}
