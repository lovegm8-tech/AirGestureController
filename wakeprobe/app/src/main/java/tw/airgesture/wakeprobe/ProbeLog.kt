package tw.airgesture.wakeprobe

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent diagnostic timeline, no images/video/location or network. */
object ProbeLog {
    private const val PREF = "wake_probe_debug"
    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
        val old = prefs.getString("events", "") ?: ""
        val latest = (old.lines().filter { it.isNotBlank() } + "$stamp  $message").takeLast(90)
        prefs.edit().putString("events", latest.joinToString("\n")).apply()
    }
    fun read(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("events", "尚無紀錄") ?: "尚無紀錄"
    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
