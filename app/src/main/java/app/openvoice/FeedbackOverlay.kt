package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val KIND_TAP = 0
private const val KIND_LONG = 1
private const val KIND_SWIPE = 2

private class Fx(
    val kind: Int,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val start: Long, val dur: Long
)

/** Shows where Open Voice is tapping, long-pressing and swiping. Never intercepts touches. */
class FeedbackOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: FxView? = null

    private fun ensure(): FxView {
        view?.let { return it }
        val v = FxView(service)
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
        return v
    }

    fun tap(x: Float, y: Float) {
        ensure().add(Fx(KIND_TAP, x, y, x, y, SystemClock.uptimeMillis(), 550))
    }

    fun doubleTap(x: Float, y: Float) {
        val v = ensure()
        val now = SystemClock.uptimeMillis()
        v.add(Fx(KIND_TAP, x, y, x, y, now, 550))
        v.add(Fx(KIND_TAP, x, y, x, y, now + 170, 550))
    }

    fun longPress(x: Float, y: Float) {
        ensure().add(Fx(KIND_LONG, x, y, x, y, SystemClock.uptimeMillis(), 1000))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        ensure().add(Fx(KIND_SWIPE, x1, y1, x2, y2, SystemClock.uptimeMillis(), 550))
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    private class FxView(context: Context) : View(context) {
        private val items = ArrayList<Fx>()
        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val ringLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val thinDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val oval = RectF()

        fun add(f: Fx) {
            items.add(f)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()
            var alive = false
            val it = items.iterator()
            while (it.hasNext()) {
                val f = it.next()
                val p = (now - f.start).toFloat() / f.dur
                if (p >= 1f) {
                    it.remove()
                    continue
                }
                alive = true
                if (p < 0f) continue
                when (f.kind) {
                    KIND_TAP -> drawTap(c, f.x1 - ox, f.y1 - oy, p, false)
                    KIND_LONG -> drawTap(c, f.x1 - ox, f.y1 - oy, p, true)
                    else -> drawSwipe(c, f.x1 - ox, f.y1 - oy, f.x2 - ox, f.y2 - oy, p)
                }
            }
            if (alive) postInvalidateOnAnimation()
        }

        private fun drawTap(c: Canvas, cx: Float, cy: Float, p: Float, long: Boolean) {
            val a = if (long) (if (p > 0.85f) (1 - p) / 0.15f else 1f) else 1 - p
            val r = if (long) dp(30f) else dp(12f) + (1 - (1 - p) * (1 - p)) * dp(34f)
            val alpha = (a * 255).toInt()

            whiteFill.alpha = (a * 90).toInt()
            c.drawCircle(cx, cy, r, whiteFill)

            ringDark.alpha = alpha
            ringLight.alpha = alpha
            if (long) {
                oval.set(cx - r, cy - r, cx + r, cy + r)
                val sweep = 360f * minOf(1f, p / 0.85f)
                c.drawArc(oval, -90f, sweep, false, ringDark)
                c.drawArc(oval, -90f, sweep, false, ringLight)
            } else {
                c.drawCircle(cx, cy, r, ringDark)
                c.drawCircle(cx, cy, r, ringLight)
            }

            if (long || p < 0.6f) {
                blackFill.alpha = alpha
                whiteFill.alpha = alpha
                c.drawCircle(cx, cy, dp(7f), blackFill)
                c.drawCircle(cx, cy, dp(5f), whiteFill)
            }
        }

        private fun drawSwipe(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, p: Float) {
            val q = minOf(1f, p / 0.7f)
            val e = 1 - (1 - q) * (1 - q)
            val a = if (p < 0.7f) 1f else (1 - p) / 0.3f
            val alpha = (a * 255).toInt()
            val hx = x1 + (x2 - x1) * e
            val hy = y1 + (y2 - y1) * e

            ringDark.alpha = alpha
            ringLight.alpha = alpha
            thinDark.alpha = alpha
            whiteFill.alpha = alpha
            blackFill.alpha = alpha

            c.drawLine(x1, y1, hx, hy, ringDark)
            c.drawLine(x1, y1, hx, hy, ringLight)

            c.drawCircle(x1, y1, dp(6f), whiteFill)
            c.drawCircle(x1, y1, dp(6f), thinDark)

            // arrow head
            val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            for (s in intArrayOf(-1, 1)) {
                val wa = ang + PI + s * 0.5
                val ex = hx + (cos(wa) * dp(16f)).toFloat()
                val ey = hy + (sin(wa) * dp(16f)).toFloat()
                c.drawLine(hx, hy, ex, ey, ringDark)
                c.drawLine(hx, hy, ex, ey, ringLight)
            }

            c.drawCircle(hx, hy, dp(10f), whiteFill)
            c.drawCircle(hx, hy, dp(10f), thinDark)
        }
    }
}
