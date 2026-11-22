package app.openvoice

sealed class Command {
    // navigation
    object Back : Command()
    object Home : Command()
    object Recents : Command()
    object Notifications : Command()
    object QuickSettings : Command()
    object Screenshot : Command()
    object LockScreen : Command()
    object PowerMenu : Command()
    object SplitScreen : Command()

    // scrolling / swiping
    object ScrollDown : Command()
    object ScrollUp : Command()
    object ScrollLeft : Command()
    object ScrollRight : Command()
    object ScrollTop : Command()
    object ScrollBottom : Command()
    object SwipeLeft : Command()
    object SwipeRight : Command()
    object SwipeUp : Command()
    object SwipeDown : Command()
    object Refresh : Command()

    // numbers + grid
    object ShowNumbers : Command()
    object HideNumbers : Command()
    object ShowGrid : Command()
    object HideGrid : Command()
    object HideAll : Command()
    object TapHere : Command()
    object DoubleTapHere : Command()
    object LongPressHere : Command()
    data class GridPick(val numbers: List<Int>) : Command()
    data class SetGrid(val mode: GridMode) : Command()
    data class Tap(val number: Int) : Command()
    data class DoubleTap(val number: Int) : Command()
    data class LongPress(val number: Int) : Command()
    data class ClickText(val text: String) : Command()

    // text
    data class TypeText(val text: String) : Command()
    object ClearText : Command()
    object SelectAll : Command()
    object Copy : Command()
    object Paste : Command()
    object Cut : Command()
    object Delete : Command()
    object DeleteWord : Command()
    object NewLine : Command()
    object Enter : Command()
    object DictationOn : Command()
    object DictationOff : Command()

    // device / app
    data class OpenApp(val name: String) : Command()
    object VolumeUp : Command()
    object VolumeDown : Command()
    object Mute : Command()
    object Unmute : Command()

    // control
    object Sleep : Command()
    object Wake : Command()
    object ShowCommands : Command()
    object StopListening : Command()
    object StopMacro : Command()
    data class RunCustom(val name: String) : Command()
    object Unknown : Command()
}

object CommandParser {

    private val units = mapOf(
        "zero" to 0, "one" to 1, "won" to 1, "two" to 2, "to" to 2, "too" to 2,
        "three" to 3, "four" to 4, "for" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "ate" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private fun below1000(tk: List<String>): Int? {
        if (tk.isEmpty()) return 0
        var i = 0
        var v = 0
        if (tk.size >= 2 && tk[1] == "hundred") {
            val h = units[tk[0]] ?: return null
            if (h !in 1..9) return null
            v = h * 100
            i = 2
        } else if (tk[0] == "hundred") {
            v = 100
            i = 1
        }
        val rest = tk.drop(i)
        return when (rest.size) {
            0 -> v
            1 -> v + (units[rest[0]] ?: tens[rest[0]] ?: return null)
            2 -> {
                val a = tens[rest[0]] ?: return null
                val b = units[rest[1]] ?: return null
                if (b !in 1..9) return null
                v + a + b
            }
            else -> null
        }
    }

    /** "7", "1,215", "seven", "twenty one", "one hundred five", "one thousand two hundred fifteen" -> number. */
    fun parseNumber(s: String): Int? {
        val t = s.trim().replace(",", "")
        t.toIntOrNull()?.let { return it }
        val tokens = t.split(" ").filter { it.isNotEmpty() && it != "and" }
        if (tokens.isEmpty()) return null
        val ti = tokens.indexOf("thousand")
        return if (ti >= 0) {
            val left = if (ti == 0) 1 else below1000(tokens.subList(0, ti)) ?: return null
            if (left <= 0) return null
            val right = below1000(tokens.subList(ti + 1, tokens.size)) ?: return null
            left * 1000 + right
        } else {
            below1000(tokens)
        }
    }

    /**
     * Used while the grid is showing. Returns the cell numbers to pick (usually one).
     * On 3x3 steps a run of digits such as "5 2" or "52" picks several steps in a row.
     * Returns a lone out-of-range number so the caller can say "no cell N".
     */
    fun parseGridPicks(s: String, max: Int): List<Int>? {
        val t = s.trim().lowercase().trimEnd('.', '!', '?', ',')
        if (t.isEmpty()) return null
        val single = parseNumber(t)
        if (single != null && single in 1..max) return listOf(single)
        if (max <= 9) {
            val compact = t.replace(" ", "")
            if (compact.length >= 2 && compact.all { it in '1'..'9' }) {
                val d = compact.map { it - '0' }
                if (d.first() <= max) return d
            }
            val words = t.split(" ").filter { it.isNotEmpty() }
            if (words.size >= 2) {
                val picks = words.map { units[it] }
                if (picks.all { it != null && it in 1..9 } && picks.first()!! <= max) return picks.map { it!! }
            }
        }
        return single?.let { listOf(it) }
    }

    private val exact: Map<String, Command> = buildMap<String, Command> {
        fun add(c: Command, vararg phrases: String) = phrases.forEach { put(it, c) }

        add(Command.Back, "back", "go back", "press back")
        add(Command.Home, "home", "go home", "press home")
        add(Command.Recents, "recents", "recent apps", "show recents", "overview")
        add(Command.Notifications, "notifications", "show notifications", "open notifications")
        add(Command.QuickSettings, "quick settings", "open quick settings")
        add(Command.Screenshot, "screenshot", "take screenshot", "take a screenshot")
        add(Command.LockScreen, "lock screen", "lock phone")
        add(Command.PowerMenu, "power menu", "power options")
        add(Command.SplitScreen, "split screen")

        add(Command.ScrollDown, "scroll down", "page down")
        add(Command.ScrollUp, "scroll up", "page up")
        add(Command.ScrollLeft, "scroll left")
        add(Command.ScrollRight, "scroll right")
        add(Command.ScrollTop, "scroll to top", "go to top", "scroll top")
        add(Command.ScrollBottom, "scroll to bottom", "go to bottom", "scroll bottom")
        add(Command.SwipeLeft, "swipe left")
        add(Command.SwipeRight, "swipe right")
        add(Command.SwipeUp, "swipe up")
        add(Command.SwipeDown, "swipe down")
        add(Command.Refresh, "refresh", "pull to refresh")

        add(Command.ShowNumbers, "show numbers", "show number", "numbers on")
        add(Command.HideNumbers, "hide numbers", "hide number", "numbers off")
        add(Command.ShowGrid, "show grid", "grid", "grid on")
        add(Command.HideGrid, "hide grid", "close grid", "grid off")
        add(Command.HideAll, "cancel", "hide all", "clear screen")
        add(Command.SetGrid(GridMode.BIG), "set grid big", "grid big", "big grid", "set grid to big")
        add(Command.SetGrid(GridMode.MEDIUM), "set grid medium", "grid medium", "medium grid", "set grid to medium")
        add(Command.SetGrid(GridMode.SMALL), "set grid small", "grid small", "small grid", "set grid to small")
        add(Command.TapHere, "tap", "click", "tap here", "click here")
        add(Command.DoubleTapHere, "double tap", "double click")
        add(Command.LongPressHere, "long press", "hold", "press and hold")

        add(Command.ClearText, "clear", "clear text", "delete all")
        add(Command.SelectAll, "select all")
        add(Command.Copy, "copy", "copy that")
        add(Command.Paste, "paste")
        add(Command.Cut, "cut", "cut that")
        add(Command.Delete, "delete", "backspace", "delete that")
        add(Command.DeleteWord, "delete word", "delete last word")
        add(Command.NewLine, "new line", "next line")
        add(Command.Enter, "enter", "press enter", "submit")
        add(Command.DictationOn, "start dictation", "dictation", "dictate")
        add(Command.DictationOff, "stop dictation", "end dictation")

        add(Command.VolumeUp, "volume up", "louder")
        add(Command.VolumeDown, "volume down", "quieter")
        add(Command.Mute, "mute", "mute volume")
        add(Command.Unmute, "unmute", "unmute volume")

        add(Command.Sleep, "pause listening", "pause voice", "go to sleep")
        add(Command.Wake, "resume listening", "wake up", "start listening")
        add(Command.ShowCommands, "show commands", "what can i say")
        add(Command.StopListening, "stop listening", "stop voice")
        add(Command.StopMacro, "stop macro", "stop running", "stop command")
    }

    private val tapVerbs = listOf("tap ", "click ", "press ", "select ", "touch ")

    private fun cleanTarget(s: String): String =
        s.trim().removePrefix("on ").removePrefix("the ").trim()

    fun parse(raw: String): Command {
        val t = raw.trim().lowercase().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return Command.Unknown

        exact[t]?.let { return it }

        for (v in listOf("double tap ", "double click ")) {
            if (t.startsWith(v)) {
                parseNumber(cleanTarget(t.removePrefix(v)))?.let { return Command.DoubleTap(it) }
            }
        }
        for (v in listOf("long press ", "long click ", "hold ")) {
            if (t.startsWith(v)) {
                parseNumber(cleanTarget(t.removePrefix(v)))?.let { return Command.LongPress(it) }
            }
        }
        for (v in tapVerbs) {
            if (t.startsWith(v)) {
                val rest = cleanTarget(t.removePrefix(v))
                if (rest.isEmpty()) return Command.Unknown
                parseNumber(rest)?.let { return Command.Tap(it) }
                return Command.ClickText(rest)
            }
        }
        for (v in listOf("open ", "launch ", "go to ")) {
            if (t.startsWith(v)) {
                val name = t.removePrefix(v).trim()
                if (name.isNotEmpty()) return Command.OpenApp(name)
            }
        }
        if (t.startsWith("run ")) {
            val name = t.removePrefix("run ").trim()
            if (name.isNotEmpty()) return Command.RunCustom(name)
        }
        for (v in listOf("type ", "write ")) {
            if (t.startsWith(v)) {
                val original = raw.trim().substring(v.length).trim()
                if (original.isNotEmpty()) return Command.TypeText(original)
            }
        }
        return Command.Unknown
    }
}
