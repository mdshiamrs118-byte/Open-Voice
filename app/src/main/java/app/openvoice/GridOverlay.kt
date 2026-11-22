package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Each level is (columns, rows). Picking a cell on a non-final level zooms in; on the final level it clicks. */
enum class GridMode(val label: String, val summary: String, val levels: List<Pair<Int, Int>>) {
    BIG("Big", "3×5 → 3×3 → 3×3 → click", listOf(3 to 5, 3 to 3, 3 to 3)),
    MEDIUM("Medium", "9×15 → 3×3 → click", listOf(9 to 15, 3 to 3)),
    SMALL("Small", "27×45 → click", listOf(27 to 45));

    companion object {
        fun from(s: String?): GridMode = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: MEDIUM
    }
}

class GridOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: GridView? = null
    private var mode = GridMode.MEDIUM
    private var level = 0
    private var region = RectF()
    private val stack = ArrayList<RectF>()

    val active: Boolean get() = view != null

    private fun full(): RectF {
        val (w, h) = Screen.size(service)
        return RectF(0f, 0f, w.toFloat(), h.toFloat())
    }

    fun show(m: GridMode) {
        mode = m
        level = 0
        stack.clear()
        region = full()
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
        push()
    }

    private fun push() {
        val (c, r) = mode.levels[level]
        view?.set(region, full(), c, r)
    }

    fun cellCount(): Int {
        val (c, r) = mode.levels[level]
        return c * r
    }

    fun levelText(): String =
        "${mode.label} grid · step ${level + 1}/${mode.levels.size} · say 1–${cellCount()}"

    /** Zooms into cell [n]; on the final step returns the point to click instead. */
    fun pick(n: Int): PointF? {
        val (cols, rows) = mode.levels[level]
        if (n !in 1..cols * rows) return null
        val idx = n - 1
        val row = idx / cols
        val col = idx % cols
        val cw = region.width() / cols
        val ch = region.height() / rows
        val cell = RectF(
            region.left + col * cw, region.top + row * ch,
            region.left + (col + 1) * cw, region.top + (row + 1) * ch
        )
        if (level == mode.levels.lastIndex) return PointF(cell.centerX(), cell.centerY())
        stack.add(region)
        region = cell
        level++
        push()
        return null
    }

    /** Goes back one zoom step. Returns false if already at the first step. */
    fun undo(): Boolean {
        if (level == 0) return false
        region = stack.removeAt(stack.size - 1)
        level--
        push()
        return true
    }

    fun centerX(): Float = region.centerX()
    fun centerY(): Float = region.centerY()

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
        private var region = RectF()
        private var full = RectF()
        private var cols = 3
        private var rows = 3
        private val d = context.resources.displayMetrics.density

        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val textDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        private val textLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; isFakeBoldText = true; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
        }

        fun set(r: RectF, screen: RectF, c: Int, rw: Int) {
            region = RectF(r)
            full = RectF(screen)
            cols = c
            rows = rw
            invalidate()
        }

        private fun fit(digits: Int, maxW: Float, maxH: Float): Float =
            minOf(maxW / (0.62f * digits), maxH)

        override fun onDraw(c: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val r = RectF(
                region.left - loc[0], region.top - loc[1],
                region.right - loc[0], region.bottom - loc[1]
            )
            if (region != full) {
                c.save()
                c.clipOutRect(r)
                c.drawColor(0x77000000)
                c.restore()
            }

            val cw = r.width() / cols
            val ch = r.height() / rows
            val thin = minOf(cw, ch) / d < 40f
            dark.strokeWidth = if (thin) 2.4f * d else 4f * d
            light.strokeWidth = if (thin) 0.8f * d else 1.5f * d

            for (i in 1 until cols) {
                val x = r.left + i * cw
                c.drawLine(x, r.top, x, r.bottom, dark)
                c.drawLine(x, r.top, x, r.bottom, light)
            }
            for (j in 1 until rows) {
                val y = r.top + j * ch
                c.drawLine(r.left, y, r.right, y, dark)
                c.drawLine(r.left, y, r.right, y, light)
            }
            dark.strokeWidth = 4f * d
            light.strokeWidth = 1.8f * d
            c.drawRect(r, dark)
            c.drawRect(r, light)

            val total = cols * rows
            val digitsMax = total.toString().length
            val sizeByDigits = FloatArray(digitsMax + 1)
            val radius = minOf(15f * d, minOf(cw, ch) * 0.38f)
            for (dg in 1..digitsMax) {
                sizeByDigits[dg] = if (thin) {
                    maxOf(fit(dg, cw * 0.95f, ch * 0.7f), 3.5f * d)
                } else {
                    minOf(16f * d, fit(dg, radius * 1.7f, radius * 1.4f))
                }
            }

            for (n in 1..total) {
                val row = (n - 1) / cols
                val col = (n - 1) % cols
                val cx = r.left + (col + 0.5f) * cw
                val cy = r.top + (row + 0.5f) * ch
                val label = n.toString()
                val size = sizeByDigits[label.length]
                if (thin) {
                    outline.textSize = size
                    outline.strokeWidth = size * 0.3f
                    textLight.textSize = size
                    val base = cy - (textLight.descent() + textLight.ascent()) / 2
                    c.drawText(label, cx, base, outline)
                    c.drawText(label, cx, base, textLight)
                } else {
                    c.drawCircle(cx, cy, radius, badge)
                    dark.strokeWidth = 1.5f * d
                    c.drawCircle(cx, cy, radius, dark)
                    textDark.textSize = size
                    val base = cy - (textDark.descent() + textDark.ascent()) / 2
                    c.drawText(label, cx, base, textDark)
                }
            }
        }
    }
}
