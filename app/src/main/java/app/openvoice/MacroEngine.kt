package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Runs the actions of a custom command one after another, in Normal, Root or Shizuku mode. */
class MacroEngine(private val svc: OpenVoiceService) {

    private val h = Handler(Looper.getMainLooper())
    private var token = 0

    private class Step(val a: Action, val mode: Mode, val cmd: CustomCommand)
    private class Out(val waitMs: Long, val error: String? = null)

    fun cancel() {
        token++
        h.removeCallbacksAndMessages(null)
    }

    fun run(cmd: CustomCommand, captured: String?, onDone: (String) -> Unit) {
        cancel()
        val my = token
        val steps = flatten(cmd, 0)
        if (steps.isEmpty()) {
            onDone("nothing to do")
            return
        }
        val modes = steps.map { it.mode }.toSet().toList()
        checkModes(modes, 0) { err ->
            if (my == token) {
                if (err != null) onDone("⚠ $err") else go(steps, 0, captured, my, onDone)
            }
        }
    }

    private fun checkModes(modes: List<Mode>, i: Int, cb: (String?) -> Unit) {
        if (i >= modes.size) {
            cb(null)
            return
        }
        Shell.check(modes[i]) { ok, why ->
            if (!ok) cb(why) else checkModes(modes, i + 1, cb)
        }
    }

    /** Expands "run another command" steps and repeats. */
    private fun flatten(cmd: CustomCommand, depth: Int): List<Step> {
        if (depth > 5) return emptyList()
        val once = ArrayList<Step>()
        for (a in cmd.actions) {
            if (a.type == "call") {
                val target = Store.findCommandByName(svc, a.p("command"))
                if (target != null) once.addAll(flatten(target, depth + 1)) else once.add(Step(a, cmd.mode, cmd))
            } else {
                once.add(Step(a, cmd.mode, cmd))
            }
        }
        val all = ArrayList<Step>()
        repeat(cmd.repeat.coerceIn(1, 99)) { if (all.size < 2000) all.addAll(once) }
        return all
    }

    private fun go(steps: List<Step>, i: Int, cap: String?, my: Int, onDone: (String) -> Unit) {
        if (my != token) return
        if (i >= steps.size) {
            onDone("done ✓")
            return
        }
        val s = steps[i]
        val out = try {
            perform(s, cap)
        } catch (e: Exception) {
            Out(0, e.message ?: "error")
        }
        if (out.error != null && !s.cmd.continueOnError) {
            onDone("⚠ ${out.error}")
            return
        }
        h.postDelayed({ go(steps, i + 1, cap, my, onDone) }, out.waitMs.coerceAtLeast(120))
    }

    // ------------------------------------------------------------------

    private fun fill(s: String, cap: String?, encode: Boolean = false): String {
        if (!s.contains("{text}")) return s
        val c = cap ?: ""
        return s.replace("{text}", if (encode) Uri.encode(c) else c)
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun isFail(r: String) =
        r == "failed" || r.contains("not found") || r.startsWith("no ") ||
            r.startsWith("needs") || r.startsWith("say ") || r.startsWith("didn't")

    private fun perform(s: Step, cap: String?): Out {
        val a = s.a
        val m = s.mode
        val normal = m == Mode.NORMAL
        val (w, hh) = Screen.size(svc)
        fun px(k: String) = (a.num(k, 50.0) / 100.0 * w).toFloat()
        fun py(k: String) = (a.num(k, 50.0) / 100.0 * hh).toFloat()

        return when (a.type) {
            "wait" -> Out((a.num("seconds", 1.0) * 1000).toLong())

            "tap" -> {
                tap(m, px("x"), py("y"))
                Out(350)
            }
            "double_tap" -> {
                val x = px("x")
                val y = py("y")
                svc.fxDouble(x, y)
                if (normal) svc.doubleTapRaw(x, y)
                else Shell.exec(m, "input tap ${x.toInt()} ${y.toInt()} ; sleep 0.1 ; input tap ${x.toInt()} ${y.toInt()}")
                Out(500)
            }
            "long_press" -> {
                val x = px("x")
                val y = py("y")
                val ms = a.num("ms", 800.0).toLong().coerceAtLeast(100)
                svc.fxLong(x, y)
                if (normal) svc.longPressRaw(x, y, ms)
                else Shell.exec(m, "input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $ms")
                Out(ms + 250)
            }
            "swipe" -> {
                val ms = a.num("ms", 300.0).toLong().coerceAtLeast(50)
                swipe(m, px("x1"), py("y1"), px("x2"), py("y2"), ms)
                Out(ms + 250)
            }
            "gesture" -> {
                val g = Store.findGesture(svc, a.p("gesture")) ?: return Out(0, "gesture not found")
                if (normal) {
                    if (!svc.playGesture(g)) return Out(0, "gesture failed")
                } else {
                    playGestureShell(m, g)
                }
                Out(g.totalMs + 250)
            }

            "click_text" -> {
                val r = svc.exec(Command.ClickText(fill(a.p("text"), cap)))
                if (isFail(r)) Out(0, r) else Out(500)
            }
            "open_app" -> {
                val name = fill(a.p("name"), cap)
                if (normal) {
                    val r = svc.exec(Command.OpenApp(name))
                    if (isFail(r)) Out(0, r) else Out(1200)
                } else {
                    val pkg = svc.findPackage(name) ?: return Out(0, "app “$name” not found")
                    Shell.exec(m, "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    Out(1200)
                }
            }
            "open_uri" -> {
                val uri = fill(a.p("uri"), cap, encode = true)
                if (normal) {
                    try {
                        svc.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        return Out(0, "can't open link")
                    }
                } else {
                    Shell.exec(m, "am start -a android.intent.action.VIEW -d ${q(uri)}")
                }
                Out(1200)
            }
            "key" -> {
                val err = pressKey(m, a.p("key", "back"))
                if (err != null) Out(0, err) else Out(500)
            }
            "type" -> {
                val text = fill(a.p("text"), cap)
                if (normal) {
                    val r = svc.exec(Command.TypeText(text))
                    if (isFail(r)) Out(0, r) else Out(400)
                } else {
                    Shell.exec(m, "input text ${q(text.replace(" ", "%s"))}")
                    Out(500)
                }
            }
            "scroll" -> {
                scroll(m, a.p("direction", "down"))
                Out(if (a.p("direction") == "top" || a.p("direction") == "bottom") 3500 else 600)
            }
            "voice" -> {
                val phrase = fill(a.p("phrase"), cap)
                val c = CommandParser.parse(phrase)
                if (c == Command.Unknown) Out(0, "unknown command “$phrase”")
                else {
                    val r = svc.exec(c)
                    if (isFail(r)) Out(0, r) else Out(600)
                }
            }
            "shell" -> {
                if (normal) Out(0, "Shell needs Root or Shizuku mode")
                else {
                    Shell.exec(m, fill(a.p("command"), cap))
                    Out(500)
                }
            }
            "toast" -> {
                svc.setStatus(fill(a.p("message"), cap), 2500)
                Out(300)
            }
            "call" -> Out(0, "command “${a.p("command")}” not found")
            else -> Out(0, "unknown action “${a.type}”")
        }
    }

    // ------------------------------------------------------------------

    private fun tap(m: Mode, x: Float, y: Float) {
        svc.fxTap(x, y)
        if (m == Mode.NORMAL) svc.tapRaw(x, y)
        else Shell.exec(m, "input tap ${x.toInt()} ${y.toInt()}")
    }

    private fun swipe(m: Mode, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (m == Mode.NORMAL) {
            svc.swipe(x1, y1, x2, y2, ms)
        } else {
            svc.fxSwipe(x1, y1, x2, y2)
            Shell.exec(m, "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $ms")
        }
    }

    private fun scroll(m: Mode, dir: String) {
        if (m == Mode.NORMAL) {
            svc.exec(
                when (dir) {
                    "up" -> Command.ScrollUp
                    "left" -> Command.ScrollLeft
                    "right" -> Command.ScrollRight
                    "top" -> Command.ScrollTop
                    "bottom" -> Command.ScrollBottom
                    else -> Command.ScrollDown
                }
            )
            return
        }
        val (w, hh) = Screen.size(svc)
        val cx = w / 2
        val cy = hh / 2
        // finger direction: scrolling down = finger moves up
        val v = when (dir) {
            "down", "bottom" -> listOf(cx, (hh * 0.7).toInt(), cx, (hh * 0.3).toInt())
            "up", "top" -> listOf(cx, (hh * 0.3).toInt(), cx, (hh * 0.7).toInt())
            "right" -> listOf((w * 0.8).toInt(), cy, (w * 0.2).toInt(), cy)
            else -> listOf((w * 0.2).toInt(), cy, (w * 0.8).toInt(), cy)
        }
        svc.fxSwipe(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
        val one = "input swipe ${v[0]} ${v[1]} ${v[2]} ${v[3]} 250"
        if (dir == "top" || dir == "bottom") {
            Shell.exec(m, "for i in 1 2 3 4 5 6 7 8; do $one; sleep 0.15; done")
        } else {
            Shell.exec(m, one)
        }
    }

    private val keycodes = mapOf(
        "back" to 4, "home" to 3, "recents" to 187, "screenshot" to 120, "lock_screen" to 223,
        "volume_up" to 24, "volume_down" to 25, "mute" to 164, "enter" to 66, "delete" to 67,
        "tab" to 61, "escape" to 111, "menu" to 82, "play_pause" to 85, "next_track" to 87,
        "previous_track" to 88, "power" to 26, "camera" to 27, "search" to 84
    )

    /** Returns an error message, or null on success. */
    private fun pressKey(m: Mode, key: String): String? {
        if (m != Mode.NORMAL) {
            val cmd = when (key) {
                "notifications" -> "cmd statusbar expand-notifications"
                "quick_settings" -> "cmd statusbar expand-settings"
                "power_menu" -> "input keyevent --longpress 26"
                else -> keycodes[key]?.let { "input keyevent $it" } ?: return "unknown key “$key”"
            }
            Shell.exec(m, cmd)
            return null
        }
        return when (key) {
            "back" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            "home" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            "recents" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
            "notifications" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            "quick_settings" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
            "power_menu" -> done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG))
            "screenshot" ->
                if (Build.VERSION.SDK_INT >= 28) done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT))
                else "needs Android 9+"
            "lock_screen" ->
                if (Build.VERSION.SDK_INT >= 28) done(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
                else "needs Android 9+"
            "volume_up" -> fail(svc.exec(Command.VolumeUp))
            "volume_down" -> fail(svc.exec(Command.VolumeDown))
            "mute" -> fail(svc.exec(Command.Mute))
            "enter" -> fail(svc.exec(Command.Enter))
            "delete" -> fail(svc.exec(Command.Delete))
            "play_pause" -> done(svc.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            "next_track" -> done(svc.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT))
            "previous_track" -> done(svc.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            else -> "“$key” needs Root or Shizuku mode"
        }
    }

    private fun done(ok: Boolean): String? = if (ok) null else "failed"
    private fun fail(r: String): String? = if (isFail(r)) r else null

    /** Root/Shizuku replay: taps and straight swipes only (no multi-finger). */
    private fun playGestureShell(m: Mode, g: Gesture) {
        val (w, hh) = Screen.size(svc)
        for (s in g.strokes.sortedBy { it.startMs }.take(20)) {
            if (s.points.isEmpty()) continue
            val f = s.points.first()
            val l = s.points.last()
            val x1 = (f.x * w).toInt()
            val y1 = (f.y * hh).toInt()
            val x2 = (l.x * w).toInt()
            val y2 = (l.y * hh).toInt()
            val moved = kotlin.math.abs(x2 - x1) > 12 || kotlin.math.abs(y2 - y1) > 12
            val dur = s.durMs.coerceAtLeast(50)
            h.postDelayed({
                when {
                    moved -> {
                        svc.fxSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                        Shell.exec(m, "input swipe $x1 $y1 $x2 $y2 $dur")
                    }
                    dur > 400 -> {
                        svc.fxLong(x1.toFloat(), y1.toFloat())
                        Shell.exec(m, "input swipe $x1 $y1 $x1 $y1 $dur")
                    }
                    else -> {
                        svc.fxTap(x1.toFloat(), y1.toFloat())
                        Shell.exec(m, "input tap $x1 $y1")
                    }
                }
            }, s.startMs)
        }
    }
}
