package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 3x3 numbered grid for apps with poor accessibility trees. Say a number to zoom into
 * that cell (repeat to zoom further), then say "tap".
 */
class GridOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: GridView? = null
    private var region = Rect()

    val active: Boolean get() = view != null

    private fun screen(): Rect = try {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect(wm.currentWindowMetrics.bounds)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Rect(0, 0, m.widthPixels, m.heightPixels)
        }
    } catch (e: Exception) {
        val m = service.resources.displayMetrics
        Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    fun show() {
        region = screen()
        if (view == null) {
            val v = GridView(service)
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
        }
        view?.setRegion(region, screen())
    }

    /** Zoom into cell n (1..9, left-to-right, top-to-bottom). */
    fun pick(n: Int) {
        if (n !in 1..9) return
        val row = (n - 1) / 3
        val col = (n - 1) % 3
        val cw = region.width() / 3f
        val ch = region.height() / 3f
        region = Rect(
            (region.left + col * cw).toInt(),
            (region.top + row * ch).toInt(),
            (region.left + (col + 1) * cw).toInt(),
            (region.top + (row + 1) * ch).toInt()
        )
        view?.setRegion(region, screen())
    }

    fun centerX(): Float = region.exactCenterX()
    fun centerY(): Float = region.exactCenterY()

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    private class GridView(context: Context) : View(context) {
        private var region = Rect()
        private var full = Rect()
        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float) = v * density

        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
        }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = dp(16f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        fun setRegion(r: Rect, screen: Rect) {
            region = Rect(r)
            full = Rect(screen)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()
            val r = RectF(
                region.left - ox, region.top - oy,
                region.right - ox, region.bottom - oy
            )

            if (region != full) {
                c.save()
                c.clipOutRect(r)
                c.drawColor(0x77000000)
                c.restore()
            }

            val cw = r.width() / 3f
            val ch = r.height() / 3f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                c.drawLine(x1, y1, x2, y2, dark)
                c.drawLine(x1, y1, x2, y2, light)
            }
            for (i in 1..2) {
                line(r.left + i * cw, r.top, r.left + i * cw, r.bottom)
                line(r.left, r.top + i * ch, r.right, r.top + i * ch)
            }
            c.drawRect(r, dark)
            c.drawRect(r, light)

            val rad = dp(15f)
            for (n in 1..9) {
                val row = (n - 1) / 3
                val col = (n - 1) % 3
                val cx = r.left + (col + 0.5f) * cw
                val cy = r.top + (row + 0.5f) * ch
                c.drawCircle(cx, cy, rad, badge)
                c.drawCircle(cx, cy, rad, dark.also { it.strokeWidth = dp(1.5f) })
                dark.strokeWidth = dp(4f)
                val base = cy - (text.descent() + text.ascent()) / 2
                c.drawText(n.toString(), cx, base, text)
            }
        }
    }
}
