package app.openvoice

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object Screen {
    /** Full physical screen size in pixels (what gestures and shell `input` use). */
    fun size(ctx: Context): Pair<Int, Int> {
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
        } catch (e: Exception) {
            val m = ctx.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
    }
}
