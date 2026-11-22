package app.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** Small pill at the top of the screen showing what Open Voice is hearing / doing. */
class StatusOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var tv: TextView? = null

    private fun dp(v: Float) = (v * service.resources.displayMetrics.density).toInt()

    fun show(text: String) {
        val v = tv ?: create()
        v.text = text
    }

    fun hide(animate: Boolean = true) {
        val v = tv ?: return
        tv = null
        if (animate && Anim.on(service)) {
            v.animate().cancel()
            v.animate().alpha(0f).translationY(-dp(10f).toFloat()).setDuration(170)
                .withEndAction { remove(v) }.start()
        } else {
            remove(v)
        }
    }

    private fun remove(v: TextView) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun statusBarHeight(): Int {
        val res = service.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else dp(24f)
    }

    private fun create(): TextView {
        val v = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (service.resources.displayMetrics.widthPixels * 0.9f).toInt()
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                setStroke(dp(1.5f), Color.WHITE)
                cornerRadius = dp(20f).toFloat()
            }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = statusBarHeight() + dp(6f)
        wm.addView(v, lp)
        tv = v
        if (Anim.on(service)) {
            v.alpha = 0f
            v.translationY = -dp(18f).toFloat()
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(300).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
        return v
    }
}
