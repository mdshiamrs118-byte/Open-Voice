package app.openvoice

import android.content.Context

enum class Kind { TEXT, NUMBER, CHOICE, GESTURE, COMMAND }

class Field(
    val key: String,
    val label: String,
    val kind: Kind,
    val default: String = "",
    val options: List<String> = emptyList(),
    val hint: String = ""
)

class Spec(val type: String, val title: String, val fields: List<Field>)

object Specs {

    val keyOptions = listOf(
        "back", "home", "recents", "notifications", "quick_settings", "screenshot",
        "lock_screen", "power_menu", "volume_up", "volume_down", "mute", "enter", "delete",
        "play_pause", "next_track", "previous_track", "tab", "escape", "menu", "power",
        "camera", "search"
    )

    private fun pct(key: String, label: String, def: String = "50") =
        Field(key, label, Kind.NUMBER, def, hint = "0–100")

    val all: List<Spec> = listOf(
        Spec("tap", "Tap at position", listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"))),
        Spec("double_tap", "Double tap at position", listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"))),
        Spec(
            "long_press", "Long press at position",
            listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"), Field("ms", "Hold time (ms)", Kind.NUMBER, "800"))
        ),
        Spec(
            "swipe", "Swipe",
            listOf(
                pct("x1", "Start X (%)"), pct("y1", "Start Y (%)", "75"),
                pct("x2", "End X (%)"), pct("y2", "End Y (%)", "25"),
                Field("ms", "Duration (ms)", Kind.NUMBER, "300")
            )
        ),
        Spec("gesture", "Play recorded gesture", listOf(Field("gesture", "Gesture", Kind.GESTURE))),
        Spec("click_text", "Click text on screen", listOf(Field("text", "Text or description", Kind.TEXT, hint = "e.g. Send"))),
        Spec("wait", "Wait", listOf(Field("seconds", "Seconds", Kind.NUMBER, "1"))),
        Spec("open_app", "Open app", listOf(Field("name", "App name", Kind.TEXT, hint = "e.g. Chrome"))),
        Spec("open_uri", "Open link / deep link", listOf(Field("uri", "URL", Kind.TEXT, hint = "https://…  ({text} = spoken words)"))),
        Spec("key", "Press key / system action", listOf(Field("key", "Key", Kind.CHOICE, "back", keyOptions))),
        Spec("type", "Type text", listOf(Field("text", "Text", Kind.TEXT, hint = "{text} = spoken words"))),
        Spec(
            "scroll", "Scroll",
            listOf(Field("direction", "Direction", Kind.CHOICE, "down", listOf("down", "up", "left", "right", "top", "bottom")))
        ),
        Spec("voice", "Run a built-in command", listOf(Field("phrase", "Phrase", Kind.TEXT, hint = "e.g. show numbers"))),
        Spec("shell", "Shell command (Root / Shizuku)", listOf(Field("command", "Command", Kind.TEXT, hint = "e.g. input keyevent 4"))),
        Spec("call", "Run another custom command", listOf(Field("command", "Command", Kind.COMMAND))),
        Spec("toast", "Show message in top bar", listOf(Field("message", "Message", Kind.TEXT)))
    )

    fun get(type: String): Spec? = all.firstOrNull { it.type == type }

    fun summary(ctx: Context, a: Action): String {
        val spec = get(a.type) ?: return a.type
        val parts = spec.fields.map { f ->
            val v = a.p(f.key, f.default)
            if (f.kind == Kind.GESTURE) Store.findGesture(ctx, v)?.name ?: "(missing)" else v
        }
        return spec.title + ": " + parts.joinToString(" · ")
    }
}
