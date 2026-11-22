package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/** Draws numbered badges over every tappable element on screen. */
class NumberOverlay(private val service: AccessibilityService) {

    class Target(val number: Int, val node: AccessibilityNodeInfo, val rect: Rect)

    var targets: List<Target> = emptyList()
        private set

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BadgeView? = null

    fun show(root: AccessibilityNodeInfo?): Int {
        if (view == null) {
            val v = BadgeView(service)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(v, lp)
            view = v
            if (Anim.on(service)) {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(220).start()
            }
        }
        update(root)
        return targets.size
    }

    fun update(root: AccessibilityNodeInfo?) {
        targets = collect(root)
        view?.setTargets(targets)
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
        targets = emptyList()
    }

    private fun collect(root: AccessibilityNodeInfo?): List<Target> {
        val out = ArrayList<Target>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= 99 || !n.isVisibleToUser) return
            if (n.isClickable && n.isEnabled) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.add(Target(out.size + 1, n, r))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }

    private class BadgeView(context: Context) : View(context) {
        private var items: List<Target> = emptyList()
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        fun setTargets(t: List<Target>) {
            items = t
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val pad = 5f * density
            val h = 20f * density
            for (t in items) {
                val label = t.number.toString()
                val w = maxOf(h, text.measureText(label) + pad * 2)
                val x = (t.rect.left - loc[0]).toFloat() + 2f * density
                val y = (t.rect.top - loc[1]).toFloat() + 2f * density
                val box = RectF(x, y, x + w, y + h)
                canvas.drawRoundRect(box, h / 2, h / 2, fill)
                canvas.drawRoundRect(box, h / 2, h / 2, stroke)
                val baseline = box.centerY() - (text.descent() + text.ascent()) / 2
                canvas.drawText(label, box.centerX(), baseline, text)
            }
        }
    }
}
