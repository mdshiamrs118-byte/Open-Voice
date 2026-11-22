package app.openvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var a11yText: TextView
    private lateinit var micText: TextView
    private lateinit var heardText: TextView
    private lateinit var rootText: TextView
    private lateinit var shizukuText: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }

    private fun switchRow(title: String, key: String, def: Boolean = true) = Switch(this).apply {
        text = title
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = Prefs.get(this@MainActivity, key, def)
        setOnCheckedChangeListener { _, on ->
            Prefs.set(this@MainActivity, key, on)
            if (key == Prefs.KEY_STATUS && !on) OpenVoiceService.instance?.hideStatus()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@MainActivity, 14) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)

        col.addView(Ui.label(this, "Open Voice", 32f, bold = true))
        col.addView(Ui.label(this, "Control your phone with your voice.", 15f, top = 4))

        // --- setup ---
        a11yText = Ui.label(this, "", 15f, top = 28)
        col.addView(a11yText)
        col.addView(Ui.button(this, "1 · Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        micText = Ui.label(this, "", 15f, top = 20)
        col.addView(micText)
        col.addView(Ui.button(this, "2 · Grant microphone") { askPermissions() })
        col.addView(Ui.label(this, "Voice control", 15f, top = 20))
        toggle = Ui.button(this, "3 · Start listening") { toggleListening() }
        col.addView(toggle)
        heardText = Ui.label(this, "Last command: —", 14f, top = 20)
        col.addView(heardText)

        // --- custom ---
        col.addView(Ui.label(this, "Make it yours", 20f, bold = true, top = 32))
        col.addView(Ui.button(this, "Custom commands") {
            startActivity(Intent(this, CommandsActivity::class.java))
        })
        col.addView(Ui.button(this, "Gestures") {
            startActivity(Intent(this, GesturesActivity::class.java))
        })

        // --- modes ---
        col.addView(Ui.label(this, "Execution modes", 20f, bold = true, top = 32))
        col.addView(
            Ui.label(
                this,
                "Each custom command can run in Normal mode (accessibility), as Root, or through Shizuku.",
                13f, top = 4, color = 0xAAFFFFFF.toInt()
            )
        )
        rootText = Ui.label(this, "", 15f, top = 14)
        col.addView(rootText)
        col.addView(Ui.button(this, "Test root access") {
            Toast.makeText(this, "Asking for root…", Toast.LENGTH_SHORT).show()
            Shell.testRootAsync { ok ->
                Toast.makeText(this, if (ok) "Root granted" else "Root not available", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })
        shizukuText = Ui.label(this, "", 15f, top = 14)
        col.addView(shizukuText)
        col.addView(Ui.button(this, "Grant Shizuku permission") { grantShizuku() })

        // --- overlays / triggers ---
        col.addView(Ui.label(this, "Options", 20f, bold = true, top = 32))
        col.addView(switchRow("Top bar: show what I said", Prefs.KEY_STATUS))
        col.addView(switchRow("Show taps & swipes", Prefs.KEY_FX))
        col.addView(switchRow("Allow other apps to trigger commands", Prefs.KEY_EXTERNAL, false))

        col.addView(
            Ui.label(
                this,
                "COMMANDS\n\n" +
                    "SEE & TAP\n" +
                    "show numbers · hide numbers\n" +
                    "tap 5 · double tap 5 · long press 5\n" +
                    "click <text on screen>\n" +
                    "show grid → say 1-9 to zoom → tap / double tap / hold\n" +
                    "cancel (hides numbers and grid)\n\n" +
                    "MOVE\n" +
                    "scroll down · up · left · right\n" +
                    "scroll to top · scroll to bottom\n" +
                    "swipe left · right · up · down · refresh\n\n" +
                    "NAVIGATE\n" +
                    "go back · go home · recents\n" +
                    "notifications · quick settings\n" +
                    "screenshot · lock screen · power menu · split screen\n" +
                    "open <app name>\n\n" +
                    "TEXT\n" +
                    "type <text> · start dictation · stop dictation\n" +
                    "select all · copy · paste · cut\n" +
                    "delete · delete word · clear text · new line · enter\n\n" +
                    "DEVICE\n" +
                    "volume up · volume down · mute · unmute\n\n" +
                    "CONTROL\n" +
                    "run <custom command name> · stop macro\n" +
                    "pause listening · resume listening\n" +
                    "show commands · stop listening",
                14f, top = 32
            )
        )

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
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
        rootText.text = "Root: ${Shell.rootStatus()}"
        shizukuText.text = "Shizuku: ${Shell.shizukuStatus()}"
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

    private fun grantShizuku() {
        try {
            when {
                !Shizuku.pingBinder() ->
                    Toast.makeText(this, "Shizuku isn't running. Open the Shizuku app and start it first.", Toast.LENGTH_LONG).show()
                Shizuku.isPreV11() ->
                    Toast.makeText(this, "Please update Shizuku.", Toast.LENGTH_LONG).show()
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    Toast.makeText(this, "Shizuku permission already granted", Toast.LENGTH_SHORT).show()
                else -> Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku isn't available on this device", Toast.LENGTH_LONG).show()
        }
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
