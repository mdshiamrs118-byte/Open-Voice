package app.openvoice

import android.content.Context

enum class Kind { TEXT, NUMBER, CHOICE, GESTURE, COMMAND }

class Field(
    val key: String,
    val label: String,
    val kind: Kind,
    val default: String = "",
    val options: List<String> = emptyList(),
    val hint: String = "",
    val multiline: Boolean = false
)

class Spec(
    val type: String,
    val title: String,
    val category: String,
    val fields: List<Field>,
    val help: String = ""
)

object Specs {

    const val C_TOUCH = "Touch & gestures"
    const val C_APPS = "Apps, links & shortcuts"
    const val C_KEYS = "Keys & text"
    const val C_FLOW = "Flow control (if / loops / try)"
    const val C_VARS = "Variables & data"
    const val C_FEEDBACK = "Feedback & device"

    val keyOptions = listOf(
        "back", "home", "recents", "notifications", "quick_settings", "screenshot",
        "lock_screen", "power_menu", "volume_up", "volume_down", "mute", "enter", "delete",
        "play_pause", "next_track", "previous_track", "tab", "escape", "menu", "power",
        "camera", "search"
    )

    val checks = listOf(
        "value", "foreground_app", "text_on_screen", "app_installed", "time_between",
        "day_of_week", "battery_level", "charging", "internet_connected", "wifi_connected",
        "device_locked", "orientation", "random_chance"
    )

    val ops = listOf(
        "equals", "not_equals", "contains", "not_contains", "starts_with", "ends_with",
        "matches", "is_empty", "not_empty", "gt", "lt", "gte", "lte"
    )

    private val condHelp =
        "Checks: value = Left op Right · foreground_app = Right (name/package) · text_on_screen = Right · " +
            "app_installed = Right · time_between = Left (start) to Right (end), 24h HH:mm · " +
            "day_of_week = Right like mon,tue or weekdays · battery_level = op + Right number · " +
            "orientation = Right portrait/landscape · random_chance = Right percent. " +
            "Any field accepts {text}, {variables}, {clipboard}, {time}…"

    private fun cond(): List<Field> = listOf(
        Field("check", "Check", Kind.CHOICE, "value", checks),
        Field("left", "Left value (for “value”) / start time", Kind.TEXT, hint = "e.g. {text}"),
        Field("op", "Operator", Kind.CHOICE, "equals", ops),
        Field("right", "Right value / target / end time", Kind.TEXT, hint = "e.g. chrome"),
        Field("negate", "Invert result (NOT)", Kind.CHOICE, "no", listOf("no", "yes")),
        Field("check2", "Second check (optional)", Kind.CHOICE, "none", listOf("none") + checks),
        Field("join", "Combine the two checks with", Kind.CHOICE, "and", listOf("and", "or")),
        Field("left2", "Second: left value / start time", Kind.TEXT),
        Field("op2", "Second: operator", Kind.CHOICE, "equals", ops),
        Field("right2", "Second: right value / target", Kind.TEXT),
        Field("negate2", "Second: invert result", Kind.CHOICE, "no", listOf("no", "yes"))
    )

    private fun pct(key: String, label: String, def: String = "50") =
        Field(key, label, Kind.NUMBER, def, hint = "0–100, or {text}")

    val all: List<Spec> = listOf(
        // ---- touch & gestures ----
        Spec("tap", "Tap at position", C_TOUCH, listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"))),
        Spec("double_tap", "Double tap at position", C_TOUCH, listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"))),
        Spec(
            "long_press", "Long press at position", C_TOUCH,
            listOf(pct("x", "Horizontal position (%)"), pct("y", "Vertical position (%)"), Field("ms", "Hold time (ms)", Kind.NUMBER, "800"))
        ),
        Spec(
            "swipe", "Swipe", C_TOUCH,
            listOf(
                pct("x1", "Start X (%)"), pct("y1", "Start Y (%)", "75"),
                pct("x2", "End X (%)"), pct("y2", "End Y (%)", "25"),
                Field("ms", "Duration (ms)", Kind.NUMBER, "300")
            )
        ),
        Spec("gesture", "Play recorded gesture", C_TOUCH, listOf(Field("gesture", "Gesture", Kind.GESTURE))),
        Spec(
            "scroll", "Scroll", C_TOUCH,
            listOf(Field("direction", "Direction", Kind.CHOICE, "down", listOf("down", "up", "left", "right", "top", "bottom")))
        ),
        Spec("click_text", "Click text on screen", C_TOUCH, listOf(Field("text", "Text or description", Kind.TEXT, hint = "e.g. Send or {text}"))),

        // ---- apps, links, shortcuts ----
        Spec("open_app", "Open app", C_APPS, listOf(Field("name", "App name", Kind.TEXT, hint = "e.g. Chrome or {text}"))),
        Spec("open_uri", "Open link / deep link", C_APPS, listOf(Field("uri", "URL", Kind.TEXT, hint = "https://…?q={text}"))),
        Spec(
            "app_shortcut", "Trigger another app's shortcut", C_APPS,
            listOf(
                Field("app", "App (name or package)", Kind.TEXT, hint = "e.g. Chrome or {text}"),
                Field("shortcut", "Shortcut (name or id)", Kind.TEXT, hint = "e.g. New incognito tab")
            ),
            help = "Runs the static shortcuts apps show when you long-press their icon. Use Browse to pick one. " +
                "If an app keeps its shortcut private, switch the command to Root or Shizuku mode."
        ),
        Spec(
            "intent", "Send custom intent", C_APPS,
            listOf(
                Field("kind", "Send as", Kind.CHOICE, "activity", listOf("activity", "broadcast", "service")),
                Field("action", "Action", Kind.TEXT, "android.intent.action.VIEW"),
                Field("data", "Data URI", Kind.TEXT, hint = "https://…, geo:…, tel:…"),
                Field("package", "Package", Kind.TEXT, hint = "com.example.app"),
                Field("class", "Class", Kind.TEXT, hint = "com.example.app.MainActivity or .MainActivity"),
                Field("type", "MIME type", Kind.TEXT, hint = "text/plain"),
                Field("category", "Categories (comma separated)", Kind.TEXT),
                Field("extras", "Extras (one per line: key=value, int:key=5, bool:key=true)", Kind.TEXT, multiline = true)
            )
        ),
        Spec("shell", "Shell command (Root / Shizuku)", C_APPS, listOf(Field("command", "Command", Kind.TEXT, hint = "e.g. input keyevent 4"))),

        // ---- keys & text ----
        Spec("key", "Press key / system action", C_KEYS, listOf(Field("key", "Key", Kind.CHOICE, "back", keyOptions))),
        Spec("type", "Type text", C_KEYS, listOf(Field("text", "Text", Kind.TEXT, hint = "{text} = spoken words"))),
        Spec("voice", "Run a built-in command", C_KEYS, listOf(Field("phrase", "Phrase", Kind.TEXT, hint = "e.g. show numbers"))),
        Spec("set_clipboard", "Copy text to clipboard", C_KEYS, listOf(Field("text", "Text", Kind.TEXT))),

        // ---- flow control ----
        Spec("if", "If (condition)", C_FLOW, cond(), help = condHelp),
        Spec("else_if", "Else if (condition)", C_FLOW, cond(), help = condHelp),
        Spec("else", "Else", C_FLOW, emptyList()),
        Spec("end_if", "End if", C_FLOW, emptyList()),
        Spec("repeat", "Repeat N times", C_FLOW, listOf(Field("count", "Times", Kind.NUMBER, "3", hint = "number or {text}"))),
        Spec("while", "While (condition)", C_FLOW, cond(), help = condHelp),
        Spec("end_loop", "End loop", C_FLOW, emptyList()),
        Spec("break", "Break (leave loop)", C_FLOW, emptyList()),
        Spec("continue", "Continue (next loop round)", C_FLOW, emptyList()),
        Spec(
            "wait_until", "Wait until (condition)", C_FLOW,
            cond() + listOf(
                Field("timeout", "Give up after (seconds)", Kind.NUMBER, "10"),
                Field("on_timeout", "If it times out", Kind.CHOICE, "continue", listOf("continue", "error"))
            ),
            help = condHelp
        ),
        Spec("wait", "Wait", C_FLOW, listOf(Field("seconds", "Seconds", Kind.NUMBER, "1", hint = "e.g. 1.5 or {text}"))),
        Spec(
            "stop", "Stop", C_FLOW,
            listOf(
                Field("scope", "Stop", Kind.CHOICE, "this_command", listOf("this_command", "all")),
                Field("message", "Message (optional)", Kind.TEXT)
            )
        ),
        Spec("try", "Try (catch errors)", C_FLOW, emptyList(), help = "Adds Try / Catch / End try. Put risky actions between Try and Catch; {error} holds the message."),
        Spec("catch", "Catch (on error)", C_FLOW, emptyList()),
        Spec("end_try", "End try", C_FLOW, emptyList()),
        Spec("call", "Run another custom command", C_FLOW, listOf(Field("command", "Command", Kind.COMMAND))),

        // ---- variables ----
        Spec(
            "set_var", "Set variable", C_VARS,
            listOf(
                Field("name", "Name", Kind.TEXT, hint = "letters, digits, _"),
                Field("op", "Operation", Kind.CHOICE, "set", listOf("set", "add", "subtract", "multiply", "append", "clear")),
                Field("value", "Value", Kind.TEXT, hint = "{text}, 5, {count}…"),
                Field("scope", "Keep", Kind.CHOICE, "run", listOf("run", "saved"))
            ),
            help = "Use it later as {name}. “run” lasts for this run only; “saved” survives restarts."
        ),
        Spec(
            "get_text", "Read into variable", C_VARS,
            listOf(
                Field("var", "Variable name", Kind.TEXT, "result"),
                Field("source", "Read from", Kind.CHOICE, "clipboard", listOf("clipboard", "focused_field", "screen", "foreground_app", "battery", "time"))
            )
        ),

        // ---- feedback ----
        Spec("toast", "Show message in top bar", C_FEEDBACK, listOf(Field("message", "Message", Kind.TEXT, hint = "e.g. Battery {battery}%"))),
        Spec("speak", "Speak text aloud", C_FEEDBACK, listOf(Field("text", "Text", Kind.TEXT)),
            help = "The microphone may hear the voice; pause listening if that causes loops."),
        Spec("vibrate", "Vibrate", C_FEEDBACK, listOf(Field("ms", "Milliseconds", Kind.NUMBER, "200")))
    )

    fun get(type: String): Spec? = all.firstOrNull { it.type == type }

    /** Adds the matching end markers when a block-opening action is inserted. */
    fun withEnds(a: Action): List<Action> = when (a.type) {
        "if" -> listOf(a, Action("end_if"))
        "repeat", "while" -> listOf(a, Action("end_loop"))
        "try" -> listOf(a, Action("catch"), Action("end_try"))
        else -> listOf(a)
    }

    fun summary(ctx: Context, a: Action): String {
        val spec = get(a.type) ?: return a.type
        return when (a.type) {
            "if" -> "IF " + Conditions.describe(a)
            "else_if" -> "ELSE IF " + Conditions.describe(a)
            "else" -> "ELSE"
            "end_if" -> "END IF"
            "repeat" -> "REPEAT ${a.p("count", "1")}×"
            "while" -> "WHILE " + Conditions.describe(a)
            "end_loop" -> "END LOOP"
            "break" -> "BREAK"
            "continue" -> "CONTINUE"
            "try" -> "TRY"
            "catch" -> "CATCH (on error)"
            "end_try" -> "END TRY"
            "wait_until" -> "WAIT UNTIL " + Conditions.describe(a) + " (max ${a.p("timeout", "10")}s)"
            "stop" -> "STOP" + if (a.p("scope") == "all") " everything" else ""
            "set_var" -> "SET ${a.p("name")} ${a.p("op", "set")} ${a.p("value")}".trim()
            "get_text" -> "READ ${a.p("source")} → {${a.p("var")}}"
            "call" -> "RUN “${a.p("command")}”"
            "app_shortcut" -> "SHORTCUT ${a.p("app")} → ${a.p("shortcut")}"
            "intent" -> "INTENT ${a.p("kind", "activity")} " +
                listOf(a.p("action"), a.p("data"), a.p("package")).filter { it.isNotBlank() }.joinToString(" ")
            else -> {
                val parts = spec.fields.map { f ->
                    val v = a.p(f.key, f.default)
                    if (f.kind == Kind.GESTURE) Store.findGesture(ctx, v)?.name ?: "(missing)" else v
                }
                spec.title + ": " + parts.joinToString(" · ")
            }
        }
    }
}
