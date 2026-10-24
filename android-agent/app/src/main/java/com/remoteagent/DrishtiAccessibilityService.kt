package com.remoteagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that enables remote input injection (tap, swipe, text)
 * without root. Works on ALL Android devices.
 *
 * Must be enabled ONCE by the user in:
 * Settings → Accessibility → Drishti Agent → Toggle ON
 *
 * After enabling as Device Owner, this can be silently granted via DPM.
 */
class DrishtiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected — input injection ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        handleAutoConfirmDialogs(event)
    }

    private fun handleAutoConfirmDialogs(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()

        // 1. Check if uninstallation was armed within the last 20 seconds
        val pkgToUninstall = pendingAutoUninstallPackage
        if (pkgToUninstall != null && now - pendingUninstallTimestamp < 20_000L) {
            val root = rootInActiveWindow ?: return
            if (tryClickUninstallConfirm(root)) {
                Log.i(TAG, "Auto-clicked uninstallation confirmation for $pkgToUninstall")
                pendingAutoUninstallPackage = null
                return
            }
        }

        // 2. Check if installation was armed within the last 30 seconds
        if (pendingAutoInstall && now - pendingInstallTimestamp < 30_000L) {
            val root = rootInActiveWindow ?: return
            if (tryClickInstallConfirm(root)) {
                Log.i(TAG, "Auto-clicked package installation confirmation")
                pendingAutoInstall = false
                return
            }
        }
    }

    private fun tryClickUninstallConfirm(root: AccessibilityNodeInfo): Boolean {
        // Look for standard Android dialog positive button (android:id/button1)
        val button1List = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (node in button1List) {
            if (node.isClickable && node.isEnabled) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Check common positive button texts
        val candidateTexts = listOf("OK", "Uninstall", "Delete", "Remove", "Yes", "Haan", "Theek hai")
        for (text in candidateTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val parent = node.parent
                if (parent != null && parent.isClickable && parent.isEnabled) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return false
    }

    private fun tryClickInstallConfirm(root: AccessibilityNodeInfo): Boolean {
        // Look for standard installer buttons
        val idList = listOf("android:id/button1", "com.android.packageinstaller:id/ok_button")
        for (id in idList) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        val candidateTexts = listOf("Install", "Update", "Done", "Open")
        for (text in candidateTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val parent = node.parent
                if (parent != null && parent.isClickable && parent.isEnabled) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── Gesture injection ────────────────────────────────────────────────────

    private fun getRealScreenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        } catch (_: Exception) {
            val metrics = resources.displayMetrics
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * Inject a tap at the given normalized coordinates.
     * @param nx  x in range [0.0, 1.0] relative to screen width
     * @param ny  y in range [0.0, 1.0] relative to screen height
     */
    fun injectTap(nx: Float, ny: Float) {
        val (width, height) = getRealScreenSize()
        val px = (nx * width).coerceIn(0f, (width - 1).toFloat())
        val py = (ny * height).coerceIn(0f, (height - 1).toFloat())
        injectTapPixels(px, py)
    }

    fun injectTapPixels(px: Float, py: Float) {
        // Android StrokeDescription requires a path with non-zero length;
        // moveTo alone is considered empty and rejected on many Android versions!
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px + 0.5f, py + 0.5f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Tap completed successfully at ($px, $py)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled by system at ($px, $py)")
            }
        }, null)
    }

    /**
     * Inject a swipe from (nx1,ny1) to (nx2,ny2) over [durationMs].
     * All coordinates normalized [0.0, 1.0].
     */
    fun injectSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long = 300L) {
        val (width, height) = getRealScreenSize()
        val px1 = (nx1 * width).coerceIn(0f, (width - 1).toFloat())
        val py1 = (ny1 * height).coerceIn(0f, (height - 1).toFloat())
        val px2 = (nx2 * width).coerceIn(0f, (width - 1).toFloat())
        val py2 = (ny2 * height).coerceIn(0f, (height - 1).toFloat())

        val path = Path().apply {
            moveTo(px1, py1)
            lineTo(px2, py2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 2000L)))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Swipe completed from ($px1, $py1) to ($px2, $py2)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled from ($px1, $py1) to ($px2, $py2)")
            }
        }, null)
    }

    /**
     * Type text into the currently focused input field.
     * Finds the focused accessibility node and injects text via ACTION_SET_TEXT.
     */
    fun injectText(text: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            node.recycle()
        } else {
            // Fallback: put into clipboard so the user or focused field can paste
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("inject", text))
        }
    }

    fun injectBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun injectHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun injectRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        private const val TAG = "DrishtiA11y"

        @Volatile
        var instance: DrishtiAccessibilityService? = null

        /** @return true if the service is currently active and ready */
        val isActive: Boolean get() = instance != null

        @Volatile
        var pendingAutoUninstallPackage: String? = null

        @Volatile
        var pendingUninstallTimestamp: Long = 0L

        @Volatile
        var pendingAutoInstall: Boolean = false

        @Volatile
        var pendingInstallTimestamp: Long = 0L

        fun armAutoUninstall(packageName: String) {
            pendingAutoUninstallPackage = packageName
            pendingUninstallTimestamp = System.currentTimeMillis()
        }

        fun armAutoInstall() {
            pendingAutoInstall = true
            pendingInstallTimestamp = System.currentTimeMillis()
        }
    }
}
