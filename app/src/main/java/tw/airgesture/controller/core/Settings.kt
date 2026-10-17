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
    private const val KEY_POINTER_DWELL = "pointer_dwell"
    private const val KEY_LONG_FIST = "long_fist"
    private const val KEY_ANIMATED_HAND_FEEDBACK = "animated_hand_feedback"
    private const val KEY_SMART_LIT_POWER = "smart_lit_power"
    private const val KEY_PALM_POSITION_OVERLAY = "palm_position_overlay"
    private const val KEY_MOTION_STANDBY = "motion_standby"
    private const val KEY_LOW_RESOLUTION = "low_resolution_active"

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

    /** 0=1s, 1=2s (default), 2=3s. */
    fun pointerDwellIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_POINTER_DWELL, 1).coerceIn(0, 2)

    fun pointerDwellMs(context: Context): Long =
        longArrayOf(1000L, 2000L, 3000L)[pointerDwellIndex(context)]

    /** 0=0.5s, 1=0.7s (default), 2=1s. */
    fun longFistIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LONG_FIST, 1).coerceIn(0, 2)

    fun longFistMs(context: Context): Long =
        longArrayOf(500L, 700L, 1000L)[longFistIndex(context)]

    /** Animated visual hand feedback does not change the model or touch rules. */
    fun animatedHandFeedback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANIMATED_HAND_FEEDBACK, true)

    /** Default true; OFF restores v0.2.9 lit-screen inference and camera FPS. */
    fun smartLitPower(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SMART_LIT_POWER, true)

    /** Visual-only tracking animation, independent from all gesture actions. */
    fun palmPositionOverlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PALM_POSITION_OVERLAY, true)

    /** Opt-out: smaller motion sentinel plus sparse AI when no hand is visible. */
    fun motionStandby(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MOTION_STANDBY, true)

    /** Optional preview experiment: keep 640x480 by default for finger accuracy. */
    fun lowResolutionActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOW_RESOLUTION, false)

    fun save(
        context: Context,
        sensitivity: Int,
        cooldown: Long,
        mode: Int = performanceMode(context),
        cursorRange: Int = cursorRange(context),
        tiltedPalmAssist: Boolean = tiltedPalmAssist(context),
        fastSwipeAssist: Boolean = fastSwipeAssist(context),
        screenWake: Boolean = screenWake(context),
        wakePowerMode: Int = wakePowerMode(context),
        pointerDwellIndex: Int = pointerDwellIndex(context),
        longFistIndex: Int = longFistIndex(context),
        animatedHandFeedback: Boolean = animatedHandFeedback(context),
        smartLitPower: Boolean = smartLitPower(context),
        palmPositionOverlay: Boolean = palmPositionOverlay(context),
        motionStandby: Boolean = motionStandby(context),
        lowResolutionActive: Boolean = lowResolutionActive(context)
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
            .putInt(KEY_POINTER_DWELL, pointerDwellIndex.coerceIn(0, 2))
            .putInt(KEY_LONG_FIST, longFistIndex.coerceIn(0, 2))
            .putBoolean(KEY_ANIMATED_HAND_FEEDBACK, animatedHandFeedback)
            .putBoolean(KEY_SMART_LIT_POWER, smartLitPower)
            .putBoolean(KEY_PALM_POSITION_OVERLAY, palmPositionOverlay)
            .putBoolean(KEY_MOTION_STANDBY, motionStandby)
            .putBoolean(KEY_LOW_RESOLUTION, lowResolutionActive)
            .apply()
    }

    fun engineOptions(context: Context): GestureDecisionEngine.Options = GestureDecisionEngine.Options(
        swipeDistance = when (sensitivity(context)) { 0 -> .18f; 2 -> .09f; else -> .13f },
        cooldownMs = cooldown(context),
        stillToRearmMs = 500L,
        fastSwipeAssistEnabled = fastSwipeAssist(context),
        longFistSecondClickMs = longFistMs(context)
    )
}
