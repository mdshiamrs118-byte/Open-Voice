package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.floor

/**
 * Runs a custom command like a tiny program: normal actions, plus if / else if / else, repeat,
 * while, break, continue, try / catch, wait until, variables and calling other commands.
 * Every text field of every action goes through {placeholder} substitution first.
 */
class MacroEngine(private val svc: OpenVoiceService) {

    companion object {
        private const val MAX_STEPS = 50000
        private const val MAX_LOOP_ROUNDS = 5000

        /** Returns a problem description if if/loop/try blocks aren't balanced, otherwise null. */
        fun validate(actions: List<Action>): String? = Prog(actions).error
    }

    private val h = Handler(Looper.getMainLooper())
    private var token = 0

    private class Out(val waitMs: Long = 0, val error: String? = null, val advance: Boolean = true)
    private class LoopState(val start: Int, var remaining: Int) {
        var rounds = 0
    }

    /** Pre-computed jump targets for the blocks of one action list. */
    private class Prog(val a: List<Action>) {
        val n = a.size
        val branchNext = IntArray(n) { -1 }
        val chainEnd = IntArray(n) { -1 }
        val loopEnd = IntArray(n) { -1 }
        val catchAt = IntArray(n) { -1 }
        val tryEnd = IntArray(n) { -1 }
        val tryStart = IntArray(n) { -1 }
        var error: String? = null

        private class Blk(val kind: Char, val start: Int) {
            val members = ArrayList<Int>()
        }

        init {
            scan()
        }

        private fun scan() {
            val st = ArrayList<Blk>()
            for (i in 0 until n) {
                when (a[i].type) {
                    "if" -> st.add(Blk('i', i).also { it.members.add(i) })
                    "else_if", "else" -> {
                        val b = st.lastOrNull()
                        if (b == null || b.kind != 'i') {
                            error = "“${a[i].type}” without “if” (action ${i + 1})"
                            return
                        }
                        branchNext[b.members.last()] = i
                        b.members.add(i)
                    }
                    "end_if" -> {
                        val b = st.lastOrNull()
                        if (b == null || b.kind != 'i') {
                            error = "“end if” without “if” (action ${i + 1})"
                            return
                        }
                        st.removeAt(st.size - 1)
                        branchNext[b.members.last()] = i
                        b.members.add(i)
                        for (m in b.members) chainEnd[m] = i
                    }
                    "repeat", "while" -> st.add(Blk('l', i))
                    "end_loop" -> {
                        val b = st.lastOrNull()
                        if (b == null || b.kind != 'l') {
                            error = "“end loop” without a loop (action ${i + 1})"
                            return
                        }
                        st.removeAt(st.size - 1)
                        loopEnd[b.start] = i
                        loopEnd[i] = b.start
                    }
                    "try" -> st.add(Blk('t', i).also { it.members.add(i) })
                    "catch" -> {
                        val b = st.lastOrNull()
                        if (b == null || b.kind != 't') {
                            error = "“catch” without “try” (action ${i + 1})"
                            return
                        }
                        catchAt[b.start] = i
                        b.members.add(i)
                        tryStart[i] = b.start
                    }
                    "end_try" -> {
                        val b = st.lastOrNull()
                        if (b == null || b.kind != 't') {
                            error = "“end try” without “try” (action ${i + 1})"
                            return
                        }
                        st.removeAt(st.size - 1)
                        b.members.add(i)
                        for (m in b.members) tryEnd[m] = i
                        tryStart[i] = b.start
                    }
                }
            }
            if (st.isNotEmpty()) {
                val b = st.last()
                error = "missing end for “${a[b.start].type}” (action ${b.start + 1})"
            }
        }
    }

    private class Frame(val cmd: CustomCommand, val prog: Prog) {
        var pc = 0
        var repeatLeft = cmd.repeat.coerceIn(1, 99)
        val loops = ArrayList<LoopState>()
        val tries = ArrayList<Int>()
    }

    private class Run(val onDone: (String) -> Unit) {
        val vars = HashMap<String, String>()
        val frames = ArrayList<Frame>()
        var steps = 0
        var waitingSince = -1L
    }

    fun cancel() {
        token++
        h.removeCallbacksAndMessages(null)
    }

    fun run(cmd: CustomCommand, captured: String?, onDone: (String) -> Unit) {
        cancel()
        val my = token
        val prog = Prog(cmd.actions)
        prog.error?.let {
            onDone("⚠ $it")
            return
        }
        if (cmd.actions.isEmpty()) {
            onDone("nothing to do")
            return
        }
        val modes = collectModes(cmd, HashSet(), 0).toList()
        checkModes(modes, 0) { err ->
            if (my == token) {
                if (err != null) {
                    onDone("⚠ $err")
                } else {
                    val run = Run(onDone)
                    run.vars["text"] = captured ?: ""
                    run.frames.add(Frame(cmd, prog))
                    step(run, my)
                }
            }
        }
    }

    private fun collectModes(cmd: CustomCommand, seen: MutableSet<String>, depth: Int): Set<Mode> {
        val out = HashSet<Mode>()
        out.add(cmd.mode)
        if (depth > 8 || !seen.add(cmd.id)) return out
        for (a in cmd.actions) {
            if (a.type == "call") {
                Store.findCommandByName(svc, a.p("command"))?.let { out.addAll(collectModes(it, seen, depth + 1)) }
            }
        }
        return out
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

    // ------------------------------------------------------------------
    // Interpreter loop
    // ------------------------------------------------------------------
    private fun later(run: Run, my: Int, ms: Long) {
        h.postDelayed({ step(run, my) }, ms)
    }

    private fun step(run: Run, my: Int) {
        if (my != token) return
        if (run.frames.isEmpty()) {
            run.onDone("done ✓")
            return
        }
        if (++run.steps > MAX_STEPS) {
            run.onDone("⚠ stopped: too many steps (endless loop?)")
            return
        }
        val f = run.frames.last()
        if (f.pc >= f.prog.n) {
            if (f.repeatLeft > 1) {
                f.repeatLeft--
                f.pc = 0
                f.loops.clear()
                f.tries.clear()
                later(run, my, 300)
            } else {
                run.frames.removeAt(run.frames.size - 1)
                later(run, my, 0)
            }
            return
        }
        val out = try {
            exec(run, f)
        } catch (e: Exception) {
            Out(error = e.message ?: "error")
        }
        if (my != token) return
        if (out.error != null) {
            fail(run, f, out.error, my)
            return
        }
        if (out.advance) f.pc++
        later(run, my, out.waitMs)
    }

    private fun fail(run: Run, f: Frame, msg: String, my: Int) {
        for (i in run.frames.indices.reversed()) {
            val fr = run.frames[i]
            if (fr.tries.isNotEmpty()) {
                while (run.frames.size - 1 > i) run.frames.removeAt(run.frames.size - 1)
                val t = fr.tries.removeAt(fr.tries.size - 1)
                run.vars["error"] = msg
                fr.loops.removeAll { it.start > t }
                val c = fr.prog.catchAt[t]
                fr.pc = if (c >= 0) c + 1 else fr.prog.tryEnd[t] + 1
                later(run, my, 0)
                return
            }
        }
        if (f.cmd.continueOnError) {
            f.pc++
            later(run, my, 120)
            return
        }
        run.onDone("⚠ $msg")
    }

    private fun resolve(run: Run, a: Action): Action {
        if (a.params.isEmpty()) return a
        val m = LinkedHashMap<String, String>()
        for ((k, v) in a.params) {
            val auto = (a.type == "open_uri" && k == "uri") || (a.type == "intent" && k == "data")
            m[k] = Vars.resolve(svc, v, run.vars, auto)
        }
        return Action(a.type, m)
    }

    private fun enterBranch(run: Run, f: Frame, idx: Int): Int {
        var i = idx
        while (i >= 0 && i < f.prog.n) {
            if (f.prog.a[i].type == "else_if") {
                val ra = resolve(run, f.prog.a[i])
                if (Conditions.eval(svc, ra)) return i + 1
                i = f.prog.branchNext[i]
            } else {
                return i + 1 // else / end_if
            }
        }
        return f.prog.n
    }

    private fun toNum(s: String): Double? =
        s.trim().toDoubleOrNull() ?: CommandParser.parseNumber(s.trim().lowercase())?.toDouble()

    private fun fmt(d: Double): String =
        if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun exec(run: Run, f: Frame): Out {
        val raw = f.prog.a[f.pc]
        val a = resolve(run, raw)
        val p = f.prog

        when (raw.type) {
            "if" -> {
                f.pc = if (Conditions.eval(svc, a)) f.pc + 1 else enterBranch(run, f, p.branchNext[f.pc])
                return Out(advance = false)
            }
            "else_if", "else" -> {
                f.pc = p.chainEnd[f.pc] + 1
                return Out(advance = false)
            }
            "end_if" -> return Out()

            "repeat" -> {
                val n = a.num("count", 1.0).toInt()
                if (n <= 0) {
                    f.pc = p.loopEnd[f.pc] + 1
                } else {
                    f.loops.add(LoopState(f.pc, n))
                    f.pc++
                }
                return Out(advance = false)
            }
            "while" -> {
                var st = f.loops.lastOrNull()?.takeIf { it.start == f.pc }
                if (st == null) {
                    st = LoopState(f.pc, 0)
                    f.loops.add(st)
                }
                st.rounds++
                if (st.rounds > MAX_LOOP_ROUNDS) return Out(error = "loop stopped after $MAX_LOOP_ROUNDS rounds")
                if (Conditions.eval(svc, a)) {
                    f.pc++
                } else {
                    f.loops.removeAt(f.loops.size - 1)
                    f.pc = p.loopEnd[f.pc] + 1
                }
                return Out(waitMs = 40, advance = false)
            }
            "end_loop" -> {
                val start = p.loopEnd[f.pc]
                val st = f.loops.lastOrNull()
                if (st == null || st.start != start) return Out(error = "loop state lost")
                if (p.a[start].type == "repeat") {
                    st.remaining--
                    if (st.remaining > 0) {
                        f.pc = start + 1
                    } else {
                        f.loops.removeAt(f.loops.size - 1)
                        f.pc++
                    }
                } else {
                    f.pc = start
                }
                return Out(advance = false)
            }
            "break" -> {
                val st = f.loops.lastOrNull() ?: return Out(error = "“break” outside a loop")
                f.loops.removeAt(f.loops.size - 1)
                f.tries.removeAll { it > st.start }
                f.pc = p.loopEnd[st.start] + 1
                return Out(advance = false)
            }
            "continue" -> {
                val st = f.loops.lastOrNull() ?: return Out(error = "“continue” outside a loop")
                f.tries.removeAll { it > st.start }
                if (p.a[st.start].type == "repeat") {
                    st.remaining--
                    if (st.remaining > 0) {
                        f.pc = st.start + 1
                    } else {
                        f.loops.removeAt(f.loops.size - 1)
                        f.pc = p.loopEnd[st.start] + 1
                    }
                } else {
                    f.pc = st.start
                }
                return Out(advance = false)
            }
            "stop" -> {
                if (a.p("message").isNotBlank()) svc.setStatus(a.p("message"))
                if (a.p("scope", "this_command") == "all") run.frames.clear()
                else run.frames.removeAt(run.frames.size - 1)
                return Out(advance = false)
            }
            "try" -> {
                f.tries.add(f.pc)
                f.pc++
                return Out(advance = false)
            }
            "catch" -> {
                if (f.tries.lastOrNull() == p.tryStart[f.pc]) f.tries.removeAt(f.tries.size - 1)
                f.pc = p.tryEnd[f.pc] + 1
                return Out(advance = false)
            }
            "end_try" -> {
                if (f.tries.lastOrNull() == p.tryStart[f.pc]) f.tries.removeAt(f.tries.size - 1)
                return Out()
            }
            "call" -> {
                val target = Store.findCommandByName(svc, a.p("command"))
                    ?: return Out(error = "command “${a.p("command")}” not found")
                if (run.frames.size >= 8) return Out(error = "commands nested too deeply")
                val prog = Prog(target.actions)
                prog.error?.let { return Out(error = "in “${target.name}”: $it") }
                f.pc++
                run.frames.add(Frame(target, prog))
                return Out(advance = false)
            }
            "wait_until" -> {
                if (Conditions.eval(svc, a)) {
                    run.waitingSince = -1L
                    return Out()
                }
                val now = SystemClock.uptimeMillis()
                if (run.waitingSince < 0) run.waitingSince = now
                val timeout = (a.num("timeout", 10.0) * 1000).toLong()
                if (now - run.waitingSince >= timeout) {
                    run.waitingSince = -1L
                    return if (a.p("on_timeout", "continue") == "error") Out(error = "timed out waiting") else Out()
                }
                return Out(waitMs = 300, advance = false)
            }
            "set_var" -> {
                val name = a.p("name").trim()
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return Out(error = "bad variable name “$name”")
                val saved = a.p("scope", "run") == "saved"
                val cur = Vars.lookup(svc, name, run.vars) ?: ""
                val v = a.p("value")
                val op = a.p("op", "set")
                val result = when (op) {
                    "clear" -> ""
                    "append" -> cur + v
                    "add" -> fmt((toNum(cur) ?: 0.0) + (toNum(v) ?: 0.0))
                    "subtract" -> fmt((toNum(cur) ?: 0.0) - (toNum(v) ?: 0.0))
                    "multiply" -> fmt((toNum(cur) ?: 0.0) * (toNum(v) ?: 0.0))
                    else -> v
                }
                if (saved) {
                    Vars.setSaved(svc, name, result)
                    run.vars.remove(name)
                } else {
                    run.vars[name] = result
                }
                return Out()
            }
            "get_text" -> {
                val name = a.p("var", "result").trim()
                run.vars[name] = when (a.p("source", "clipboard")) {
                    "clipboard" -> svc.clipboardText()
                    "focused_field" -> svc.focusedText()
                    "screen" -> svc.screenText()
                    "foreground_app" -> svc.appLabel(svc.foregroundPackage().orEmpty())
                    "battery" -> svc.batteryLevel().toString()
                    "time" -> Vars.lookup(svc, "time", run.vars).orEmpty()
                    else -> ""
                }
                return Out()
            }
        }

        val o = perform(f.cmd.mode, a)
        return Out(waitMs = o.waitMs.coerceAtLeast(120), error = o.error)
    }

    // ------------------------------------------------------------------
    // Ordinary actions (placeholders already resolved in [a])
    // ------------------------------------------------------------------
    private fun isFail(r: String) =
        r == "failed" || r.contains("not found") || r.startsWith("no ") ||
            r.startsWith("needs") || r.startsWith("say ") || r.startsWith("didn't")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun perform(m: Mode, a: Action): Out {
        val normal = m == Mode.NORMAL
        val (w, hh) = Screen.size(svc)
        fun px(k: String) = (a.num(k, 50.0) / 100.0 * w).toFloat()
        fun py(k: String) = (a.num(k, 50.0) / 100.0 * hh).toFloat()

        return when (a.type) {
            "wait" -> Out(waitMs = (a.num("seconds", 1.0) * 1000).toLong())

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
                val g = Store.findGesture(svc, a.p("gesture")) ?: return Out(error = "gesture not found")
                if (normal) {
                    if (!svc.playGesture(g)) return Out(error = "gesture failed")
                } else {
                    playGestureShell(m, g)
                }
                Out(g.totalMs + 250)
            }
            "scroll" -> {
                val d = a.p("direction", "down")
                scroll(m, d)
                Out(if (d == "top" || d == "bottom") 3500 else 600)
            }
            "click_text" -> {
                val r = svc.exec(Command.ClickText(a.p("text")))
                if (isFail(r)) Out(error = r) else Out(500)
            }

            "open_app" -> {
                val name = a.p("name")
                if (normal) {
                    val r = svc.exec(Command.OpenApp(name))
                    if (isFail(r)) Out(error = r) else Out(1200)
                } else {
                    val pkg = svc.findPackage(name) ?: return Out(error = "app “$name” not found")
                    Shell.exec(m, "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    Out(1200)
                }
            }
            "open_uri" -> {
                val uri = a.p("uri")
                if (normal) {
                    try {
                        svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        return Out(error = "can't open link")
                    }
                } else {
                    Shell.exec(m, "am start -a android.intent.action.VIEW -d ${q(uri)}")
                }
                Out(1200)
            }
            "app_shortcut" -> {
                val err = AppShortcuts.run(svc, m, a.p("app"), a.p("shortcut"))
                if (err != null) Out(error = err) else Out(1200)
            }
            "intent" -> {
                val err = IntentRunner.launch(svc, m, IntentRunner.specFrom(a))
                if (err != null) Out(error = err) else Out(1000)
            }
            "shell" -> {
                if (normal) {
                    Out(error = "Shell needs Root or Shizuku mode")
                } else {
                    Shell.exec(m, a.p("command"))
                    Out(500)
                }
            }

            "key" -> {
                val err = pressKey(m, a.p("key", "back"))
                if (err != null) Out(error = err) else Out(500)
            }
            "type" -> {
                val text = a.p("text")
                if (normal) {
                    val r = svc.exec(Command.TypeText(text))
                    if (isFail(r)) Out(error = r) else Out(400)
                } else {
                    Shell.exec(m, "input text ${q(text.replace(" ", "%s"))}")
                    Out(500)
                }
            }
            "voice" -> {
                val phrase = a.p("phrase")
                val c = CommandParser.parse(phrase)
                if (c == Command.Unknown) Out(error = "unknown command “$phrase”")
                else {
                    val r = svc.exec(c)
                    if (isFail(r)) Out(error = r) else Out(600)
                }
            }
            "set_clipboard" -> {
                if (svc.setClipboard(a.p("text"))) Out(200) else Out(error = "couldn't set clipboard")
            }

            "toast" -> {
                svc.setStatus(a.p("message"), 2500)
                Out(300)
            }
            "speak" -> {
                svc.speak(a.p("text"))
                Out(300)
            }
            "vibrate" -> {
                val ms = a.num("ms", 200.0).toLong()
                svc.vibrate(ms)
                Out(ms)
            }
            else -> Out(error = "unknown action “${a.type}”")
        }
    }

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
        val v = when (dir) {
            "down", "bottom" -> listOf(cx, (hh * 0.7).toInt(), cx, (hh * 0.3).toInt())
            "up", "top" -> listOf(cx, (hh * 0.3).toInt(), cx, (hh * 0.7).toInt())
            "right" -> listOf((w * 0.8).toInt(), cy, (w * 0.2).toInt(), cy)
            else -> listOf((w * 0.2).toInt(), cy, (w * 0.8).toInt(), cy)
        }
        svc.fxSwipe(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
        val one = "input swipe ${v[0]} ${v[1]} ${v[2]} ${v[3]} 250"
        if (dir == "top" || dir == "bottom") Shell.exec(m, "for i in 1 2 3 4 5 6 7 8; do $one; sleep 0.15; done")
        else Shell.exec(m, one)
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
            val moved = abs(x2 - x1) > 12 || abs(y2 - y1) > 12
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
