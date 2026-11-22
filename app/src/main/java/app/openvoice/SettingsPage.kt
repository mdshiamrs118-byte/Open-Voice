package app.openvoice

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity

/** Settings tab: everything that used to sit on the main screen. */
class SettingsPage(private val a: MainActivity) {

    val view: ScrollView
    private val col: LinearLayout
    private val dim = 0xAAFFFFFF.toInt()

    private lateinit var a11yText: TextView
    private lateinit var micText: TextView
    private lateinit var writeText: TextView
    private lateinit var rootText: TextView
    private lateinit var shizukuText: TextView
    private lateinit var gridGroup: RadioGroup
    private lateinit var sysGroup: RadioGroup
    private val gridIds = LinkedHashMap<GridMode, Int>()
    private val sysIds = LinkedHashMap<Mode, Int>()
    private val sections = ArrayList<View>()
    private lateinit var segNormal: TextView
    private lateinit var segAi: TextView
    private lateinit var aiStatus: TextView
    private lateinit var sensGroup: RadioGroup
    private val sensIds = LinkedHashMap<Int, Int>()

    init {
        val (sv, c) = Ui.scrollColumn(a)
        view = sv
        col = c
        col.addView(Ui.label(a, "Settings", 28f, bold = true))

        // ---- setup ----
        section("Setup")
        a11yText = statusLine()
        col.addView(Ui.small(a, "Accessibility settings") {
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        micText = statusLine()
        col.addView(Ui.small(a, "Allow microphone") { a.askPermissions() })
        writeText = statusLine()
        col.addView(Ui.small(a, "Allow “Modify system settings”") {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {
            }
        })
        note("Brightness and auto-rotate need “Modify system settings”. On Android 13+, if the accessibility switch is greyed out: App info → ⋮ → Allow restricted settings.")
        col.addView(Ui.small(a, "Stop listening service") {
            ListenService.stop(a)
            a.refreshAll()
        })

        // ---- assistant mode ----
        section("Assistant mode")
        note("Normal understands the exact phrases listed in Commands. AI mode also understands everyday wording and can chain several tasks in one sentence (“open Chrome, search for cats and scroll down”), using a small on-device model. No chat, no cloud.")
        val seg = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xFF111111.toInt())
                setStroke(Ui.dp(a, 1), 0x66FFFFFF)
                cornerRadius = Ui.dp(a, 26).toFloat()
            }
            setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(a, 52)).apply { topMargin = Ui.dp(a, 12) }
        }
        segNormal = segButton("Normal") { setAiMode(false) }
        segAi = segButton("AI") { setAiMode(true) }
        seg.addView(segNormal, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        seg.addView(segAi, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        col.addView(seg)
        aiStatus = statusLine()
        col.addView(Ui.small(a, "Manage AI models") { a.startActivity(Intent(a, ModelsActivity::class.java)) })
        col.addView(Ui.label(a, "AI sensitivity", 13f, bold = true, top = 14))
        note("Strict only acts when it is sure. Loose guesses more.")
        sensGroup = RadioGroup(a)
        listOf("Strict", "Balanced", "Loose").forEachIndexed { i, name ->
            val rb = RadioButton(a).apply {
                text = name
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            sensIds[i] = rb.id
            sensGroup.addView(rb)
        }
        sensGroup.setOnCheckedChangeListener { _, id ->
            sensIds.entries.firstOrNull { it.value == id }?.key?.let { Prefs.setAiSensitivity(a, it) }
        }
        col.addView(sensGroup)
        switchRow("Show multi-step progress in the top bar", Prefs.KEY_AI_PLAN, true)

        // ---- display ----
        section("Display & feedback")
        switchRow("Top bar", Prefs.KEY_STATUS, true)
        switchRow("Top bar always visible (off = only while speaking)", Prefs.KEY_STATUS_ALWAYS, false)
        switchRow("Show taps & swipes", Prefs.KEY_FX, true)
        switchRow("Animations", Prefs.KEY_ANIM, true)

        // ---- grid ----
        section("Grid size")
        note("Say “show grid”, then a cell number. Change it by voice: “set grid big / medium / small”.")
        gridGroup = RadioGroup(a)
        for (m in GridMode.values()) {
            val rb = RadioButton(a).apply {
                text = "${m.label}: ${m.summary}"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            gridIds[m] = rb.id
            gridGroup.addView(rb)
        }
        gridGroup.setOnCheckedChangeListener { _, id ->
            gridIds.entries.firstOrNull { it.value == id }?.key?.let { Prefs.setGridMode(a, it) }
        }
        col.addView(gridGroup)

        // ---- system controls ----
        section("System controls")
        note("How Wi‑Fi, Bluetooth, brightness and other switches are changed. Normal taps the Quick Settings tile; Root and Shizuku send direct commands where one exists.")
        sysGroup = RadioGroup(a)
        val labels = mapOf(
            Mode.NORMAL to "Normal (Quick Settings tiles)",
            Mode.ROOT to "Root (direct commands)",
            Mode.SHIZUKU to "Shizuku (direct commands)"
        )
        for (m in Mode.values()) {
            val rb = RadioButton(a).apply {
                text = labels[m]
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            sysIds[m] = rb.id
            sysGroup.addView(rb)
        }
        sysGroup.setOnCheckedChangeListener { _, id ->
            sysIds.entries.firstOrNull { it.value == id }?.key?.let { Prefs.setSysMode(a, it) }
        }
        col.addView(sysGroup)

        // ---- root / shizuku ----
        section("Root & Shizuku")
        rootText = statusLine()
        col.addView(Ui.small(a, "Test root access") {
            Toast.makeText(a, "Asking for root…", Toast.LENGTH_SHORT).show()
            Shell.testRootAsync { ok ->
                Toast.makeText(a, if (ok) "Root granted" else "Root not available", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })
        shizukuText = statusLine()
        col.addView(Ui.small(a, "Grant Shizuku permission") { a.grantShizuku() })

        // ---- triggers ----
        section("Triggers")
        switchRow("Allow other apps to trigger commands", Prefs.KEY_EXTERNAL, false)
        note("Lets Tasker or adb run a custom command: am start -a app.openvoice.action.RUN_COMMAND --es name \"My command\"")

        // ---- about ----
        section("About")
        val ver = try {
            a.packageManager.getPackageInfo(a.packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        col.addView(Ui.label(a, "Open Voice $ver · open source (MIT)", 13f, top = 4, color = dim))

        refresh()
    }

    private fun segButton(text: String, onClick: () -> Unit) = TextView(a).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setOnClickListener { onClick() }
        Anim.press(this)
    }

    private fun styleSeg() {
        val ai = Prefs.aiMode(a)
        fun style(v: TextView, sel: Boolean) {
            v.setTextColor(if (sel) Color.BLACK else 0xAAFFFFFF.toInt())
            v.background = if (sel) GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = Ui.dp(a, 22).toFloat()
            } else null
        }
        style(segNormal, !ai)
        style(segAi, ai)
    }

    fun setAiMode(on: Boolean) {
        if (on && !AiEngine.ready(a)) {
            Toast.makeText(a, "Download an AI model first", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(a, ModelsActivity::class.java))
            styleSeg()
            return
        }
        Prefs.set(a, Prefs.KEY_AI_MODE, on)
        if (on) AiEngine.preload(a)
        styleSeg()
        a.refreshAll()
    }

    private fun section(title: String) {
        val h = Ui.label(a, title.uppercase(), 12f, bold = true, top = 28, color = dim)
        col.addView(h)
        sections.add(h)
    }

    private fun note(text: String) {
        col.addView(Ui.label(a, text, 12f, top = 6, color = 0x88FFFFFF.toInt()))
    }

    private fun statusLine(): TextView {
        val t = Ui.label(a, "", 15f, top = 12)
        col.addView(t)
        return t
    }

    private fun switchRow(title: String, key: String, def: Boolean) {
        val sw = Switch(a).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            Ui.styleSwitch(this)
            isChecked = Prefs.get(a, key, def)
            setOnCheckedChangeListener { _, on ->
                Prefs.set(a, key, on)
                val svc = OpenVoiceService.instance
                when (key) {
                    Prefs.KEY_STATUS -> if (!on) svc?.hideStatus() else svc?.settle()
                    Prefs.KEY_STATUS_ALWAYS -> svc?.settle()
                }
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(a, 14) }
        }
        col.addView(sw)
    }

    fun refresh() {
        a11yText.text = if (OpenVoiceService.instance != null) "● Accessibility service: on" else "○ Accessibility service: off"
        micText.text = if (a.hasMic()) "● Microphone: allowed" else "○ Microphone: not allowed"
        writeText.text = if (Settings.System.canWrite(a)) "● Modify system settings: allowed" else "○ Modify system settings: not allowed"
        rootText.text = "Root: ${Shell.rootStatus()}"
        shizukuText.text = "Shizuku: ${Shell.shizukuStatus()}"
        styleSeg()
        val m = AiEngine.activeModel(a)
        aiStatus.text = if (m == null) "No AI model installed" else "Model: ${m.title} · ${AiEngine.statusText}"
        sensIds[Prefs.aiSensitivity(a)]?.let { if (sensGroup.checkedRadioButtonId != it) sensGroup.check(it) }
        gridIds[Prefs.gridMode(a)]?.let { if (gridGroup.checkedRadioButtonId != it) gridGroup.check(it) }
        sysIds[Prefs.sysMode(a)]?.let { if (sysGroup.checkedRadioButtonId != it) sysGroup.check(it) }
    }

    fun onShow() {
        refresh()
        val n = col.childCount
        for (i in 0 until minOf(n, 14)) Anim.appear(col.getChildAt(i), i * 28L, 12)
    }
}
