package app.openvoice

import android.content.Context

object Prefs {
    const val KEY_STATUS = "status_bar"
    const val KEY_FX = "gesture_fx"
    const val KEY_EXTERNAL = "allow_external"

    private fun sp(c: Context) = c.getSharedPreferences("open_voice", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, def: Boolean = true): Boolean = sp(c).getBoolean(key, def)
    fun set(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()

    fun statusBar(c: Context) = get(c, KEY_STATUS)
    fun gestureFx(c: Context) = get(c, KEY_FX)
    fun allowExternal(c: Context) = get(c, KEY_EXTERNAL, false)
}
