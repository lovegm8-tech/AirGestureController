package tw.airgesture.controller.core

import android.content.Context

object Settings {
    private const val PREFS = "air_gesture_settings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_PERFORMANCE = "performance_mode"

    fun sensitivity(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_SENSITIVITY, 1).coerceIn(0, 2)

    fun cooldown(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_COOLDOWN, 850L).coerceIn(500L, 1600L)

    /** 0=省電、1=智慧平衡（預設）、2=高反應。舊版設定自動採用智慧平衡。 */
    fun performanceMode(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_PERFORMANCE, 1).coerceIn(0, 2)

    fun save(context: Context, sensitivity: Int, cooldown: Long, mode: Int = performanceMode(context)) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SENSITIVITY, sensitivity.coerceIn(0, 2))
            .putLong(KEY_COOLDOWN, cooldown.coerceIn(500L, 1600L))
            .putInt(KEY_PERFORMANCE, mode.coerceIn(0, 2))
            .apply()
    }

    fun engineOptions(context: Context): GestureDecisionEngine.Options = GestureDecisionEngine.Options(
        swipeDistance = when (sensitivity(context)) { 0 -> .18f; 2 -> .09f; else -> .13f },
        cooldownMs = cooldown(context),
        stillToRearmMs = 500L
    )
}
