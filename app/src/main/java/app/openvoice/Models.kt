package app.openvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Mode(val label: String) {
    NORMAL("Normal (Accessibility)"),
    ROOT("Root (su)"),
    SHIZUKU("Shizuku")
}

/** One step of a custom command. Parameters are stored as strings so the editor can be generic. */
data class Action(val type: String, val params: Map<String, String> = emptyMap()) {
    fun p(key: String, def: String = ""): String = params[key] ?: def
    fun num(key: String, def: Double): Double = params[key]?.toDoubleOrNull() ?: def

    fun toJson(): JSONObject = JSONObject().put("type", type).put("params", JSONObject(params))

    companion object {
        fun fromJson(o: JSONObject): Action {
            val po = o.optJSONObject("params") ?: JSONObject()
            val m = LinkedHashMap<String, String>()
            po.keys().forEach { m[it] = po.optString(it) }
            return Action(o.optString("type"), m)
        }
    }
}

class CustomCommand(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var phrases: MutableList<String> = mutableListOf(),
    var mode: Mode = Mode.NORMAL,
    var repeat: Int = 1,
    var enabled: Boolean = true,
    var continueOnError: Boolean = false,
    val actions: MutableList<Action> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("phrases", JSONArray(phrases))
        .put("mode", mode.name)
        .put("repeat", repeat)
        .put("enabled", enabled)
        .put("continueOnError", continueOnError)
        .put("actions", JSONArray().also { arr -> actions.forEach { arr.put(it.toJson()) } })

    fun copyDeep(): CustomCommand = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject, fresh: Boolean = false): CustomCommand {
            val phrases = mutableListOf<String>()
            o.optJSONArray("phrases")?.let { a -> for (i in 0 until a.length()) phrases.add(a.optString(i)) }
            val actions = mutableListOf<Action>()
            o.optJSONArray("actions")?.let { a ->
                for (i in 0 until a.length()) actions.add(Action.fromJson(a.getJSONObject(i)))
            }
            val mode = try {
                Mode.valueOf(o.optString("mode", "NORMAL"))
            } catch (e: Exception) {
                Mode.NORMAL
            }
            return CustomCommand(
                id = if (fresh) UUID.randomUUID().toString() else o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                phrases = phrases,
                mode = mode,
                repeat = o.optInt("repeat", 1).coerceIn(1, 99),
                enabled = o.optBoolean("enabled", true),
                continueOnError = o.optBoolean("continueOnError", false),
                actions = actions
            )
        }
    }
}

/** Point as a fraction (0..1) of the screen so gestures work on any resolution. */
class Pt(val x: Float, val y: Float)

class Stroke(val startMs: Long, val durMs: Long, val points: List<Pt>) {
    fun toJson(): JSONObject = JSONObject()
        .put("s", startMs)
        .put("d", durMs)
        .put("p", JSONArray().also { arr ->
            points.forEach { arr.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        })

    companion object {
        fun fromJson(o: JSONObject): Stroke {
            val pa = o.getJSONArray("p")
            val pts = (0 until pa.length()).map {
                val q = pa.getJSONArray(it)
                Pt(q.getDouble(0).toFloat(), q.getDouble(1).toFloat())
            }
            return Stroke(o.optLong("s"), o.optLong("d", 50), pts)
        }
    }
}

class Gesture(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    val strokes: List<Stroke> = emptyList()
) {
    val totalMs: Long get() = strokes.maxOfOrNull { it.startMs + it.durMs } ?: 0L

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("strokes", JSONArray().also { arr -> strokes.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Gesture {
            val sa = o.optJSONArray("strokes") ?: JSONArray()
            return Gesture(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                strokes = (0 until sa.length()).map { Stroke.fromJson(sa.getJSONObject(it)) }
            )
        }
    }
}

object Store {
    private const val K_CMD = "custom_commands"
    private const val K_GES = "gestures"

    private var cmds: MutableList<CustomCommand>? = null
    private var gests: MutableList<Gesture>? = null

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("open_voice_data", Context.MODE_PRIVATE)

    // ---- commands ----
    @Synchronized
    fun commands(c: Context): MutableList<CustomCommand> {
        cmds?.let { return it }
        val list = mutableListOf<CustomCommand>()
        try {
            val arr = JSONArray(sp(c).getString(K_CMD, "[]"))
            for (i in 0 until arr.length()) list.add(CustomCommand.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        cmds = list
        return list
    }

    @Synchronized
    fun saveCommands(c: Context) {
        val arr = JSONArray()
        commands(c).forEach { arr.put(it.toJson()) }
        sp(c).edit().putString(K_CMD, arr.toString()).apply()
    }

    fun upsertCommand(c: Context, cmd: CustomCommand) {
        val l = commands(c)
        val i = l.indexOfFirst { it.id == cmd.id }
        if (i >= 0) l[i] = cmd else l.add(cmd)
        saveCommands(c)
    }

    fun deleteCommand(c: Context, id: String) {
        commands(c).removeAll { it.id == id }
        saveCommands(c)
    }

    fun findCommand(c: Context, id: String) = commands(c).firstOrNull { it.id == id }

    fun findCommandByName(c: Context, name: String): CustomCommand? {
        val n = CustomMatcher.norm(name)
        return commands(c).firstOrNull { it.enabled && CustomMatcher.norm(it.name) == n }
    }

    // ---- gestures ----
    @Synchronized
    fun gestures(c: Context): MutableList<Gesture> {
        gests?.let { return it }
        val list = mutableListOf<Gesture>()
        try {
            val arr = JSONArray(sp(c).getString(K_GES, "[]"))
            for (i in 0 until arr.length()) list.add(Gesture.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        gests = list
        return list
    }

    @Synchronized
    fun saveGestures(c: Context) {
        val arr = JSONArray()
        gestures(c).forEach { arr.put(it.toJson()) }
        sp(c).edit().putString(K_GES, arr.toString()).apply()
    }

    fun addGesture(c: Context, g: Gesture) {
        gestures(c).add(g)
        saveGestures(c)
    }

    fun deleteGesture(c: Context, id: String) {
        gestures(c).removeAll { it.id == id }
        saveGestures(c)
    }

    fun findGesture(c: Context, id: String) = gestures(c).firstOrNull { it.id == id }

    // ---- import / export ----
    fun exportJson(c: Context): String {
        val o = JSONObject()
        o.put("app", "OpenVoice")
        o.put("version", 1)
        o.put("commands", JSONArray().also { a -> commands(c).forEach { a.put(it.toJson()) } })
        o.put("gestures", JSONArray().also { a -> gestures(c).forEach { a.put(it.toJson()) } })
        return o.toString(2)
    }

    /** Returns the number of commands imported, or -1 if the text isn't valid. */
    fun importJson(c: Context, text: String): Int {
        return try {
            val o = JSONObject(text)
            val ga = o.optJSONArray("gestures") ?: JSONArray()
            val have = gestures(c).map { it.id }.toMutableSet()
            for (i in 0 until ga.length()) {
                val g = Gesture.fromJson(ga.getJSONObject(i))
                if (have.add(g.id)) gestures(c).add(g)
            }
            saveGestures(c)
            val ca = o.optJSONArray("commands") ?: JSONArray()
            var n = 0
            for (i in 0 until ca.length()) {
                commands(c).add(CustomCommand.fromJson(ca.getJSONObject(i), fresh = true))
                n++
            }
            saveCommands(c)
            n
        } catch (e: Exception) {
            -1
        }
    }
}
