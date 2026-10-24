package com.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * BroadcastReceiver for PackageInstaller uninstallation status callbacks.
 */
class PackageUninstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "No details"
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package uninstallation succeeded: $packageName")
                AgentState.setLastResult("Package $packageName uninstalled successfully (silent PackageInstaller)")
            }

            else -> {
                Log.e(TAG, "Package uninstallation failed ($status): $message")
                AgentState.setLastResult("Uninstallation of $packageName failed ($status): $message")
            }
        }
    }

    companion object {
        private const val TAG = "PackageUninstallReceiver"
        const val ACTION_UNINSTALL_STATUS = "com.remoteagent.ACTION_UNINSTALL_STATUS"
    }
}
