package app.openvoice

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Placeholders usable in every text field of every action:
 *   {text}       words captured by "{text}" in the trigger phrase
 *   {name}       a variable set with "Set variable" (this run, or saved)
 *   {clipboard} {time} {hour} {minute} {date} {weekday} {battery} {app} {app_name} {random} {error}
 * Modifiers: {text:url} {text:sh} {text:upper} {text:lower} {text:trim}
 */
object Vars {
    private val re = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)(?::([a-z]+))?\\}")

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("open_voice_vars", Context.MODE_PRIVATE)

    fun getSaved(c: Context, name: String): String? = sp(c).getString(name, null)
    fun setSaved(c: Context, name: String, value: String) = sp(c).edit().putString(name, value).apply()

    fun resolve(svc: OpenVoiceService, s: String, run: Map<String, String>, autoUrl: Boolean = false): String {
        if (!s.contains('{')) return s
        return re.replace(s) { m ->
            val name = m.groupValues[1]
            val mod = m.groupValues[2]
            val v = lookup(svc, name, run)
            if (v == null) m.value else modify(v, mod, autoUrl && name == "text")
        }
    }

    private fun modify(v: String, mod: String, autoUrl: Boolean): String = when (mod) {
        "url" -> Uri.encode(v)
        "sh" -> "'" + v.replace("'", "'\\''") + "'"
        "upper" -> v.uppercase()
        "lower" -> v.lowercase()
        "trim" -> v.trim()
        "" -> if (autoUrl) Uri.encode(v) else v
        else -> v
    }

    fun lookup(svc: OpenVoiceService, name: String, run: Map<String, String>): String? {
        run[name]?.let { return it }
        val now = Calendar.getInstance()
        return when (name) {
            "clipboard" -> svc.clipboardText()
            "time" -> SimpleDateFormat("HH:mm", Locale.US).format(now.time)
            "hour" -> now.get(Calendar.HOUR_OF_DAY).toString()
            "minute" -> now.get(Calendar.MINUTE).toString()
            "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
            "weekday" -> SimpleDateFormat("EEE", Locale.US).format(now.time).lowercase()
            "battery" -> svc.batteryLevel().toString()
            "app" -> svc.foregroundPackage().orEmpty()
            "app_name" -> svc.foregroundPackage()?.let { svc.appLabel(it) }.orEmpty()
            "random" -> Random.nextInt(1, 101).toString()
            else -> getSaved(svc, name)
        }
    }
}
