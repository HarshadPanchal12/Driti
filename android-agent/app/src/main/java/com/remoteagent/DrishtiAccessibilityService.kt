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
        // We don't observe events; we only inject gestures
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── Gesture injection ────────────────────────────────────────────────────

    /**
     * Inject a tap at the given normalized coordinates.
     * @param nx  x in range [0.0, 1.0] relative to screen width
     * @param ny  y in range [0.0, 1.0] relative to screen height
     */
    fun injectTap(nx: Float, ny: Float) {
        val metrics = resources.displayMetrics
        val px = (nx * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
        val py = (ny * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())
        injectTapPixels(px, py)
    }

    fun injectTapPixels(px: Float, py: Float) {
        // Android StrokeDescription requires a path with movement or lineTo;
        // moveTo alone is considered empty and rejected on many Android versions!
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px, py)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
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
        val metrics = resources.displayMetrics
        val px1 = (nx1 * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
        val py1 = (ny1 * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())
        val px2 = (nx2 * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
        val py2 = (ny2 * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

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
    }
}
