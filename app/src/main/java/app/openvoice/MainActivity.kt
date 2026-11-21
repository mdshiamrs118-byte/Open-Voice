package app.openvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var a11yText: TextView
    private lateinit var micText: TextView
    private lateinit var heardText: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sp: Float, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(28).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        col.addView(label("Open Voice", 32f, bold = true))
        col.addView(label("Control your phone with your voice.", 15f, top = 4))

        a11yText = label("", 15f, top = 28)
        col.addView(a11yText)
        col.addView(button("1 · Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        micText = label("", 15f, top = 20)
        col.addView(micText)
        col.addView(button("2 · Grant microphone") { askPermissions() })

        col.addView(label("", 15f, top = 20).apply { text = "Voice control" })
        toggle = button("3 · Start listening") { toggleListening() }
        col.addView(toggle)

        heardText = label("Last command: —", 14f, top = 20)
        col.addView(heardText)

        col.addView(
            label(
                "COMMANDS\n\n" +
                    "show numbers · hide numbers\n" +
                    "tap 5 · long press 5\n" +
                    "click <text on screen>\n" +
                    "scroll down · scroll up\n" +
                    "swipe left · swipe right\n" +
                    "go back · go home · recents\n" +
                    "notifications · quick settings\n" +
                    "open <app name>\n" +
                    "type <text>\n" +
                    "start dictation · stop dictation\n" +
                    "clear text · enter\n" +
                    "stop listening",
                14f, top = 32
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        ListenService.onHeard = { msg -> runOnUiThread { heardText.text = "Last command: $msg" } }
        refresh()
    }

    override fun onPause() {
        ListenService.onHeard = null
        super.onPause()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        a11yText.text = if (OpenVoiceService.instance != null) "● Accessibility service: on"
        else "○ Accessibility service: off"
        micText.text = if (hasMic()) "● Microphone: granted" else "○ Microphone: not granted"
        toggle.text = if (ListenService.running) "3 · Stop listening" else "3 · Start listening"
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun toggleListening() {
        if (ListenService.running) {
            ListenService.stop(this)
        } else {
            if (!hasMic()) {
                askPermissions()
                return
            }
            ListenService.start(this)
        }
        ui.postDelayed({ refresh() }, 400)
    }
}
