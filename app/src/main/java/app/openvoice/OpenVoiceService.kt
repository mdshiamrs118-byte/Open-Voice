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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    private var dictation = false
    private var numbersOn = false
    private var sleeping = false

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val refreshNumbers = Runnable {
        if (numbersOn) numbers.update(rootInActiveWindow)
    }
    private val revertStatus = Runnable { showIdle() }

    override fun onServiceConnected() {
        instance = this
        numbers = NumberOverlay(this)
        grid = GridOverlay(this)
        fx = FeedbackOverlay(this)
        status = StatusOverlay(this)
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
        if (::numbers.isInitialized) {
            numbers.hide()
            grid.hide()
            fx.hide()
            status.hide()
        }
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------
    // Top status pill (called by ListenService)
    // ------------------------------------------------------------------
    fun idleText(): String = when {
        sleeping -> "⏸ Paused · say “resume listening”"
        dictation -> "✎ Dictating · say “stop dictation”"
        grid.active -> "▦ Grid · say 1–9, then “tap”"
        else -> "● Listening"
    }

    fun setStatus(text: String, holdMs: Long = 0) {
        if (!::status.isInitialized) return
        main.removeCallbacks(revertStatus)
        if (!Prefs.statusBar(this)) {
            status.hide()
            return
        }
        status.show(text)
        if (holdMs > 0) main.postDelayed(revertStatus, holdMs)
    }

    fun showIdle() = setStatus(idleText())

    fun hideStatus() {
        main.removeCallbacks(revertStatus)
        if (::status.isInitialized) status.hide()
    }

    // ------------------------------------------------------------------
    // Visual feedback helpers
    // ------------------------------------------------------------------
    private fun fxOn() = Prefs.gestureFx(this)
    private fun fxTap(x: Float, y: Float) { if (fxOn()) fx.tap(x, y) }
    private fun fxDouble(x: Float, y: Float) { if (fxOn()) fx.doubleTap(x, y) }
    private fun fxLong(x: Float, y: Float) { if (fxOn()) fx.longPress(x, y) }
    private fun fxSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (fxOn()) fx.swipe(x1, y1, x2, y2)
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

        if (grid.active) {
            for (a in alternatives) {
                val picks = CommandParser.parseGridPicks(a) ?: continue
                return "“$a” → ${execute(Command.GridPick(picks))}"
            }
        }

        var heard = alternatives[0]
        var cmd: Command = CommandParser.parse(heard)
        if (cmd == Command.Unknown) {
            for (alt in alternatives.drop(1)) {
                val c = CommandParser.parse(alt)
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
        return "“$heard” → ${execute(cmd)}"
    }

    private fun done(ok: Boolean) = if (ok) "done" else "failed"

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
            val w = screenW()
            val h = screenH()
            done(swipe(w / 2, h * 0.3f, w / 2, h * 0.8f, 500))
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
            grid.show()
            "grid on"
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
            if (!grid.active) "say “show grid” first"
            else {
                cmd.digits.forEach { grid.pick(it) }
                "zoomed"
            }
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

        // control
        Command.Sleep -> {
            sleeping = true
            "paused"
        }
        Command.Wake -> "already listening"
        Command.ShowCommands -> {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "opening list"
        }
        Command.StopListening -> {
            ListenService.stop(this)
            "stopped"
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

    private fun atGrid(block: (Float, Float) -> Boolean): String {
        if (!grid.active) return "say “show grid” first"
        val x = grid.centerX()
        val y = grid.centerY()
        grid.hide()
        return done(block(x, y))
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
    private fun screenW() = resources.displayMetrics.widthPixels.toFloat()
    private fun screenH() = resources.displayMetrics.heightPixels.toFloat()

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
        val w = screenW()
        val h = screenH()
        return if (forward) swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
        else swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
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
        val w = screenW()
        val h = screenH()
        return done(
            if (fingerLeft) swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        )
    }

    /** fingerUp = true: finger moves bottom-to-top. */
    private fun swipeVertical(fingerUp: Boolean): String {
        val w = screenW()
        val h = screenH()
        return done(
            if (fingerUp) swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
            else swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
        )
    }

    // ------------------------------------------------------------------
    // Raw gestures
    // ------------------------------------------------------------------
    private fun point(x: Float, y: Float, start: Long, dur: Long) =
        GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, start, dur)

    private fun tapRaw(x: Float, y: Float): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(point(x, y, 0, 50)).build(), null, null
    )

    private fun doubleTapRaw(x: Float, y: Float): Boolean = dispatchGesture(
        GestureDescription.Builder()
            .addStroke(point(x, y, 0, 50))
            .addStroke(point(x, y, 150, 50))
            .build(), null, null
    )

    private fun longPressRaw(x: Float, y: Float): Boolean = dispatchGesture(
        GestureDescription.Builder().addStroke(point(x, y, 0, 900)).build(), null, null
    )

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
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

    @Suppress("DEPRECATION")
    private fun openApp(name: String): String {
        val pm = packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(probe, 0)
        val q = name.lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "app “$name” not found"
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "can't launch"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "opening ${match.loadLabel(pm)}"
    }
}
