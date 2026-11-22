package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import kotlin.math.roundToInt
import kotlin.math.abs

class OpenVoiceService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OpenVoiceService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var numbers: NumberOverlay
    private lateinit var grid: GridOverlay
    private lateinit var fx: FeedbackOverlay
    private lateinit var status: StatusOverlay
    private lateinit var engine: MacroEngine
    private var recorder: GestureRecorder? = null

    private var dictation = false
    private var numbersOn = false
    private var sleeping = false
    private var clickMode = false

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val refreshNumbers = Runnable {
        if (numbersOn) numbers.update(rootInActiveWindow)
    }
    private val revertRunnable = Runnable { settle() }

    override fun onServiceConnected() {
        instance = this
        numbers = NumberOverlay(this)
        grid = GridOverlay(this)
        fx = FeedbackOverlay(this)
        status = StatusOverlay(this)
        engine = MacroEngine(this)
        try {
            getSystemService(CameraManager::class.java).registerTorchCallback(torchCb, main)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!numbersOn || event == null) return
        if (event.packageName == packageName) return
        main.removeCallbacks(refreshNumbers)
        main.postDelayed(refreshNumbers, 350)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacksAndMessages(null)
        if (::engine.isInitialized) engine.cancel()
        recorder?.cancel()
        recorder = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        try {
            getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCb)
        } catch (_: Exception) {
        }
        if (::numbers.isInitialized) {
            numbers.hide()
            grid.hide()
            fx.hide()
            status.hide(false)
        }
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------
    // Top bar. Two styles (Options in the app):
    //   always visible  -> shows "● Listening" (or the current mode) all the time
    //   only when speaking -> appears when you start talking, then hides after the result
    // ------------------------------------------------------------------
    fun idleText(): String = when {
        sleeping -> "⏸ Paused · say “resume listening”"
        dictation -> "✎ Dictating · say “stop dictation”"
        grid.active -> "▦ ${grid.levelText()}"
        clickMode -> "👆 Click mode · say what to click · “stop clicking” to exit"
        else -> "● Listening"
    }

    private fun barEnabled() = ::status.isInitialized && Prefs.statusBar(this)

    private fun showBar(text: String) {
        if (!::status.isInitialized) return
        main.removeCallbacks(revertRunnable)
        if (!Prefs.statusBar(this)) {
            status.hide()
            return
        }
        status.show(text)
    }

    /** Shows a message, then returns to the resting state after [holdMs]. */
    fun setStatus(text: String, holdMs: Long = 2500) {
        showBar(text)
        if (barEnabled()) main.postDelayed(revertRunnable, holdMs)
    }

    /** Resting state: idle pill when "always visible" and listening, otherwise hidden. */
    fun settle() {
        if (!::status.isInitialized) return
        main.removeCallbacks(revertRunnable)
        if (Prefs.statusBar(this) && Prefs.statusAlways(this) && ListenService.running) {
            status.show(idleText())
        } else {
            status.hide()
        }
    }

    fun onSpeechStart() {
        if (barEnabled()) showBar("● Hearing…")
    }

    fun onPartial(text: String) {
        if (barEnabled()) showBar("“$text”")
    }

    /** Removes the numbers and grid overlays (used when listening stops or is turned off). */
    fun clearOverlays() {
        numbersOn = false
        main.removeCallbacks(refreshNumbers)
        if (::numbers.isInitialized) {
            numbers.hide()
            grid.hide()
        }
    }

    fun hideStatus() {
        main.removeCallbacks(revertRunnable)
        if (::status.isInitialized) status.hide()
    }

    // ------------------------------------------------------------------
    // Visual feedback helpers (internal: also used by MacroEngine)
    // ------------------------------------------------------------------
    private fun fxOn() = Prefs.gestureFx(this)
    internal fun fxTap(x: Float, y: Float) { if (fxOn()) fx.tap(x, y) }
    internal fun fxDouble(x: Float, y: Float) { if (fxOn()) fx.doubleTap(x, y) }
    internal fun fxLong(x: Float, y: Float) { if (fxOn()) fx.longPress(x, y) }
    internal fun fxSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (fxOn()) fx.swipe(x1, y1, x2, y2)
    }

    // ------------------------------------------------------------------
    // Custom commands
    // ------------------------------------------------------------------
    fun runCustom(cmd: CustomCommand, captured: String?, delayMs: Long = 0) {
        val go = Runnable {
            engine.run(cmd, captured) { res -> setStatus("${cmd.name}: $res") }
        }
        if (delayMs > 0) main.postDelayed(go, delayMs) else go.run()
    }

    fun startRecorder() {
        recorder?.cancel()
        recorder = GestureRecorder(this) { g ->
            recorder = null
            if (g != null) {
                g.name = "Gesture ${Store.gestures(this).size + 1}"
                Store.addGesture(this, g)
                Toast.makeText(this, "Saved “${g.name}”", Toast.LENGTH_SHORT).show()
            }
            startActivity(Intent(this, GesturesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.also { it.start() }
    }

    fun playGestureAfter(g: Gesture, delayMs: Long) {
        main.postDelayed({ playGesture(g) }, delayMs)
    }

    /** Replays a recorded gesture (multi-stroke, multi-finger) with the accessibility API. */
    internal fun playGesture(g: Gesture): Boolean {
        val (w, h) = Screen.size(this)
        val maxDur = GestureDescription.getMaxGestureDuration()
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        val b = GestureDescription.Builder()
        var n = 0
        for (s in g.strokes) {
            if (n >= maxStrokes || s.points.isEmpty()) continue
            val start = s.startMs.coerceIn(0L, maxDur - 100)
            val dur = s.durMs.coerceIn(50L, maxDur - start)
            val first = s.points.first()
            val last = s.points.last()
            val moved = s.points.any { abs(it.x - first.x) * w > 12 || abs(it.y - first.y) * h > 12 }
            val p = Path()
            p.moveTo(first.x * w, first.y * h)
            if (moved) s.points.drop(1).forEach { p.lineTo(it.x * w, it.y * h) }
            try {
                b.addStroke(GestureDescription.StrokeDescription(p, start, dur))
            } catch (e: Exception) {
                continue
            }
            n++
            val fx1 = first.x * w
            val fy1 = first.y * h
            val lx = last.x * w
            val ly = last.y * h
            main.postDelayed({
                when {
                    moved -> fxSwipe(fx1, fy1, lx, ly)
                    dur > 400 -> fxLong(fx1, fy1)
                    else -> fxTap(fx1, fy1)
                }
            }, start)
        }
        if (n == 0) return false
        return dispatchGesture(b.build(), null, null)
    }

    // ------------------------------------------------------------------
    // Entry point called by ListenService with the recognizer's alternatives
    // ------------------------------------------------------------------
    fun handleSpeech(alternatives: List<String>): String {
        if (alternatives.isEmpty()) return ""

        if (sleeping) {
            if (alternatives.any { CommandParser.parse(it) == Command.Wake }) {
                sleeping = false
                return "“resume listening” → awake"
            }
            return ""
        }

        // click mode: the exit phrase is always honoured
        if (clickMode && alternatives.any { CommandParser.parse(it) == Command.ClickModeOff }) {
            clickMode = false
            return "“stop clicking” → click mode off"
        }

        if (grid.active) {
            if (alternatives.any {
                    val t = it.trim().lowercase().trimEnd('.', '!', '?')
                    t == "undo" || CommandParser.parse(t) == Command.Back
                }
            ) {
                return "“undo” → ${gridUndo()}"
            }
            for (a in alternatives) {
                val picks = CommandParser.parseGridPicks(a, grid.cellCount()) ?: continue
                return "“$a” → ${execute(Command.GridPick(picks))}"
            }
        }

        // custom commands win over built-in ones
        if (!dictation) {
            val customs = Store.commands(this)
            for (a in alternatives) {
                val m = CustomMatcher.match(a, customs) ?: continue
                runCustom(m.command, m.captured)
                return "“$a” → ▶ ${m.command.name}"
            }
        }

        // click mode: just say the text of what you want to click
        if (clickMode && !dictation) {
            clickByVoice(alternatives)?.let { return it }
        }

        var heard = alternatives[0]
        var cmd: Command = parseEnabled(heard)
        if (cmd == Command.Unknown) {
            for (alt in alternatives.drop(1)) {
                val c = parseEnabled(alt)
                if (c != Command.Unknown) {
                    cmd = c
                    heard = alt
                    break
                }
            }
        }
        if (dictation) {
            val stillCommand = cmd == Command.DictationOff || cmd == Command.ClearText ||
                cmd == Command.Enter || cmd == Command.StopListening || cmd == Command.Delete ||
                cmd == Command.DeleteWord || cmd == Command.NewLine || cmd == Command.SelectAll ||
                cmd == Command.Copy || cmd == Command.Paste || cmd == Command.Cut ||
                cmd == Command.Sleep
            if (!stillCommand) {
                heard = alternatives[0]
                cmd = Command.TypeText(heard)
            }
        }
        if (clickMode && !dictation && cmd == Command.Unknown) return "“$heard” → nothing on screen to click"
        return "“$heard” → ${execute(cmd)}"
    }

    private fun cleanClickTarget(raw: String): String {
        var t = raw.trim().trimEnd('.', '!', '?', ',')
        val prefixes = listOf("click ", "tap ", "press ", "select ", "touch ", "on ", "the ", "this ", "that ")
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (t.length > p.length && t.startsWith(p, ignoreCase = true)) {
                    t = t.substring(p.length).trim()
                    changed = true
                }
            }
        }
        return t
    }

    /** In click mode: a spoken number taps that badge, spoken text taps the matching element on screen. */
    private fun clickByVoice(alternatives: List<String>): String? {
        for (a in alternatives) {
            val t = cleanClickTarget(a)
            if (t.isEmpty()) continue
            val n = CommandParser.parseNumber(t.lowercase())
            if (numbersOn && n != null && numbers.targets.any { it.number == n }) {
                return "“$a” → ${execute(Command.Tap(n))}"
            }
            val root = rootInActiveWindow ?: continue
            if (findVisible(root, t).isNotEmpty()) return "“$a” → ${clickText(t)}"
        }
        return null
    }

    private fun parseEnabled(text: String): Command {
        val c = CommandParser.parse(text)
        return if (c != Command.Unknown && !BuiltIns.isEnabled(this, c.groupId())) Command.Unknown else c
    }

    private fun done(ok: Boolean) = if (ok) "done" else "failed"

    /** Runs one built-in command and returns a short result. */
    fun exec(cmd: Command): String = execute(cmd)

    private fun execute(cmd: Command): String = when (cmd) {
        // navigation
        Command.Back -> done(performGlobalAction(GLOBAL_ACTION_BACK))
        Command.Home -> done(performGlobalAction(GLOBAL_ACTION_HOME))
        Command.Recents -> done(performGlobalAction(GLOBAL_ACTION_RECENTS))
        Command.Notifications -> done(performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS))
        Command.QuickSettings -> done(performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS))
        Command.PowerMenu -> done(performGlobalAction(GLOBAL_ACTION_POWER_DIALOG))
        Command.SplitScreen -> done(performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN))
        Command.Screenshot ->
            if (Build.VERSION.SDK_INT >= 28) done(performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT))
            else "needs Android 9+"
        Command.LockScreen ->
            if (Build.VERSION.SDK_INT >= 28) done(performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
            else "needs Android 9+"

        // scrolling / swiping
        Command.ScrollDown -> done(scrollOnce(true))
        Command.ScrollUp -> done(scrollOnce(false))
        Command.ScrollLeft -> scrollHorizontal(false)
        Command.ScrollRight -> scrollHorizontal(true)
        Command.ScrollTop -> {
            scrollRepeat(false)
            "scrolling to top"
        }
        Command.ScrollBottom -> {
            scrollRepeat(true)
            "scrolling to bottom"
        }
        Command.SwipeLeft -> swipeHorizontal(true)
        Command.SwipeRight -> swipeHorizontal(false)
        Command.SwipeUp -> swipeVertical(true)
        Command.SwipeDown -> swipeVertical(false)
        Command.Refresh -> {
            val (w, h) = Screen.size(this)
            done(swipe(w / 2f, h * 0.3f, w / 2f, h * 0.8f, 500))
        }

        // numbers
        Command.ShowNumbers -> {
            grid.hide()
            numbersOn = true
            "${numbers.show(rootInActiveWindow)} items"
        }
        Command.HideNumbers -> {
            numbersOn = false
            numbers.hide()
            "hidden"
        }
        is Command.Tap -> withTarget(cmd.number) { clickNode(it.node, it.rect) }
        is Command.DoubleTap -> withTarget(cmd.number) {
            fxDouble(it.rect.exactCenterX(), it.rect.exactCenterY())
            doubleTapRaw(it.rect.exactCenterX(), it.rect.exactCenterY())
        }
        is Command.LongPress -> withTarget(cmd.number) {
            fxLong(it.rect.exactCenterX(), it.rect.exactCenterY())
            it.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        is Command.ClickText -> clickText(cmd.text)

        // grid
        Command.ShowGrid -> {
            numbersOn = false
            numbers.hide()
            grid.show(Prefs.gridMode(this))
            grid.levelText()
        }
        is Command.SetGrid -> {
            Prefs.setGridMode(this, cmd.mode)
            if (grid.active) grid.show(cmd.mode)
            "grid: ${cmd.mode.label} (${cmd.mode.summary})"
        }
        Command.HideGrid -> {
            grid.hide()
            "grid off"
        }
        Command.HideAll -> {
            grid.hide()
            numbersOn = false
            numbers.hide()
            "cleared"
        }
        is Command.GridPick -> {
            if (!grid.active) "say “show grid” first" else gridPicks(cmd.numbers)
        }
        Command.TapHere -> atGrid { x, y ->
            fxTap(x, y)
            tapRaw(x, y)
        }
        Command.DoubleTapHere -> atGrid { x, y ->
            fxDouble(x, y)
            doubleTapRaw(x, y)
        }
        Command.LongPressHere -> atGrid { x, y ->
            fxLong(x, y)
            longPressRaw(x, y)
        }

        // text
        is Command.TypeText -> typeText(cmd.text)
        Command.ClearText -> setFocusedText("")
        Command.SelectAll -> selectAll()
        Command.Copy -> onFocused { done(it.performAction(AccessibilityNodeInfo.ACTION_COPY)) }
        Command.Paste -> onFocused { done(it.performAction(AccessibilityNodeInfo.ACTION_PASTE)) }
        Command.Cut -> onFocused { done(it.performAction(AccessibilityNodeInfo.ACTION_CUT)) }
        Command.Delete -> deleteBackward()
        Command.DeleteWord -> deleteWord()
        Command.NewLine -> onFocused { setText(it, currentText(it) + "\n") }
        Command.Enter -> pressEnter()
        Command.DictationOn -> {
            clickMode = false
            dictation = true
            "dictation on"
        }
        Command.DictationOff -> {
            dictation = false
            "dictation off"
        }

        // device / apps
        is Command.OpenApp -> openApp(cmd.name)
        Command.VolumeUp -> volume(AudioManager.ADJUST_RAISE)
        Command.VolumeDown -> volume(AudioManager.ADJUST_LOWER)
        Command.Mute -> volume(AudioManager.ADJUST_MUTE)
        Command.Unmute -> volume(AudioManager.ADJUST_UNMUTE)

        is Command.Volume -> volumeCmd(cmd.stream, cmd.op, cmd.pct)
        is Command.Brightness -> brightnessCmd(cmd.op, cmd.pct)
        is Command.Toggle -> systemToggle(cmd.name, cmd.state)
        is Command.Ringer -> ringer(cmd.mode)

        // control
        Command.ClickModeOn -> {
            dictation = false
            clickMode = true
            "click mode on · just say what to click"
        }
        Command.ClickModeOff -> {
            clickMode = false
            "click mode off"
        }
        Command.Sleep -> {
            sleeping = true
            "paused"
        }
        Command.Wake -> "already listening"
        Command.ShowCommands -> {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "opening list"
        }
        Command.StopListening -> {
            clearOverlays()
            ListenService.stop(this)
            "stopped"
        }
        Command.StopMacro -> {
            engine.cancel()
            "stopped"
        }
        is Command.RunCustom -> {
            val c = Store.findCommandByName(this, cmd.name)
            if (c == null) "no command “${cmd.name}”"
            else {
                runCustom(c, null)
                "running ${c.name}"
            }
        }
        Command.Unknown -> "didn't understand"
    }

    // ------------------------------------------------------------------
    // Targets (numbers / grid)
    // ------------------------------------------------------------------
    private fun withTarget(n: Int, block: (NumberOverlay.Target) -> Boolean): String {
        val t = numbers.targets.firstOrNull { it.number == n }
            ?: return if (numbersOn) "no item $n" else "say “show numbers” first"
        return done(block(t))
    }

    /** Applies cell numbers in order: zooms in, and clicks on the final step. */
    private fun gridPicks(nums: List<Int>): String {
        for (n in nums) {
            if (!grid.active) break
            val max = grid.cellCount()
            if (n !in 1..max) return "no cell $n (1–$max)"
            val p = grid.pick(n)
            if (p != null) {
                fxTap(p.x, p.y)
                val ok = tapRaw(p.x, p.y)
                grid.show(Prefs.gridMode(this)) // stays on screen, back at the first step
                return if (ok) "clicked" else "failed"
            }
        }
        return grid.levelText()
    }

    private fun gridUndo(): String {
        if (!grid.undo()) {
            grid.hide()
            return "grid closed"
        }
        return grid.levelText()
    }

    private fun atGrid(block: (Float, Float) -> Boolean): String {
        if (!grid.active) return "say “show grid” first"
        val x = grid.centerX()
        val y = grid.centerY()
        val ok = block(x, y)
        grid.show(Prefs.gridMode(this)) // stays on screen, back at the first step
        return done(ok)
    }

    // ------------------------------------------------------------------
    // Clicking
    // ------------------------------------------------------------------
    private fun clickNode(node: AccessibilityNodeInfo?, rect: Rect): Boolean {
        fxTap(rect.exactCenterX(), rect.exactCenterY())
        node?.refresh()
        var cur = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return tapRaw(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun clickText(query: String): String {
        val root = rootInActiveWindow ?: return "no window"
        var q = query
        var visible = findVisible(root, q)
        if (visible.isEmpty()) {
            for (suffix in listOf(" button", " icon", " tab", " link", " option")) {
                if (q.endsWith(suffix)) {
                    q = q.removeSuffix(suffix)
                    visible = findVisible(root, q)
                    break
                }
            }
        }
        if (visible.isEmpty()) return "“$query” not found"
        val best = visible.firstOrNull {
            (it.text ?: it.contentDescription)?.toString().equals(q, ignoreCase = true)
        } ?: visible.first()
        val r = Rect()
        best.getBoundsInScreen(r)
        return done(clickNode(best, r))
    }

    private fun findVisible(root: AccessibilityNodeInfo, q: String): List<AccessibilityNodeInfo> =
        (root.findAccessibilityNodeInfosByText(q) ?: emptyList()).filter { it.isVisibleToUser }

    // ------------------------------------------------------------------
    // Scrolling / swiping
    // ------------------------------------------------------------------
    private fun scrollOnce(forward: Boolean, allowGesture: Boolean = true): Boolean {
        val node = rootInActiveWindow?.let { findLargestScrollable(it, null) }
        if (node != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val dy = r.height() * 0.25f
                val cx = r.exactCenterX()
                val cy = r.exactCenterY()
                if (forward) fxSwipe(cx, cy + dy, cx, cy - dy) else fxSwipe(cx, cy - dy, cx, cy + dy)
                return true
            }
            if (!allowGesture) return false
        }
        val (w, h) = Screen.size(this)
        return if (forward) swipe(w / 2f, h * 0.7f, w / 2f, h * 0.3f)
        else swipe(w / 2f, h * 0.3f, w / 2f, h * 0.7f)
    }

    private fun scrollRepeat(forward: Boolean, times: Int = 15) {
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i++ >= times) return
                if (scrollOnce(forward, allowGesture = false)) main.postDelayed(this, 380)
            }
        }
        main.post(step)
    }

    private fun scrollHorizontal(right: Boolean): String {
        val act = if (right) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
        val node = rootInActiveWindow?.let { findLargestScrollable(it, act) }
        if (node != null && node.performAction(act.id)) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val dx = r.width() * 0.25f
            val cx = r.exactCenterX()
            val cy = r.exactCenterY()
            if (right) fxSwipe(cx + dx, cy, cx - dx, cy) else fxSwipe(cx - dx, cy, cx + dx, cy)
            return "done"
        }
        return swipeHorizontal(right) // scrolling right = finger moves left
    }

    private fun findLargestScrollable(
        root: AccessibilityNodeInfo,
        needs: AccessibilityNodeInfo.AccessibilityAction?
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            if (n.isScrollable && (needs == null || n.actionList.contains(needs))) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height().toLong()
                if (area > bestArea) {
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    /** fingerLeft = true: finger moves right-to-left. */
    private fun swipeHorizontal(fingerLeft: Boolean): String {
        val (w, h) = Screen.size(this)
        return done(
            if (fingerLeft) swipe(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
            else swipe(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
        )
    }

    /** fingerUp = true: finger moves bottom-to-top. */
    private fun swipeVertical(fingerUp: Boolean): String {
        val (w, h) = Screen.size(this)
        return done(
            if (fingerUp) swipe(w / 2f, h * 0.7f, w / 2f, h * 0.3f)
            else swipe(w / 2f, h * 0.3f, w / 2f, h * 0.7f)
        )
    }

    // ------------------------------------------------------------------
    // Raw gestures
    // ------------------------------------------------------------------
    private fun point(x: Float, y: Float, start: Long, dur: Long) =
        GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, start, dur)

    internal fun tapRaw(x: Float, y: Float): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(point(x, y, 0, 50)).build(), null, null
    )

    internal fun doubleTapRaw(x: Float, y: Float): Boolean = dispatchGesture(
        GestureDescription.Builder()
            .addStroke(point(x, y, 0, 50))
            .addStroke(point(x, y, 150, 50))
            .build(), null, null
    )

    internal fun longPressRaw(x: Float, y: Float, ms: Long = 900): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(point(x, y, 0, ms)).build(), null, null
    )

    internal fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
        fxSwipe(x1, y1, x2, y2)
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
                .build(), null, null
        )
    }

    // ------------------------------------------------------------------
    // Text editing
    // ------------------------------------------------------------------
    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun onFocused(block: (AccessibilityNodeInfo) -> String): String {
        val node = focusedInput() ?: return "no text field focused"
        return block(node)
    }

    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun typeText(text: String): String = onFocused { node ->
        val existing = currentText(node)
        val sep = if (existing.isNotEmpty() && !existing.endsWith(" ") && !existing.endsWith("\n")) " " else ""
        setText(node, existing + sep + text)
    }

    private fun setFocusedText(text: String): String = onFocused { setText(it, text) }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int = text.length): String {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return done(ok)
    }

    private fun selectAll(): String = onFocused { node ->
        val len = currentText(node).length
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        done(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel))
    }

    private fun deleteBackward(): String = onFocused { node ->
        val text = currentText(node)
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        when {
            s >= 0 && e > s && e <= text.length -> setText(node, text.removeRange(s, e), s)
            text.isEmpty() -> "nothing to delete"
            else -> setText(node, text.dropLast(1))
        }
    }

    private fun deleteWord(): String = onFocused { node ->
        val t = currentText(node).trimEnd()
        if (t.isEmpty()) "nothing to delete"
        else {
            val i = t.lastIndexOf(' ')
            setText(node, if (i < 0) "" else t.substring(0, i))
        }
    }

    private fun pressEnter(): String = onFocused { node ->
        if (Build.VERSION.SDK_INT >= 30) {
            done(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
        } else {
            "needs Android 11+"
        }
    }

    // ------------------------------------------------------------------
    // System controls: volume, brightness, switches
    // ------------------------------------------------------------------
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
        }
    }

    private fun streamOf(name: String) = when (name) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun volumeCmd(stream: String, op: String, pct: Int): String {
        val st = streamOf(stream)
        return try {
            val max = audio.getStreamMaxVolume(st)
            when (op) {
                "up" -> audio.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> audio.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> audio.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                else -> {
                    val target = when (op) {
                        "max" -> max
                        "min" -> (max * 0.2f).roundToInt().coerceAtLeast(1)
                        else -> (pct / 100f * max).roundToInt()
                    }
                    audio.setStreamVolume(st, target.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
                }
            }
            val now = audio.getStreamVolume(st)
            "$stream volume ${if (max > 0) now * 100 / max else 0}%"
        } catch (e: SecurityException) {
            "needs Do Not Disturb access for that volume"
        } catch (e: Exception) {
            "failed"
        }
    }

    private fun ringer(mode: String): String = try {
        audio.ringerMode = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        "$mode mode"
    } catch (e: SecurityException) {
        "needs Do Not Disturb access (try “turn on do not disturb”)"
    } catch (e: Exception) {
        "failed"
    }

    private fun askWriteSettings(): String {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
        return "allow “Modify system settings” for Open Voice first"
    }

    private fun brightnessCmd(op: String, pct: Int): String {
        val mode = Prefs.sysMode(this)
        val cur = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            return "failed"
        }
        if (op == "auto") {
            if (mode != Mode.NORMAL) {
                Shell.exec(mode, "settings put system screen_brightness_mode 1")
                return "auto brightness on"
            }
            if (!Settings.System.canWrite(this)) return askWriteSettings()
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "auto brightness on"
        }
        val step = (255 * 0.15f).roundToInt()
        val target = when (op) {
            "up" -> cur + step
            "down" -> cur - step
            "max" -> 255
            "min" -> (255 * 0.08f).roundToInt()
            else -> (pct / 100f * 255).roundToInt()
        }.coerceIn(1, 255)

        if (mode != Mode.NORMAL) {
            Shell.exec(mode, "settings put system screen_brightness_mode 0 ; settings put system screen_brightness $target")
            return "brightness ${target * 100 / 255}%"
        }
        if (!Settings.System.canWrite(this)) return askWriteSettings()
        return try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            "brightness ${target * 100 / 255}%"
        } catch (e: Exception) {
            "failed"
        }
    }

    private fun setTorch(state: Boolean?): String {
        return try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "no flashlight on this device"
            val target = state ?: !torchOn
            cm.setTorchMode(id, target)
            "flashlight ${if (target) "on" else "off"}"
        } catch (e: Exception) {
            "flashlight failed"
        }
    }

    /**
     * Flips a system switch. Flashlight and auto-rotate use direct APIs; Root/Shizuku mode uses shell
     * commands where one exists; everything else (Wi-Fi, Bluetooth, hotspot, any other tile) works by
     * opening Quick Settings and tapping the tile.
     */
    private fun systemToggle(rawName: String, state: Boolean?): String {
        val key = SystemToggles.canon(rawName)
        val spec = SystemToggles.spec(key)
        val title = spec?.title ?: rawName
        val labels = spec?.labels ?: listOf(rawName)
        val word = when (state) { true -> "on"; false -> "off"; null -> "toggled" }

        if (key == "flashlight") return setTorch(state)
        if (key == "rotate" && Settings.System.canWrite(this)) {
            return try {
                val now = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                val target = state ?: !now
                Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
                "auto-rotate ${if (target) "on" else "off"}"
            } catch (e: Exception) {
                "failed"
            }
        }

        val mode = Prefs.sysMode(this)
        if (mode != Mode.NORMAL && state != null) {
            val cmd = if (state) spec?.on else spec?.off
            if (cmd != null) {
                Shell.check(mode) { ok, why ->
                    if (ok) {
                        Shell.exec(mode, cmd)
                        setStatus("$title $word")
                    } else {
                        setStatus("⚠ $why")
                    }
                }
                return "$title → $word"
            }
        }
        return toggleViaTile(labels, state, title)
    }

    private fun toggleViaTile(labels: List<String>, want: Boolean?, title: String): String {
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return "can't open quick settings"
        fun attempt(n: Int) {
            val node = rootInActiveWindow?.let { findTile(it, labels) }
            if (node == null) {
                if (n < 5) {
                    main.postDelayed({ attempt(n + 1) }, 350)
                } else {
                    setStatus("“$title” tile not found")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return
            }
            val state = tileState(node)
            if (want != null && state == want) {
                setStatus("$title already ${if (want) "on" else "off"}")
            } else {
                val r = Rect()
                node.getBoundsInScreen(r)
                clickNode(node, r)
                setStatus("$title ${when (want) { true -> "on"; false -> "off"; null -> "toggled" }}")
            }
            main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        }
        main.postDelayed({ attempt(0) }, 600)
        return "$title…"
    }

    private fun findTile(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            val low = t.lowercase()
            for (l in labels) {
                val ll = l.lowercase()
                if (low.contains(ll)) {
                    val score = if (low.trim().startsWith(ll)) 2 else 1
                    var target: AccessibilityNodeInfo? = n
                    while (target != null && !target.isClickable) target = target.parent
                    if (target != null && score > bestScore) {
                        best = target
                        bestScore = score
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun tileState(node: AccessibilityNodeInfo): Boolean? {
        if (node.isCheckable) return node.isChecked
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= 30) sb.append(node.stateDescription ?: "").append(' ')
        sb.append(node.contentDescription ?: "").append(' ').append(node.text ?: "")
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            sb.append(' ').append(c.text ?: "").append(' ').append(c.contentDescription ?: "")
        }
        val t = sb.toString().lowercase()
        return when {
            Regex("\\boff\\b").containsMatchIn(t) -> false
            Regex("\\bon\\b").containsMatchIn(t) -> true
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Device / apps
    // ------------------------------------------------------------------
    private fun volume(direction: Int): String {
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            "done"
        } catch (e: Exception) {
            "failed"
        }
    }

    internal fun mediaKey(code: Int): Boolean = try {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Device state, used by conditions and {placeholders}
    // ------------------------------------------------------------------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsPending = ArrayList<String>()

    internal fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    internal fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    internal fun packageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    internal fun screenHasText(t: String): Boolean {
        if (t.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return (root.findAccessibilityNodeInfosByText(t) ?: emptyList()).any { it.isVisibleToUser }
    }

    internal fun screenText(max: Int = 2000): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length >= max || !n.isVisibleToUser) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(t)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString().take(max)
    }

    internal fun focusedText(): String = focusedInput()?.let { currentText(it) }.orEmpty()

    internal fun batteryLevel(): Int =
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    internal fun isCharging(): Boolean = getSystemService(BatteryManager::class.java).isCharging

    private fun netCaps(): NetworkCapabilities? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(n)
    }

    internal fun internetConnected(): Boolean {
        val c = netCaps() ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal fun wifiConnected(): Boolean = netCaps()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    internal fun deviceLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    internal fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Android may return nothing here when Open Voice isn't the focused app. */
    internal fun clipboardText(): String = try {
        getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    } catch (e: Exception) {
        ""
    }

    internal fun setClipboard(text: String): Boolean = try {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Open Voice", text))
        true
    } catch (e: Exception) {
        false
    }

    internal fun speak(text: String) {
        if (text.isBlank()) return
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) {
                    ttsPending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "ov") }
                    ttsPending.clear()
                }
            }
        }
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ov") else ttsPending.add(text)
    }

    internal fun vibrate(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(ms.coerceIn(10L, 5000L), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    internal fun findPackage(name: String): String? {
        val pm = packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(probe, 0)
        val q = name.lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
        return match?.activityInfo?.packageName
    }

    private fun openApp(name: String): String {
        val pkg = findPackage(name) ?: return "app “$name” not found"
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return "can't launch"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "opening $name"
    }
}
