package app.openvoice

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/** Which brain AI mode uses, and how much it may see and do. */
class AiSettingsActivity : Activity() {

    private val dim = 0xAAFFFFFF.toInt()
    private lateinit var sourceGroup: RadioGroup
    private val ids = LinkedHashMap<String, Int>()
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "AI engine", 28f, bold = true))
        col.addView(Ui.label(this, "Pick what understands your requests in AI mode. If the chosen one fails, Open Voice falls back to the local model.", 13f, top = 6, color = dim))

        sourceGroup = RadioGroup(this)
        val items = listOf(
            "local" to "Local · small on-device model (private, offline)",
            "api" to "API · your OpenAI-compatible keys (smartest)",
            "web" to "Web AI · ChatGPT, Gemini, DuckDuckGo, DeepSeek, Qwen… in a hidden browser"
        )
        for ((k, t) in items) {
            val rb = RadioButton(this).apply {
                text = t
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            ids[k] = rb.id
            sourceGroup.addView(rb)
        }
        sourceGroup.check(ids[Prefs.aiSource(this)] ?: ids.getValue("local"))
        sourceGroup.setOnCheckedChangeListener { _, id ->
            ids.entries.firstOrNull { it.value == id }?.key?.let { Prefs.setAiSource(this, it) }
            refresh()
        }
        col.addView(sourceGroup)

        info = Ui.label(this, "", 13f, top = 12)
        col.addView(info)

        col.addView(Ui.button(this, "Local models") { startActivity(Intent(this, ModelsActivity::class.java)) })
        col.addView(Ui.button(this, "API keys") { startActivity(Intent(this, ApiKeysActivity::class.java)) })
        col.addView(Ui.button(this, "Web AI") { startActivity(Intent(this, WebAiActivity::class.java)) })

        col.addView(Ui.label(this, "PRIVACY", 12f, bold = true, top = 28, color = dim))
        switchRow(col, "Handle simple commands locally first (faster, nothing is sent)", Prefs.KEY_AI_RULES_FIRST, true)
        switchRow(col, "Share installed app names with online AI", Prefs.KEY_AI_SHARE_APPS, true)
        switchRow(col, "Share visible screen text with online AI", Prefs.KEY_AI_SHARE_SCREEN, false)
        col.addView(Ui.label(this, "Online AI only sees your spoken request plus what you allow here. The local model never leaves the phone.", 12f, top = 6, color = 0x88FFFFFF.toInt()))

        col.addView(Ui.label(this, "POWER", 12f, bold = true, top = 28, color = dim))
        switchRow(col, "Let online AI use Shizuku / Root directly", Prefs.KEY_AI_SHELL, false)
        col.addView(Ui.label(this, "Off: the AI can only use normal accessibility actions. On: it may also send taps, keys and shell commands as shell input. Dangerous commands (deleting, uninstalling, wiping…) are always blocked.", 12f, top = 6, color = 0x88FFFFFF.toInt()))
        col.addView(Ui.label(this, "Run AI shell commands with", 13f, bold = true, top = 12))
        val modeGroup = RadioGroup(this)
        val mids = LinkedHashMap<Mode, Int>()
        for (m in listOf(Mode.SHIZUKU, Mode.ROOT)) {
            val rb = RadioButton(this).apply {
                text = m.label
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            mids[m] = rb.id
            modeGroup.addView(rb)
        }
        modeGroup.check(mids.getValue(Prefs.aiShellMode(this).let { if (it == Mode.ROOT) Mode.ROOT else Mode.SHIZUKU }))
        modeGroup.setOnCheckedChangeListener { _, id ->
            mids.entries.firstOrNull { it.value == id }?.key?.let { Prefs.setAiShellMode(this, it) }
        }
        col.addView(modeGroup)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        info.text = when (Prefs.aiSource(this)) {
            "api" -> {
                val keys = ApiStore.keys(this)
                val usable = keys.count { ApiStore.usable(it) }
                if (keys.isEmpty()) "No API keys yet. Add one below."
                else "$usable of ${keys.size} key(s) ready" + if (usable == 0) " · all exhausted, using the local model" else ""
            }
            "web" -> {
                val s = WebSites.find(this, Prefs.webAiId(this))
                if (s == null) "No Web AI chosen yet. Choose one and log in below." else "Using ${s.name}"
            }
            else -> AiEngine.activeModel(this)?.let { "Model: ${it.title} · ${AiEngine.statusText}" } ?: "No local model installed yet."
        }
    }

    private fun switchRow(col: LinearLayout, title: String, key: String, def: Boolean) {
        val sw = Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            Ui.styleSwitch(this)
            isChecked = Prefs.get(this@AiSettingsActivity, key, def)
            setOnCheckedChangeListener { _, on -> Prefs.set(this@AiSettingsActivity, key, on) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@AiSettingsActivity, 14) }
        }
        col.addView(sw)
    }
}
