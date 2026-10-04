package com.remoteagent

/**
 * Kiayo K518: mirrors Settings → Display → "Hide navigation".
 * Uses persist props + broadcasts (same sequence as KiayoSystemBarsHelper).
 */
object KiayoSystemBarsHelper {
    private const val PROP_NAV_BAR = "persist.kiayo.status.naviBar"
    private const val PROP_FULLSCREEN = "persist.sys.fullscreen"
    private const val KIAYO_VERSION_PROP = "ro.kiayo.version.first"

    private const val ACTION_TCHIP_BAR = "com.tchip.changeBarHideStatus"
    private const val ACTION_STATUSBAR_CHANGE = "com.kiayo.statusbar.change"
    private const val ACTION_CMD_SYNC = "com.kiayo.cmd.sync"

    private const val NAV_BAR_VISIBLE = "1"
    private const val NAV_BAR_HIDDEN = "0"
    private const val FULLSCREEN_NORMAL = "normal"
    private const val FULLSCREEN_FULL = "full"

    fun isKiayoDevice(): Boolean {
        return getSystemProperty(KIAYO_VERSION_PROP, "").isNotEmpty()
    }

    fun hideNavigationBars(rootExecutor: RootExecutor): ExecutionResult {
        if (!isKiayoDevice()) {
            return ExecutionResult(
                success = false,
                output = "Not a Kiayo device (ro.kiayo.version.first is empty)",
            )
        }
        return applyBarVisibility(rootExecutor, hidden = true)
    }

    fun showNavigationBars(rootExecutor: RootExecutor): ExecutionResult {
        if (!isKiayoDevice()) {
            return ExecutionResult(
                success = false,
                output = "Not a Kiayo device (ro.kiayo.version.first is empty)",
            )
        }
        return applyBarVisibility(rootExecutor, hidden = false)
    }

    private fun applyBarVisibility(
        rootExecutor: RootExecutor,
        hidden: Boolean,
    ): ExecutionResult {
        val navValue = if (hidden) NAV_BAR_HIDDEN else NAV_BAR_VISIBLE
        val fullValue = if (hidden) FULLSCREEN_FULL else FULLSCREEN_NORMAL
        val visibleFlag = if (hidden) "false" else "true"

        return rootExecutor.executeScript(
            listOf(
                "setprop $PROP_NAV_BAR $navValue",
                "setprop $PROP_FULLSCREEN $fullValue",
                "am broadcast --user 0 -a $ACTION_TCHIP_BAR",
                "am broadcast --user 0 -a $ACTION_STATUSBAR_CHANGE --ez visible $visibleFlag",
                "am broadcast --user 0 -a $ACTION_CMD_SYNC",
            ),
        )
    }

    private fun getSystemProperty(key: String, defaultValue: String): String {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod = systemProperties.getMethod(
                "get",
                String::class.java,
                String::class.java,
            )
            getMethod.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            defaultValue
        }
    }
}
