package app.openvoice

sealed class Command {
    object Back : Command()
    object Home : Command()
    object Recents : Command()
    object Notifications : Command()
    object QuickSettings : Command()
    object ScrollDown : Command()
    object ScrollUp : Command()
    object SwipeLeft : Command()
    object SwipeRight : Command()
    object ShowNumbers : Command()
    object HideNumbers : Command()
    object ClearText : Command()
    object Enter : Command()
    object DictationOn : Command()
    object DictationOff : Command()
    object StopListening : Command()
    data class Tap(val number: Int) : Command()
    data class LongPress(val number: Int) : Command()
    data class ClickText(val text: String) : Command()
    data class OpenApp(val name: String) : Command()
    data class TypeText(val text: String) : Command()
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

    /** "7", "seven", "twenty one" -> number, otherwise null. */
    fun parseNumber(s: String): Int? {
        val t = s.trim()
        t.toIntOrNull()?.let { return it }
        val parts = t.split(" ").filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> units[parts[0]] ?: tens[parts[0]]
            2 -> {
                val a = tens[parts[0]]
                val b = units[parts[1]]
                if (a != null && b != null && b in 1..9) a + b else null
            }
            else -> null
        }
    }

    private val tapVerbs = listOf("tap ", "click ", "press ", "select ", "touch ")

    fun parse(raw: String): Command {
        val t = raw.trim().lowercase().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return Command.Unknown

        when (t) {
            "back", "go back", "press back" -> return Command.Back
            "home", "go home", "press home" -> return Command.Home
            "recents", "recent apps", "show recents", "overview" -> return Command.Recents
            "notifications", "show notifications", "open notifications" -> return Command.Notifications
            "quick settings", "open quick settings" -> return Command.QuickSettings
            "scroll down", "page down" -> return Command.ScrollDown
            "scroll up", "page up" -> return Command.ScrollUp
            "swipe left" -> return Command.SwipeLeft
            "swipe right" -> return Command.SwipeRight
            "show numbers", "show number", "numbers on" -> return Command.ShowNumbers
            "hide numbers", "hide number", "numbers off" -> return Command.HideNumbers
            "clear", "clear text", "delete all" -> return Command.ClearText
            "enter", "press enter", "submit" -> return Command.Enter
            "start dictation", "dictation", "dictate" -> return Command.DictationOn
            "stop dictation", "end dictation" -> return Command.DictationOff
            "stop listening", "stop voice" -> return Command.StopListening
        }

        for (v in listOf("long press ", "long click ")) {
            if (t.startsWith(v)) {
                parseNumber(t.removePrefix(v))?.let { return Command.LongPress(it) }
            }
        }

        for (v in tapVerbs) {
            if (t.startsWith(v)) {
                val rest = t.removePrefix(v).trim()
                if (rest.isEmpty()) return Command.Unknown
                parseNumber(rest)?.let { return Command.Tap(it) }
                return Command.ClickText(rest)
            }
        }

        if (t.startsWith("open ")) {
            val name = t.removePrefix("open ").trim()
            if (name.isNotEmpty()) return Command.OpenApp(name)
        }

        for (v in listOf("type ", "write ")) {
            if (t.startsWith(v)) {
                // keep the original casing of what was said
                val original = raw.trim().substring(v.length).trim()
                if (original.isNotEmpty()) return Command.TypeText(original)
            }
        }

        return Command.Unknown
    }
}
