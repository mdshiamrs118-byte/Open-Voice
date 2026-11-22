package app.openvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** The big microphone orb on the Home tab. Breathes and sends out rings while listening, reacts to your voice. */
class OrbView(context: Context) : View(context) {

    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private var level = 0f
    private var target = 0f
    private val d = resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    /** [rmsDb] comes from the speech recognizer (roughly -2..10). */
    fun pushLevel(rmsDb: Float) {
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        invalidate()
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        glyph.color = color
        glyph.style = Paint.Style.FILL
        oval.set(cx - 0.28f * u, cy - 0.78f * u, cx + 0.28f * u, cy + 0.12f * u)
        c.drawRoundRect(oval, 0.28f * u, 0.28f * u, glyph)
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = 0.13f * u
        oval.set(cx - 0.55f * u, cy - 0.5f * u, cx + 0.55f * u, cy + 0.62f * u)
        c.drawArc(oval, 0f, 180f, false, glyph)
        c.drawLine(cx, cy + 0.62f * u, cx, cy + 0.95f * u, glyph)
        c.drawLine(cx - 0.3f * u, cy + 0.95f * u, cx + 0.3f * u, cy + 0.95f * u, glyph)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        level += (target - level) * 0.18f
        target *= 0.93f

        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.24f
        val animate = Anim.on(context)

        if (active) {
            if (animate) {
                for (i in 0..2) {
                    val t = ((now % 3200L) / 3200f + i / 3f) % 1f
                    val r = base * (1.12f + t * 0.95f + level * 0.55f)
                    ring.alpha = ((1f - t) * 120).toInt()
                    c.drawCircle(cx, cy, r, ring)
                }
            }
            val breathe = if (animate) 1f + 0.035f * sin(now / 550.0).toFloat() + level * 0.15f else 1f
            c.drawCircle(cx, cy, base * breathe, fill)
            drawMic(c, cx, cy, base * breathe * 0.5f, Color.BLACK)
        } else {
            c.drawCircle(cx, cy, base, dim)
            c.drawCircle(cx, cy, base, outline)
            drawMic(c, cx, cy, base * 0.5f, 0xCCFFFFFF.toInt())
        }
        if ((active && animate) || level > 0.01f) postInvalidateOnAnimation()
    }
}
