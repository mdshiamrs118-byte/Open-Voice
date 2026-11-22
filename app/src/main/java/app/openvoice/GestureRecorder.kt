package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Records a gesture drawn over whatever is on screen. Supports several strokes and several
 * fingers at once (pinch, two-finger swipe...). A 5 second countdown lets you switch to the
 * right screen first.
 */
class GestureRecorder(
    private val service: AccessibilityService,
    private val onFinish: (Gesture?) -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var countdown: TextView? = null
    private var frame: FrameLayout? = null
    private var draw: DrawView? = null
    private var left = 5
    private var finished = false

    fun start() {
        val tv = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = pill()
        }
        add(tv, full = false, touchable = false, gravity = Gravity.CENTER)
        countdown = tv
        tick()
    }

    fun cancel() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        removeAll()
    }

    private fun tick() {
        if (finished) return
        if (left <= 0) {
            countdown?.let { remove(it) }
            countdown = null
            showRecorder()
            return
        }
        countdown?.text = "Go to the screen you want to record on\nRecording starts in $left…"
        left--
        handler.postDelayed({ tick() }, 1000)
    }

    private fun showRecorder() {
        val f = FrameLayout(service)
        val dv = DrawView(service)
        f.addView(dv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val hint = TextView(service).apply {
            text = "Draw your gesture. Use two fingers for pinch.\nTap Save when you're done."
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill()
        }
        f.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) }
        )

        val bar = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bar.addView(btn("Cancel") { finish(null) })
        bar.addView(btn("Clear") { dv.clear() })
        bar.addView(btn("Save") {
            val g = dv.build()
            if (g == null) Toast.makeText(service, "Nothing recorded yet", Toast.LENGTH_SHORT).show()
            else finish(g)
        })
        f.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70) }
        )
        add(f, full = true, touchable = true, gravity = Gravity.TOP or Gravity.START)
        frame = f
        draw = dv
    }

    private fun finish(g: Gesture?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        removeAll()
        onFinish(g)
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(service).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
        setPadding(dp(22), 0, dp(22), 0)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
    }

    private fun pill() = GradientDrawable().apply {
        setColor(0xE6000000.toInt())
        setStroke(dp(2), Color.WHITE)
        cornerRadius = dp(20).toFloat()
    }

    private fun add(v: View, full: Boolean, touchable: Boolean, gravity: Int) {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val size = if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity
        wm.addView(v, lp)
    }

    private fun remove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun removeAll() {
        countdown?.let { remove(it) }
        frame?.let { remove(it) }
        countdown = null
        frame = null
        draw = null
    }

    // ------------------------------------------------------------------

    private class Live(val id: Int, val start: Long) {
        val pts = ArrayList<FloatArray>() // x, y, t
        var end = -1L
    }

    private class DrawView(context: Context) : View(context) {
        private val strokes = ArrayList<Live>()
        private val active = HashMap<Int, Live>()
        private var t0 = -1L
        private val d = context.resources.displayMetrics.density

        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 8f * d; strokeCap = Paint.Cap.ROUND
        }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val dotDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        fun clear() {
            strokes.clear()
            active.clear()
            t0 = -1L
            invalidate()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    if (t0 < 0) t0 = now
                    val s = Live(e.getPointerId(i), now - t0)
                    s.pts.add(floatArrayOf(e.getX(i) + loc[0], e.getY(i) + loc[1], (now - t0).toFloat()))
                    strokes.add(s)
                    active[s.id] = s
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val s = active[e.getPointerId(i)] ?: continue
                        s.pts.add(floatArrayOf(e.getX(i) + loc[0], e.getY(i) + loc[1], (now - t0).toFloat()))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = e.actionIndex
                    active.remove(e.getPointerId(i))?.end = now - t0
                }
                MotionEvent.ACTION_CANCEL -> {
                    active.values.forEach { it.end = now - t0 }
                    active.clear()
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0x55000000)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            for (s in strokes) {
                for (i in 1 until s.pts.size) {
                    val a = s.pts[i - 1]
                    val b = s.pts[i]
                    c.drawLine(a[0] - loc[0], a[1] - loc[1], b[0] - loc[0], b[1] - loc[1], dark)
                    c.drawLine(a[0] - loc[0], a[1] - loc[1], b[0] - loc[0], b[1] - loc[1], light)
                }
                val f = s.pts.firstOrNull() ?: continue
                c.drawCircle(f[0] - loc[0], f[1] - loc[1], 7f * d, dotDark)
                c.drawCircle(f[0] - loc[0], f[1] - loc[1], 5f * d, dot)
            }
        }

        fun build(): Gesture? {
            val (w, h) = Screen.size(context)
            val list = strokes.filter { it.pts.isNotEmpty() }
            if (list.isEmpty()) return null
            val now = SystemClock.uptimeMillis() - t0
            val out = list.map { s ->
                val end = if (s.end >= 0) s.end else now
                val pts = downsample(s.pts, 40).map {
                    Pt((it[0] / w).coerceIn(0f, 1f), (it[1] / h).coerceIn(0f, 1f))
                }
                Stroke(s.start, maxOf(50L, end - s.start), pts)
            }
            return Gesture(name = "", strokes = out)
        }

        private fun downsample(p: List<FloatArray>, max: Int): List<FloatArray> {
            if (p.size <= max) return p
            return (0 until max).map { p[(it.toLong() * (p.size - 1) / (max - 1)).toInt()] }
        }
    }
}
