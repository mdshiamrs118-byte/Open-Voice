package app.openvoice

import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HomePage(private val a: MainActivity) {

    val view: ScrollView
    val orb = OrbView(a)

    private val status: TextView
    private val hint: TextView
    private val lastCard: LinearLayout
    private val lastText: TextView
    private val setupBox: LinearLayout
    private val modeChip: TextView
    private var setupKey = ""
    private val dim = 0xAAFFFFFF.toInt()

    init {
        val (sv, col) = Ui.scrollColumn(a)
        view = sv
        col.gravity = Gravity.CENTER_HORIZONTAL

        col.addView(Ui.label(a, "Open Voice", 28f, bold = true).apply { gravity = Gravity.CENTER })

        col.addView(orb, LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(a, 270)).apply { topMargin = Ui.dp(a, 8) })
        orb.setOnClickListener { a.toggleListening() }
        Anim.press(orb)

        status = Ui.label(a, "", 22f, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(status)
        hint = Ui.label(a, "", 13f, top = 6, color = dim).apply { gravity = Gravity.CENTER }
        col.addView(hint)

        modeChip = Ui.label(a, "", 13f, bold = true, top = 14).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111111.toInt())
                setStroke(Ui.dp(a, 1), 0x66FFFFFF)
                cornerRadius = Ui.dp(a, 20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = Ui.dp(a, 14)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { a.toggleAiMode() }
            Anim.press(this)
        }
        col.addView(modeChip)

        setupBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupBox)

        lastCard = Ui.card(a).apply { visibility = android.view.View.GONE }
        lastCard.addView(Ui.label(a, "LAST COMMAND", 11f, color = dim))
        lastText = Ui.label(a, "", 15f, top = 4)
        lastCard.addView(lastText)
        col.addView(lastCard)
    }

    fun showHeard(msg: String) {
        lastText.text = msg
        if (lastCard.visibility != android.view.View.VISIBLE) lastCard.visibility = android.view.View.VISIBLE
        Anim.appear(lastCard, 0, 14)
    }

    fun refresh() {
        val running = ListenService.running
        val listening = ListenService.listening
        orb.active = listening
        status.text = when {
            !running -> "Tap to start"
            listening -> "Listening"
            else -> "Listening is off"
        }
        hint.text = when {
            !running -> "Then say “show numbers”, “scroll down” or “open Chrome”"
            listening -> "Tap the orb to turn off · the notification has a button too"
            else -> "Tap the orb to turn listening back on"
        }

        val aiOn = Prefs.aiMode(a) && AiEngine.ready(a)
        modeChip.text = if (aiOn) "✦ AI mode · ${AiEngine.activeModel(a)?.title ?: ""}" else "Normal mode · tap to switch"

        val needA11y = OpenVoiceService.instance == null
        val needMic = !a.hasMic()
        val key = "$needA11y/$needMic"
        if (key != setupKey) {
            setupKey = key
            setupBox.removeAllViews()
            var i = 0L
            if (needA11y) {
                val c = card(
                    "Turn on the accessibility service",
                    "Open Voice needs it to tap, scroll and type for you. If the switch is greyed out: App info → ⋮ → Allow restricted settings.",
                    "Open settings"
                ) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                setupBox.addView(c)
                Anim.appear(c, i * 90)
                i++
            }
            if (needMic) {
                val c = card("Allow the microphone", "So it can hear your commands.", "Allow") { a.askPermissions() }
                setupBox.addView(c)
                Anim.appear(c, i * 90)
            }
        }
    }

    fun onShow() {
        refresh()
        Anim.pop(orb)
        Anim.appear(status, 120, 12)
        Anim.appear(hint, 180, 12)
        if (lastCard.visibility == android.view.View.VISIBLE) Anim.appear(lastCard, 240, 12)
    }

    private fun card(title: String, body: String, button: String, onClick: () -> Unit): LinearLayout {
        val c = Ui.card(a)
        c.addView(Ui.label(a, title, 15f, bold = true))
        c.addView(Ui.label(a, body, 12f, top = 4, color = dim))
        c.addView(Ui.small(a, button, onClick))
        return c
    }
}
