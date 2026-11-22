package app.openvoice

import android.content.Context

object Prefs {
    const val KEY_STATUS = "status_bar"
    const val KEY_STATUS_ALWAYS = "status_always"
    const val KEY_FX = "gesture_fx"
    const val KEY_EXTERNAL = "allow_external"
    const val KEY_GRID = "grid_mode"

    private fun sp(c: Context) = c.getSharedPreferences("open_voice", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, def: Boolean = true): Boolean = sp(c).getBoolean(key, def)
    fun set(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()

    fun statusBar(c: Context) = get(c, KEY_STATUS)
    /** true = top bar always visible while listening; false = only while speaking. */
    fun statusAlways(c: Context) = get(c, KEY_STATUS_ALWAYS, false)
    fun gestureFx(c: Context) = get(c, KEY_FX)
    fun allowExternal(c: Context) = get(c, KEY_EXTERNAL, false)

    fun gridMode(c: Context): GridMode = GridMode.from(sp(c).getString(KEY_GRID, null))
    fun setGridMode(c: Context, m: GridMode) = sp(c).edit().putString(KEY_GRID, m.name).apply()
}
