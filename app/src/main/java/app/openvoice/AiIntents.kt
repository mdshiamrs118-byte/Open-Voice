package app.openvoice

import android.net.Uri

/** One executable step of an AI plan. */
sealed class PlanStep(val label: String) {
    class Builtin(val cmd: Command, label: String, val waitAfter: Long = 0L) : PlanStep(label)
    class Custom(val cmd: CustomCommand, val captured: String?, label: String) : PlanStep(label)
    class Pause(val ms: Long) : PlanStep("wait")
}

/** What the planner knows about the phone right now (gathered on the main thread). */
class AiContext(
    val apps: List<Pair<String, String>>, // label, package
    val screenTexts: List<String>,
    val customs: List<CustomCommand>
) {
    fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun findApp(text: String): String? {
        val padded = " ${norm(text)} "
        var best: String? = null
        var bestLen = 0
        for ((label, _) in apps) {
            val l = norm(label)
            if (l.length < 2) continue
            if (l.length > bestLen && padded.contains(" $l ")) {
                best = label
                bestLen = l.length
            }
        }
        if (best != null) return best
        val m = Regex("(?:open|launch|start|run|use|go to|switch to|show)\\s+(?:the\\s+)?(?:app\\s+)?(.+)").find(norm(text))
            ?: return null
        val rem = m.groupValues[1].replace(Regex("\\b(app|application)\\b"), "").trim()
        if (rem.isEmpty()) return null
        return apps.firstOrNull { norm(it.first).startsWith(rem) }?.first
            ?: apps.firstOrNull { norm(it.first).contains(rem) }?.first
    }

    fun hasText(t: String): Boolean = screenTexts.any { it.contains(t, ignoreCase = true) }

    /** Picks the on-screen text the user most likely means; falls back to the words after the verb. */
    fun findScreenTarget(text: String): String? {
        val padded = " ${norm(text)} "
        var best: String? = null
        var bestLen = 0
        for (t in screenTexts) {
            val n = norm(t)
            if (n.length < 2) continue
            if (n.length > bestLen && padded.contains(" $n ")) {
                best = t
                bestLen = n.length
            }
        }
        if (best != null) return best

        val words = norm(text).split(" ").filter { it.length > 2 }.toSet()
        var overlapBest: String? = null
        var score = 0.6
        for (t in screenTexts) {
            val tw = norm(t).split(" ").filter { it.length > 2 }
            if (tw.isEmpty()) continue
            val sc = tw.count { it in words }.toDouble() / tw.size
            if (sc > score) {
                score = sc
                overlapBest = t
            }
        }
        if (overlapBest != null) return overlapBest

        val m = Regex("(?:click|tap|press|hit|select|choose|touch|push)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+(?:button|icon|option|tab|link))?$")
            .find(text.trim().lowercase().trimEnd('.', '!', '?'))
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

class AiIntent(val id: String, val examples: List<String>, val build: (String, AiContext) -> List<PlanStep>?)

object AiIntents {

    /** Bump when the example sentences change so cached embeddings are rebuilt. */
    const val VERSION = 1

    fun describe(c: Command): String = when (c) {
        is Command.OpenApp -> "Open ${c.name}"
        is Command.OpenUri -> "Open link"
        is Command.ClickText -> "Click “${c.text}”"
        is Command.TypeText -> "Type “${c.text}”"
        is Command.Toggle -> "${SystemToggles.spec(c.name)?.title ?: c.name} " +
            when (c.state) { true -> "on"; false -> "off"; null -> "toggle" }
        is Command.Volume -> "Volume ${c.op}" + if (c.op == "pct") " ${c.pct}%" else ""
        is Command.Brightness -> "Brightness ${c.op}" + if (c.op == "pct") " ${c.pct}%" else ""
        is Command.Ringer -> "${c.mode.replaceFirstChar { it.uppercase() }} mode"
        Command.Back -> "Back"
        Command.Home -> "Home"
        Command.Recents -> "Recent apps"
        Command.Notifications -> "Notifications"
        Command.QuickSettings -> "Quick settings"
        Command.ScrollDown -> "Scroll down"
        Command.ScrollUp -> "Scroll up"
        Command.ScrollLeft -> "Scroll left"
        Command.ScrollRight -> "Scroll right"
        Command.ScrollTop -> "Scroll to top"
        Command.ScrollBottom -> "Scroll to bottom"
        Command.Screenshot -> "Screenshot"
        Command.Enter -> "Enter"
        else -> c::class.java.simpleName
    }

    private fun step(c: Command): List<PlanStep> = listOf(PlanStep.Builtin(c, describe(c)))
    private fun simple(id: String, c: Command, vararg ex: String) = AiIntent(id, ex.toList()) { _, _ -> step(c) }

    // ---- small language helpers ----
    private fun padded(t: String) = " " + t.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
    private fun has(t: String, vararg words: String) = words.any { t.contains(" $it ") }

    private val upCues = listOf(
        "too quiet", "too dark", "too dim", "too low", "can't hear", "cannot hear", "cant hear", "turn it up", "all the way up",
        "up", "louder", "higher", "increase", "raise", "boost", "more", "brighter", "bigger", "lighter"
    )
    private val downCues = listOf(
        "too loud", "too bright", "turn it down", "quiet down", "down", "quieter", "lower", "decrease", "reduce",
        "softer", "less", "dimmer", "dim", "darker", "smaller"
    )

    /** True = up, false = down, null = no cue. Multi-word cues are checked first. */
    private fun direction(t: String): Boolean? {
        val p = padded(t)
        val all = (upCues.map { it to true } + downCues.map { it to false }).sortedByDescending { it.first.length }
        for ((cue, dir) in all) if (p.contains(" $cue ")) return dir
        return null
    }

    private fun number(t: String): Int? {
        Regex("(\\d{1,3})").find(t)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
        val skip = setOf("to", "too", "for", "won", "ate", "and")
        val toks = t.lowercase().replace(Regex("[^a-z ]"), " ").split(" ").filter { it.isNotEmpty() }
        for (i in toks.indices) {
            if (toks[i] in skip) continue
            for (len in 3 downTo 1) {
                if (i + len > toks.size) continue
                val v = CommandParser.parseNumber(toks.subList(i, i + len).joinToString(" "))
                if (v != null && v in 0..100) return v
            }
        }
        return null
    }

    private fun stateOf(t: String): Boolean? {
        val p = padded(t)
        if (has(p, "off", "disable", "deactivate", "kill", "stop", "shut")) return false
        if (has(p, "on", "enable", "activate", "start", "need", "open")) return true
        return null
    }

    private fun streamOf(t: String): String {
        val p = padded(t)
        return when {
            has(p, "ring", "ringer", "ringtone") -> "ring"
            has(p, "alarm") -> "alarm"
            has(p, "notification", "notifications") -> "notification"
            has(p, "call") -> "call"
            else -> "media"
        }
    }

    // ---- builders ----
    private fun scrollSteps(t: String): List<PlanStep> {
        val p = padded(t)
        val c = when {
            has(p, "top", "beginning", "very start") || p.contains(" all the way up ") -> Command.ScrollTop
            has(p, "bottom", "end") || p.contains(" all the way down ") -> Command.ScrollBottom
            has(p, "left") -> Command.ScrollLeft
            has(p, "right") -> Command.ScrollRight
            has(p, "up", "above", "previous") -> Command.ScrollUp
            else -> Command.ScrollDown
        }
        return step(c)
    }

    private fun volumeSteps(t: String): List<PlanStep> {
        val p = padded(t)
        val stream = streamOf(t)
        val c: Command = when {
            has(p, "unmute") || p.contains(" sound back ") -> Command.Unmute
            has(p, "mute", "silence") -> Command.Mute
            has(p, "max", "maximum", "full", "highest", "loudest") || p.contains(" as loud as ") -> Command.Volume(stream, "max")
            has(p, "minimum", "lowest", "min", "quietest") -> Command.Volume(stream, "min")
            number(t) != null -> Command.Volume(stream, "pct", number(t)!!)
            else -> Command.Volume(stream, if (direction(t) == false) "down" else "up")
        }
        return step(c)
    }

    private fun brightnessSteps(t: String): List<PlanStep> {
        val p = padded(t)
        val c: Command = when {
            has(p, "auto", "automatic", "adaptive") -> Command.Brightness("auto")
            has(p, "max", "maximum", "full", "highest", "brightest") -> Command.Brightness("max")
            has(p, "minimum", "lowest", "min", "darkest") -> Command.Brightness("min")
            number(t) != null -> Command.Brightness("pct", number(t)!!)
            else -> Command.Brightness(if (direction(t) == false) "down" else "up")
        }
        return step(c)
    }

    private fun switchSteps(t: String): List<PlanStep>? {
        val key = SystemToggles.find(t)
        val state = stateOf(t)
        if (key != null) return step(Command.Toggle(key, state))
        val m = Regex("(?:turn|switch|enable|disable|activate|deactivate|toggle|flip|start|stop)\\s+(?:on\\s+|off\\s+)?(?:the\\s+)?(.+?)(?:\\s+(?:on|off))?$")
            .find(t.trim().lowercase().trimEnd('.', '!', '?')) ?: return null
        val name = m.groupValues[1].trim()
        return if (name.isEmpty()) null else step(Command.Toggle(SystemToggles.canon(name), state))
    }

    private fun ringerSteps(t: String): List<PlanStep> {
        val p = padded(t)
        return step(
            Command.Ringer(
                when {
                    p.contains("vibrat") -> "vibrate"
                    has(p, "silent", "silence", "mute") -> "silent"
                    else -> "normal"
                }
            )
        )
    }

    private fun typeSteps(t: String): List<PlanStep>? {
        val m = Regex("(?:type|write|enter|input|dictate|say)\\s+(?:the\\s+)?(?:text\\s+|message\\s+|following\\s+)?(?:that says\\s+|saying\\s+)?(.+)", RegexOption.IGNORE_CASE)
            .find(t.trim()) ?: return null
        val rem = m.groupValues[1].trim().trimEnd('.', '!', '?')
        return if (rem.isEmpty()) null else step(Command.TypeText(rem))
    }

    private fun searchSteps(t: String, ctx: AiContext): List<PlanStep>? {
        val m = Regex("(?:search(?:\\s+the\\s+web)?(?:\\s+for)?|look\\s+up|look\\s+for|google|find\\s+out|find)\\s+(.+)", RegexOption.IGNORE_CASE)
            .find(t.trim()) ?: return null
        val rem = m.groupValues[1].trim().trimEnd('.', '!', '?')
        if (rem.isEmpty()) return null
        val inApp = Regex("^(.+?)\\s+(?:on|in|using|with)\\s+(.+)$", RegexOption.IGNORE_CASE).find(rem)
        if (inApp != null) {
            val app = ctx.findApp(inApp.groupValues[2])
            if (app != null) {
                val q = inApp.groupValues[1].trim()
                return listOf(
                    PlanStep.Builtin(Command.OpenApp(app), "Open $app"),
                    PlanStep.Pause(1600),
                    PlanStep.Builtin(Command.ClickText("Search"), "Open search", 700),
                    PlanStep.Builtin(Command.TypeText(q), "Type “$q”", 500),
                    PlanStep.Builtin(Command.Enter, "Enter")
                )
            }
        }
        return listOf(
            PlanStep.Builtin(Command.OpenUri("https://www.google.com/search?q=" + Uri.encode(rem)), "Search “$rem”")
        )
    }

    val all: List<AiIntent> by lazy {
        listOf(
            simple("back", Command.Back, "go back", "return to the previous screen", "take me back", "previous page", "navigate back"),
            simple("home", Command.Home, "go to the home screen", "take me home", "leave this app", "show my home screen", "exit to the launcher"),
            simple("recents", Command.Recents, "show recent apps", "switch between apps", "what apps are open", "open the app switcher"),
            simple("notifications", Command.Notifications, "show my notifications", "open the notification panel", "do I have any messages", "pull down the notifications", "what's new"),
            simple("quick_settings", Command.QuickSettings, "open quick settings", "show the quick settings panel", "pull down the settings tiles", "open the control panel"),
            simple("screenshot", Command.Screenshot, "take a screenshot", "capture the screen", "save a picture of this screen", "screen grab"),
            simple("lock", Command.LockScreen, "lock the phone", "lock the screen", "turn off the screen", "put the phone to sleep"),
            simple("power_menu", Command.PowerMenu, "show the power menu", "I want to shut down", "restart the phone", "power off options"),
            simple("split_screen", Command.SplitScreen, "split the screen", "use split screen mode", "show two apps at once"),
            AiIntent("scroll", listOf(
                "scroll down", "scroll up the page", "move the page down", "show me more of this page", "go to the top of the page",
                "jump to the bottom", "scroll to the left", "scroll right", "keep going down", "see what is below"
            )) { t, _ -> scrollSteps(t) },
            simple("refresh", Command.Refresh, "refresh the page", "reload this", "pull to refresh", "update the feed"),
            simple("numbers", Command.ShowNumbers, "show numbers", "label the buttons with numbers", "number the clickable items", "I want to pick things by number"),
            simple("cancel", Command.HideAll, "hide the numbers", "clear the overlays", "remove the labels", "cancel that"),
            simple("grid", Command.ShowGrid, "show the grid", "I need to tap somewhere precise", "overlay a grid", "let me tap anywhere on the screen"),
            simple("select_all", Command.SelectAll, "select all the text", "highlight everything"),
            simple("copy", Command.Copy, "copy that", "copy the selected text"),
            simple("paste", Command.Paste, "paste it here", "insert what I copied"),
            simple("cut", Command.Cut, "cut the selection", "cut that text"),
            simple("delete", Command.Delete, "delete that", "remove the last character", "backspace"),
            simple("clear_text", Command.ClearText, "clear the text box", "erase everything in this field", "empty the field"),
            simple("enter", Command.Enter, "press enter", "submit it", "confirm the text"),
            simple("new_line", Command.NewLine, "add a new line", "go to the next line"),
            AiIntent("volume", listOf(
                "turn the volume up", "make it louder", "it's too quiet", "I can't hear it", "turn the volume down", "make it quieter",
                "it's too loud", "mute the sound", "silence it", "unmute the sound", "set the volume to fifty percent",
                "maximum volume", "lowest volume", "volume at 30"
            )) { t, _ -> volumeSteps(t) },
            AiIntent("brightness", listOf(
                "make the screen brighter", "increase the brightness", "it's too dark", "I can't see the screen", "dim the screen",
                "reduce the brightness", "it's too bright", "set brightness to 50 percent", "full brightness", "lowest brightness",
                "turn on auto brightness"
            )) { t, _ -> brightnessSteps(t) },
            AiIntent("switch", listOf(
                "turn on wifi", "turn off wifi", "enable bluetooth", "disable bluetooth", "switch on the flashlight", "switch off the torch",
                "activate airplane mode", "deactivate airplane mode", "start the hotspot", "stop the hotspot", "toggle mobile data",
                "turn on dark mode", "turn off location", "enable do not disturb", "switch on battery saver", "flip the auto rotate switch"
            )) { t, _ -> switchSteps(t) },
            AiIntent("ringer", listOf(
                "put the phone on silent", "silent mode", "vibrate only", "set vibrate mode", "ring mode", "turn the ringer back on"
            )) { t, _ -> ringerSteps(t) },
            AiIntent("open_app", listOf(
                "open chrome", "launch the camera", "start youtube", "go to the settings app", "I want to use the calculator",
                "run maps", "switch to gallery", "open my messages app"
            )) { t, ctx -> ctx.findApp(t)?.let { step(Command.OpenApp(it)) } },
            AiIntent("click", listOf(
                "press the ok button", "tap on settings", "select that option", "click on login", "hit send",
                "choose the second tab", "push the start button"
            )) { t, ctx -> ctx.findScreenTarget(t)?.let { step(Command.ClickText(it)) } },
            AiIntent("type", listOf(
                "type hello world", "write a message saying good morning", "enter my name", "input this text", "write that I will be late"
            )) { t, _ -> typeSteps(t) },
            AiIntent("search", listOf(
                "search for cats", "look up the weather", "google pizza near me", "find out who won the game",
                "search the web for news", "search for funny videos on youtube"
            )) { t, ctx -> searchSteps(t, ctx) },
            simple("dictation_on", Command.DictationOn, "start dictation", "let me dictate", "I want to speak the text", "transcribe what I say"),
            simple("dictation_off", Command.DictationOff, "stop dictation", "end dictation", "stop typing what I say"),
            simple("click_mode_on", Command.ClickModeOn, "turn on click mode", "let me click by saying the names", "start clicking by name"),
            simple("click_mode_off", Command.ClickModeOff, "turn off click mode", "stop clicking by name", "exit click mode"),
            simple("pause", Command.Sleep, "pause listening", "stop listening for a while", "go to sleep voice control"),
            simple("stop_listening", Command.StopListening, "stop listening", "turn off voice control", "quit the voice assistant"),
            simple("stop_macro", Command.StopMacro, "stop that command", "cancel the running command", "abort what you are doing"),
            simple("show_commands", Command.ShowCommands, "what can I say", "show me the commands", "help me", "list the commands")
        )
    }
}
