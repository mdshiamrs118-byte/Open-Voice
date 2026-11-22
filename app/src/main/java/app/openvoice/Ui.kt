package app.openvoice

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers so every screen shares the same black-and-white look. */
object Ui {

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** Sets up a black scrolling page and returns its column. */
    fun screen(a: Activity): LinearLayout {
        a.window.statusBarColor = Color.BLACK
        a.window.navigationBarColor = Color.BLACK
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 24), dp(a, 40), dp(a, 24), dp(a, 40))
        }
        a.setContentView(ScrollView(a).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
        })
        return col
    }

    fun label(c: Context, text: String, sp: Float = 15f, bold: Boolean = false, top: Int = 0, color: Int = Color.WHITE) =
        TextView(c).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(c, top) }
        }

    fun button(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(c, 28).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(c, 56)).apply { topMargin = dp(c, 12) }
    }

    /** Small outlined button for rows. */
    fun small(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(c, 14), 0, dp(c, 14), 0)
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(c, 1), Color.WHITE)
            cornerRadius = dp(c, 18).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(c, 38)).apply {
            topMargin = dp(c, 8)
            marginEnd = dp(c, 8)
        }
    }

    fun row(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        background = GradientDrawable().apply {
            setColor(0xFF0D0D0D.toInt())
            setStroke(dp(c, 1), 0x66FFFFFF)
            cornerRadius = dp(c, 14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(c, 10) }
    }

    fun edit(c: Context, hint: String, text: String = "", multiline: Boolean = false, number: Boolean = false) =
        EditText(c).apply {
            this.hint = hint
            setText(text)
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            textSize = 15f
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
            background = GradientDrawable().apply {
                setColor(0xFF111111.toInt())
                setStroke(dp(c, 1), 0x66FFFFFF)
                cornerRadius = dp(c, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(c, 6) }
        }
}
