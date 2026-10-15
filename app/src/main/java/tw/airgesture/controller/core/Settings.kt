package tw.airgesture.controller.core

import android.content.Context

object Settings {
    private const val PREFS = "air_gesture_settings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_PERFORMANCE = "performance_mode"
    private const val KEY_CURSOR_RANGE = "cursor_range"
    private const val KEY_TILTED_PALM_ASSIST = "tilted_palm_assist"
    private const val KEY_FAST_SWIPE_ASSIST = "fast_swipe_assist"
    private const val KEY_SCREEN_WAKE = "screen_wake"
    private const val KEY_SCREEN_WAKE_POWER = "screen_wake_power"

    fun sensitivity(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_SENSITIVITY, 1).coerceIn(0, 2)

    fun cooldown(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_COOLDOWN, 850L).coerceIn(500L, 1600L)

    /** 0=省電、1=智慧平衡（預設）、2=高反應。舊版設定自動採用智慧平衡。 */
    fun performanceMode(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_PERFORMANCE, 1).coerceIn(0, 2)

    /** Cursor reach only; existing palm/fist settings remain unchanged. */
    fun cursorRange(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_CURSOR_RANGE, 2).coerceIn(0, 3)

    /** Opt-in: preserve v0.2.3 original behavior until users enable angle support. */
    fun tiltedPalmAssist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TILTED_PALM_ASSIST, false)

    /** Experimental, independently switchable fast-swipe tracking. Defaults OFF. */
    fun fastSwipeAssist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FAST_SWIPE_ASSIST, false)

    /** Screen-off monitoring is opt-in and separately configurable. */
    fun screenWake(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_WAKE, false)

    /** 0 intermittent camera windows (default), 1 continuous camera (higher power). */
    fun wakePowerMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SCREEN_WAKE_POWER, 0).coerceIn(0, 1)

    fun save(
        context: Context,
        sensitivity: Int,
        cooldown: Long,
        mode: Int = performanceMode(context),
        cursorRange: Int = cursorRange(context),
        tiltedPalmAssist: Boolean = tiltedPalmAssist(context),
        fastSwipeAssist: Boolean = fastSwipeAssist(context),
        screenWake: Boolean = screenWake(context),
        wakePowerMode: Int = wakePowerMode(context)
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SENSITIVITY, sensitivity.coerceIn(0, 2))
            .putLong(KEY_COOLDOWN, cooldown.coerceIn(500L, 1600L))
            .putInt(KEY_PERFORMANCE, mode.coerceIn(0, 2))
            .putInt(KEY_CURSOR_RANGE, cursorRange.coerceIn(0, 3))
            .putBoolean(KEY_TILTED_PALM_ASSIST, tiltedPalmAssist)
            .putBoolean(KEY_FAST_SWIPE_ASSIST, fastSwipeAssist)
            .putBoolean(KEY_SCREEN_WAKE, screenWake)
            .putInt(KEY_SCREEN_WAKE_POWER, wakePowerMode.coerceIn(0, 1))
            .apply()
    }

    fun engineOptions(context: Context): GestureDecisionEngine.Options = GestureDecisionEngine.Options(
        swipeDistance = when (sensitivity(context)) { 0 -> .18f; 2 -> .09f; else -> .13f },
        cooldownMs = cooldown(context),
        stillToRearmMs = 500L,
        fastSwipeAssistEnabled = fastSwipeAssist(context)
    )
}
