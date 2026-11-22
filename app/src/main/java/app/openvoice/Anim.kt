package app.openvoice

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small animation helpers. Everything respects the "Animations" switch in Settings. */
object Anim {

    fun on(c: Context) = Prefs.animations(c)

    /** Fades and slides a view up into place. */
    fun appear(v: View, delay: Long = 0, dy: Int = 18) {
        val c = v.context
        v.animate().cancel()
        if (!on(c)) {
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        v.alpha = 0f
        v.translationY = dy * c.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Springy scale-in, used for the orb and cards. */
    fun pop(v: View, delay: Long = 0) {
        v.animate().cancel()
        if (!on(v.context)) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 1f
            return
        }
        v.scaleX = 0.7f
        v.scaleY = 0.7f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(520)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Presses in on touch and springs back. */
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            if (on(view.context)) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
            false
        }
    }
}
