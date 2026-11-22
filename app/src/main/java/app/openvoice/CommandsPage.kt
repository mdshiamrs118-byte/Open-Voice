package app.openvoice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/** Commands tab: custom commands and every built-in command, each with an on/off switch. */
class CommandsPage(private val a: MainActivity) {

    val view: ScrollView
    private val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var query = ""
    private val dim = 0xAAFFFFFF.toInt()

    init {
        val (sv, col) = Ui.scrollColumn(a)
        view = sv
        col.addView(Ui.label(a, "Commands", 28f, bold = true))
        col.addView(Ui.label(a, "Your own commands and everything built in. Switch any of them off.", 14f, top = 4, color = dim))

        val search = Ui.edit(a, "Search commands")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                render(false)
            }
        })
        col.addView(search)

        col.addView(Ui.button(a, "+ New command") {
            a.startActivity(Intent(a, CommandEditorActivity::class.java))
        })
        val tools = Ui.row(a)
        tools.addView(Ui.small(a, "Gestures") { a.startActivity(Intent(a, GesturesActivity::class.java)) })
        tools.addView(Ui.small(a, "Add examples") { addExamples() })
        tools.addView(Ui.small(a, "Export") { export() })
        tools.addView(Ui.small(a, "Import") { importFromClipboard() })
        col.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        })

        col.addView(listBox)
        render(false)
    }

    fun onShow() = render(true)

    private fun header(text: String, count: Int): android.widget.TextView =
        Ui.label(a, "${text.uppercase()}  ·  $count", 12f, bold = true, top = 24, color = dim)

    private fun row(
        title: String,
        sub: String,
        meta: String?,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
        onClick: (() -> Unit)?
    ): LinearLayout {
        val card = Ui.card(a)
        val line = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        left.addView(Ui.label(a, title, 16f, bold = true))
        if (sub.isNotEmpty()) left.addView(Ui.label(a, sub, 12.5f, top = 3, color = dim))
        if (meta != null) left.addView(Ui.label(a, meta, 11f, top = 3, color = 0x77FFFFFF))
        val sw = Switch(a).apply {
            isChecked = checked
            Ui.styleSwitch(this)
            setOnCheckedChangeListener { _, on ->
                onToggle(on)
                card.animate().alpha(if (on) 1f else 0.55f).setDuration(200).start()
            }
        }
        card.alpha = if (checked) 1f else 0.55f
        line.addView(left)
        line.addView(sw)
        card.addView(line)
        if (onClick != null) {
            left.setOnClickListener { onClick() }
            Anim.press(left)
        }
        return card
    }

    fun render(animate: Boolean) {
        listBox.removeAllViews()
        val q = query
        var n = 0L
        fun add(v: android.view.View) {
            listBox.addView(v)
            if (animate && n < 14) Anim.appear(v, n * 35, 14)
            n++
        }

        // ---- custom ----
        val customs = Store.commands(a).filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.phrases.any { p -> p.lowercase().contains(q) }
        }
        listBox.addView(header("Custom", customs.size))
        if (customs.isEmpty()) {
            listBox.addView(
                Ui.label(
                    a,
                    if (q.isEmpty()) "None yet. Tap “+ New command” or “Add examples”." else "No custom commands match.",
                    13f, top = 8, color = dim
                )
            )
        }
        for (c in customs) {
            val phrases = if (c.phrases.isEmpty()) "shortcut only" else c.phrases.joinToString(" · ") { "“$it”" }
            add(
                row(
                    c.name, phrases,
                    "${c.mode.label} · ${c.actions.size} action(s)" + if (c.repeat > 1) " · ×${c.repeat}" else "",
                    c.enabled,
                    { on ->
                        c.enabled = on
                        Store.saveCommands(a)
                    },
                    { a.startActivity(Intent(a, CommandEditorActivity::class.java).putExtra("id", c.id)) }
                )
            )
        }

        // ---- built-in ----
        val disabled = Prefs.disabledBuiltIns(a)
        for (cat in BuiltIns.categories) {
            val items = BuiltIns.groups.filter {
                it.category == cat &&
                    (q.isEmpty() || it.title.lowercase().contains(q) || it.say.lowercase().contains(q))
            }
            if (items.isEmpty()) continue
            listBox.addView(header(cat, items.size))
            for (g in items) {
                add(
                    row(
                        g.title, "say: ${g.say}", null,
                        g.id !in disabled,
                        { on -> Prefs.setBuiltInEnabled(a, g.id, on) },
                        null
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------

    private fun addExamples() {
        fun cmd(name: String, phrases: List<String>, actions: List<Action>, repeat: Int = 1) {
            if (Store.commands(a).any { it.name == name }) return
            Store.upsertCommand(
                a,
                CustomCommand(name = name, phrases = phrases.toMutableList(), actions = actions.toMutableList(), repeat = repeat)
            )
        }
        cmd(
            "Read feed", listOf("read feed"),
            listOf(Action("scroll", mapOf("direction" to "down")), Action("wait", mapOf("seconds" to "2.5"))),
            repeat = 10
        )
        cmd(
            "Web search", listOf("search for {text}"),
            listOf(Action("open_uri", mapOf("uri" to "https://www.google.com/search?q={text}")))
        )
        cmd(
            "Screenshot then home", listOf("capture screen"),
            listOf(
                Action("key", mapOf("key" to "screenshot")),
                Action("wait", mapOf("seconds" to "1.5")),
                Action("key", mapOf("key" to "home"))
            )
        )
        cmd(
            "Open app or search", listOf("launch {text}"),
            listOf(
                Action("if", mapOf("check" to "app_installed", "right" to "{text}")),
                Action("open_app", mapOf("name" to "{text}")),
                Action("else"),
                Action("open_uri", mapOf("uri" to "https://www.google.com/search?q={text}")),
                Action("end_if")
            )
        )
        cmd(
            "Battery check", listOf("battery check"),
            listOf(
                Action("if", mapOf("check" to "battery_level", "op" to "lt", "right" to "20")),
                Action("toast", mapOf("message" to "Battery low: {battery}%")),
                Action("vibrate", mapOf("ms" to "300")),
                Action("else"),
                Action("toast", mapOf("message" to "Battery {battery}%")),
                Action("end_if")
            )
        )
        cmd(
            "Scroll until text", listOf("scroll to {text}"),
            listOf(
                Action("repeat", mapOf("count" to "15")),
                Action("if", mapOf("check" to "text_on_screen", "right" to "{text}")),
                Action("stop", mapOf("message" to "Found “{text}”")),
                Action("end_if"),
                Action("scroll", mapOf("direction" to "down")),
                Action("wait", mapOf("seconds" to "0.8")),
                Action("end_loop")
            )
        )
        render(true)
        Toast.makeText(a, "Examples added", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        val cm = a.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Open Voice commands", Store.exportJson(a)))
        Toast.makeText(a, "Commands and gestures copied to clipboard", Toast.LENGTH_LONG).show()
    }

    private fun importFromClipboard() {
        val cm = a.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString().orEmpty()
        val n = Store.importJson(a, text)
        if (n < 0) {
            Toast.makeText(a, "Clipboard doesn't contain Open Voice commands", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(a, "Imported $n command(s)", Toast.LENGTH_SHORT).show()
            render(true)
        }
    }
}
