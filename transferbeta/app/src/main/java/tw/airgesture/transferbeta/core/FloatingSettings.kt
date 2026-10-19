package tw.airgesture.transferbeta.core
import android.content.Context
object FloatingSettings {
    private fun pref(c:Context)=c.getSharedPreferences("floating_control_settings",Context.MODE_PRIVATE)
    fun dwellIndex(c:Context)=pref(c).getInt("dwell",1).coerceIn(0,2)
    fun dwellMs(c:Context)=longArrayOf(1000L,2000L,3000L)[dwellIndex(c)]
    fun range(c:Context)=pref(c).getInt("cursor_range",2).coerceIn(0,3)
    // Direction cue is on by default; independent of legacy palm trail preferences.
    fun actionFeedback(c:Context)=pref(c).getBoolean("action_feedback_enabled",true)
    fun palmPositionEnabled(c:Context)=pref(c).getBoolean("palm_position_enabled",true)
    fun power(c:Context)=pref(c).getInt("power_mode",1).coerceIn(0,2)
    // Per-device, persisted: existing users retain the full mode by default.
    fun gestureCamera(c:Context)=pref(c).getBoolean("gesture_camera_enabled",true)
    fun nearby(c:Context)=pref(c).getBoolean("nearby_enabled",true)
    fun pauseInGames(c:Context)=pref(c).getBoolean("pause_in_games",true)
    fun sensitivity(c:Context)=pref(c).getInt("swipe_sensitivity",1).coerceIn(0,2)
    fun youtubeShortsMode(c:Context)=pref(c).getInt("youtube_shorts_mode",0).coerceIn(0,2)
    fun saveInt(c:Context,k:String,v:Int)=pref(c).edit().putInt(k,v).apply()
    fun saveBool(c:Context,k:String,v:Boolean)=pref(c).edit().putBoolean(k,v).apply()
}
