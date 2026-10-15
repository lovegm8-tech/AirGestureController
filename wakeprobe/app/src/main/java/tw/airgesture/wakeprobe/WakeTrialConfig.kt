package tw.airgesture.wakeprobe

import android.content.Context

/**
 * Separate A/B test: only the screen-wake mechanism differs.
 * 0: transient Activity with setTurnScreenOn/requestDismissKeyguard
 * 1: legacy PowerManager wake lock from v0.1.1
 */
object WakeTrialConfig {
    const val ACTIVITY_WAKE = 0
    const val LEGACY_WAKE = 1
    private const val PREFS = "wake_probe_preferences"
    private const val MODE = "screen_wake_mode"

    fun selectedMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(MODE, ACTIVITY_WAKE).coerceIn(ACTIVITY_WAKE, LEGACY_WAKE)

    fun save(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(MODE, mode.coerceIn(ACTIVITY_WAKE, LEGACY_WAKE)).apply()
    }

    fun label(mode: Int): String = when (mode) {
        ACTIVITY_WAKE -> "Activity 亮屏 + 系統鎖定畫面驗證"
        else -> "PowerManager 舊版亮屏（對照組）"
    }
}
