package tw.airgesture.controller.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores short local-only diagnostics, never images, audio, faces or locations. */
object WakeDiagnosticLog {
    private const val PREFS = "air_gesture_wake_log"

    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
        val previous = prefs.getString("events", "") ?: ""
        val events = (previous.lines().filter { it.isNotBlank() } +
            "$stamp  $message").takeLast(80)
        prefs.edit().putString("events", events.joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("events", "目前沒有熄屏喚醒紀錄") ?: "目前沒有熄屏喚醒紀錄"

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
