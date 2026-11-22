package app.openvoice

import android.content.Context

/** The built-in voice commands, grouped so each group can be switched on or off in the Commands tab. */
object BuiltIns {

    class Group(val id: String, val title: String, val category: String, val say: String)

    const val NAV = "Navigation"
    const val SCROLL = "Scrolling & swiping"
    const val TARGET = "See & tap"
    const val TEXT = "Text"
    const val APPS = "Apps"
    const val SOUND = "Volume & brightness"
    const val SYSTEM = "System toggles"
    const val CONTROL = "Control"

    val groups = listOf(
        Group("back", "Back", NAV, "go back · back"),
        Group("home", "Home", NAV, "go home · home"),
        Group("recents", "Recent apps", NAV, "recents · overview"),
        Group("notifications", "Notifications", NAV, "notifications · show notifications"),
        Group("quick_settings", "Quick settings", NAV, "quick settings"),
        Group("power_menu", "Power menu", NAV, "power menu"),
        Group("screenshot", "Screenshot", NAV, "screenshot · take a screenshot"),
        Group("lock_screen", "Lock screen", NAV, "lock screen · lock phone"),
        Group("split_screen", "Split screen", NAV, "split screen"),

        Group("scroll_vert", "Scroll up / down", SCROLL, "scroll down · scroll up · page down"),
        Group("scroll_horiz", "Scroll left / right", SCROLL, "scroll left · scroll right"),
        Group("scroll_edge", "Scroll to top / bottom", SCROLL, "scroll to top · scroll to bottom"),
        Group("swipe", "Swipe", SCROLL, "swipe left · right · up · down"),
        Group("refresh", "Pull to refresh", SCROLL, "refresh"),

        Group("numbers", "Numbers overlay", TARGET, "show numbers · hide numbers"),
        Group("tap_number", "Tap by number", TARGET, "tap 5 · double tap 5 · long press 5"),
        Group("click_text", "Click by text", TARGET, "click <text on screen>"),
        Group("grid", "Grid", TARGET, "show grid · 1-135 · set grid big / medium / small · undo"),
        Group("tap_here", "Tap in grid", TARGET, "tap · double tap · hold"),
        Group("cancel", "Cancel overlays", TARGET, "cancel · hide all"),
        Group("click_mode", "Click mode", TARGET, "click mode on · stop clicking — then just say the text to click"),

        Group("type", "Type text", TEXT, "type <text> · write <text>"),
        Group("dictation", "Dictation", TEXT, "start dictation · stop dictation"),
        Group("edit", "Select / copy / paste / cut", TEXT, "select all · copy · paste · cut"),
        Group("delete", "Delete", TEXT, "delete · delete word · clear text"),
        Group("newline_enter", "New line / Enter", TEXT, "new line · enter"),

        Group("open_app", "Open app", APPS, "open <app> · launch <app> · go to <app>"),

        Group("volume_step", "Volume up / down", SOUND, "volume up · volume down · louder · quieter"),
        Group("volume_set", "Set volume", SOUND, "volume full · volume low · volume 40 percent · ring volume down"),
        Group("mute", "Mute / unmute", SOUND, "mute · unmute"),
        Group("brightness", "Brightness", SOUND, "brightness up / down · full · low · 60 percent · auto"),

        Group("toggles", "On / off switches", SYSTEM,
            "turn on / off Wi‑Fi, Bluetooth, hotspot, airplane mode, location, mobile data, NFC, do not disturb, " +
                "flashlight, auto-rotate, battery saver, dark mode, night light, cast, screen record… " +
                "or any Quick Settings tile name · toggle <name>"),
        Group("ringer", "Sound mode", SYSTEM, "silent mode · vibrate mode · ring mode"),

        Group("pause_resume", "Pause / resume", CONTROL, "pause listening · resume listening"),
        Group("stop_listening", "Stop listening", CONTROL, "stop listening"),
        Group("stop_macro", "Stop running command", CONTROL, "stop macro · stop running"),
        Group("run_custom", "Run custom command", CONTROL, "run <command name>"),
        Group("show_commands", "Show commands", CONTROL, "show commands")
    )

    val categories: List<String> = groups.map { it.category }.distinct()

    fun isEnabled(ctx: Context, id: String): Boolean = id.isEmpty() || id !in Prefs.disabledBuiltIns(ctx)
}

fun Command.groupId(): String = when (this) {
    Command.Back -> "back"
    Command.Home -> "home"
    Command.Recents -> "recents"
    Command.Notifications -> "notifications"
    Command.QuickSettings -> "quick_settings"
    Command.Screenshot -> "screenshot"
    Command.LockScreen -> "lock_screen"
    Command.PowerMenu -> "power_menu"
    Command.SplitScreen -> "split_screen"
    Command.ScrollDown, Command.ScrollUp -> "scroll_vert"
    Command.ScrollLeft, Command.ScrollRight -> "scroll_horiz"
    Command.ScrollTop, Command.ScrollBottom -> "scroll_edge"
    Command.SwipeLeft, Command.SwipeRight, Command.SwipeUp, Command.SwipeDown -> "swipe"
    Command.Refresh -> "refresh"
    Command.ShowNumbers, Command.HideNumbers -> "numbers"
    Command.ShowGrid, Command.HideGrid, is Command.SetGrid, is Command.GridPick -> "grid"
    Command.HideAll -> "cancel"
    Command.TapHere, Command.DoubleTapHere, Command.LongPressHere -> "tap_here"
    is Command.Tap, is Command.DoubleTap, is Command.LongPress -> "tap_number"
    is Command.ClickText -> "click_text"
    is Command.TypeText -> "type"
    Command.ClearText, Command.Delete, Command.DeleteWord -> "delete"
    Command.SelectAll, Command.Copy, Command.Paste, Command.Cut -> "edit"
    Command.NewLine, Command.Enter -> "newline_enter"
    Command.DictationOn, Command.DictationOff -> "dictation"
    is Command.OpenApp, is Command.OpenUri -> "open_app"
    Command.VolumeUp, Command.VolumeDown -> "volume_step"
    Command.Mute, Command.Unmute -> "mute"
    is Command.Volume -> "volume_set"
    is Command.Brightness -> "brightness"
    is Command.Toggle -> "toggles"
    is Command.Ringer -> "ringer"
    Command.Sleep, Command.Wake -> "pause_resume"
    Command.ShowCommands -> "show_commands"
    Command.StopListening -> "stop_listening"
    Command.ClickModeOn, Command.ClickModeOff -> "click_mode"
    Command.StopMacro -> "stop_macro"
    is Command.RunCustom -> "run_custom"
    Command.Unknown -> ""
}
