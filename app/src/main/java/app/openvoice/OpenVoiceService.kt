package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
    private lateinit var overlay: NumberOverlay
    private var dictation = false
    private var numbersOn = false

    private val refresh = Runnable {
        if (numbersOn) overlay.update(rootInActiveWindow)
    }

    override fun onServiceConnected() {
        instance = this
        overlay = NumberOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!numbersOn || event == null) return
        if (event.packageName == packageName) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 350)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------
    // Entry point called by ListenService with the recognizer's alternatives
    // ------------------------------------------------------------------
    fun handleSpeech(alternatives: List<String>): String {
        if (alternatives.isEmpty()) return ""
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
                cmd == Command.Enter || cmd == Command.StopListening
            if (!stillCommand) {
                heard = alternatives[0]
                cmd = Command.TypeText(heard)
            }
        }
        return "“$heard” → ${execute(cmd)}"
    }

    private fun done(ok: Boolean) = if (ok) "done" else "failed"

    private fun execute(cmd: Command): String = when (cmd) {
        Command.Back -> done(performGlobalAction(GLOBAL_ACTION_BACK))
        Command.Home -> done(performGlobalAction(GLOBAL_ACTION_HOME))
        Command.Recents -> done(performGlobalAction(GLOBAL_ACTION_RECENTS))
        Command.Notifications -> done(performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS))
        Command.QuickSettings -> done(performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS))
        Command.ScrollDown -> scroll(true)
        Command.ScrollUp -> scroll(false)
        Command.SwipeLeft -> swipeHorizontal(true)
        Command.SwipeRight -> swipeHorizontal(false)
        Command.ShowNumbers -> {
            numbersOn = true
            "${overlay.show(rootInActiveWindow)} items"
        }
        Command.HideNumbers -> {
            numbersOn = false
            overlay.hide()
            "hidden"
        }
        is Command.Tap -> withTarget(cmd.number) { clickNode(it.node, it.rect) }
        is Command.LongPress -> withTarget(cmd.number) { it.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
        is Command.ClickText -> clickText(cmd.text)
        is Command.OpenApp -> openApp(cmd.name)
        is Command.TypeText -> typeText(cmd.text)
        Command.ClearText -> setFocusedText("")
        Command.Enter -> pressEnter()
        Command.DictationOn -> {
            dictation = true
            "dictation on"
        }
        Command.DictationOff -> {
            dictation = false
            "dictation off"
        }
        Command.StopListening -> {
            ListenService.stop(this)
            "stopped"
        }
        Command.Unknown -> "didn't understand"
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------
    private fun withTarget(n: Int, block: (NumberOverlay.Target) -> Boolean): String {
        val t = overlay.targets.firstOrNull { it.number == n }
            ?: return if (numbersOn) "no item $n" else "say “show numbers” first"
        return done(block(t))
    }

    private fun clickNode(node: AccessibilityNodeInfo?, rect: Rect): Boolean {
        node?.refresh()
        var cur = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun clickText(query: String): String {
        val root = rootInActiveWindow ?: return "no window"
        val visible = (root.findAccessibilityNodeInfosByText(query) ?: emptyList())
            .filter { it.isVisibleToUser }
        if (visible.isEmpty()) return "“$query” not found"
        val best = visible.firstOrNull {
            (it.text ?: it.contentDescription)?.toString().equals(query, ignoreCase = true)
        } ?: visible.first()
        val r = Rect()
        best.getBoundsInScreen(r)
        return done(clickNode(best, r))
    }

    private fun scroll(forward: Boolean): String {
        val node = rootInActiveWindow?.let { findLargestScrollable(it) }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node != null && node.performAction(action)) return "done"

        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return done(
            if (forward) swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
            else swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
        )
    }

    private fun findLargestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            if (n.isScrollable) {
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

    private fun swipeHorizontal(left: Boolean): String {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return done(
            if (left) swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        )
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun typeText(text: String): String {
        val node = focusedInput() ?: return "no text field focused"
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val sep = if (existing.isNotEmpty() && !existing.endsWith(" ")) " " else ""
        return setText(node, existing + sep + text)
    }

    private fun setFocusedText(text: String): String {
        val node = focusedInput() ?: return "no text field focused"
        return setText(node, text)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): String {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return done(ok)
    }

    private fun pressEnter(): String {
        val node = focusedInput() ?: return "no text field focused"
        return if (Build.VERSION.SDK_INT >= 30) {
            done(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
        } else {
            "needs Android 11+"
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
