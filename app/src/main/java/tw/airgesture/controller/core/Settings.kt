package tw.airgesture.controller.core

import android.content.Context

object Settings {
    private const val PREFS = "gesture_settings"
    private const val SENSITIVITY = "sensitivity"
    private const val COOLDOWN = "cooldown"
    fun sensitivity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SENSITIVITY, 1).coerceIn(0, 2)
    fun cooldown(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(COOLDOWN, 850L).coerceIn(500L, 1600L)
    fun save(context: Context, sensitivity: Int, cooldownMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(SENSITIVITY, sensitivity.coerceIn(0, 2))
            .putLong(COOLDOWN, cooldownMs.coerceIn(500L, 1600L)).apply()
    }
    fun engineOptions(context: Context): GestureDecisionEngine.Options {
        val sensitivity = sensitivity(context)
        return GestureDecisionEngine.Options(
            swipeDistance = when (sensitivity) {
                0 -> 0.19f
                2 -> 0.09f
                else -> 0.13f
            },
            cooldownMs = cooldown(context)
        )
    }
}
