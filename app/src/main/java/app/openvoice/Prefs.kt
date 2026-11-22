package app.openvoice

import android.content.Context

object Prefs {
    const val KEY_STATUS = "status_bar"
    const val KEY_STATUS_ALWAYS = "status_always"
    const val KEY_FX = "gesture_fx"
    const val KEY_EXTERNAL = "allow_external"
    const val KEY_GRID = "grid_mode"
    const val KEY_ANIM = "animations"
    const val KEY_SYS_MODE = "system_mode"
    const val KEY_AI_MODE = "ai_mode"
    const val KEY_AI_PLAN = "ai_show_plan"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_SENS = "ai_sensitivity"
    private const val KEY_DISABLED = "disabled_builtins"

    private fun sp(c: Context) = c.getSharedPreferences("open_voice", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, def: Boolean = true): Boolean = sp(c).getBoolean(key, def)
    fun set(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()

    fun statusBar(c: Context) = get(c, KEY_STATUS)
    /** true = top bar always visible while listening; false = only while speaking. */
    fun statusAlways(c: Context) = get(c, KEY_STATUS_ALWAYS, false)
    fun gestureFx(c: Context) = get(c, KEY_FX)
    fun allowExternal(c: Context) = get(c, KEY_EXTERNAL, false)
    fun animations(c: Context) = get(c, KEY_ANIM, true)

    fun gridMode(c: Context): GridMode = GridMode.from(sp(c).getString(KEY_GRID, null))
    fun setGridMode(c: Context, m: GridMode) = sp(c).edit().putString(KEY_GRID, m.name).apply()

    /** How Wi-Fi, Bluetooth, brightness... are changed: Normal (accessibility), Root or Shizuku. */
    fun sysMode(c: Context): Mode = try {
        Mode.valueOf(sp(c).getString(KEY_SYS_MODE, "NORMAL") ?: "NORMAL")
    } catch (e: Exception) {
        Mode.NORMAL
    }

    fun setSysMode(c: Context, m: Mode) = sp(c).edit().putString(KEY_SYS_MODE, m.name).apply()

    /** Assistant mode: false = Normal (exact phrases), true = AI (understands everyday wording, multi-step). */
    fun aiMode(c: Context) = get(c, KEY_AI_MODE, false)
    fun aiShowPlan(c: Context) = get(c, KEY_AI_PLAN, true)
    fun aiModel(c: Context): String = sp(c).getString(KEY_AI_MODEL, "") ?: ""
    fun setAiModel(c: Context, id: String) = sp(c).edit().putString(KEY_AI_MODEL, id).apply()

    /** 0 = strict, 1 = balanced, 2 = loose */
    fun aiSensitivity(c: Context): Int = sp(c).getInt(KEY_AI_SENS, 1)
    fun setAiSensitivity(c: Context, v: Int) = sp(c).edit().putInt(KEY_AI_SENS, v).apply()
    fun aiThreshold(c: Context): Float = when (aiSensitivity(c)) {
        0 -> 0.66f
        2 -> 0.44f
        else -> 0.54f
    }

    fun disabledBuiltIns(c: Context): Set<String> =
        HashSet(sp(c).getStringSet(KEY_DISABLED, emptySet()) ?: emptySet())

    fun setBuiltInEnabled(c: Context, id: String, enabled: Boolean) {
        val s = disabledBuiltIns(c).toMutableSet()
        if (enabled) s.remove(id) else s.add(id)
        sp(c).edit().putStringSet(KEY_DISABLED, s).apply()
    }
}
