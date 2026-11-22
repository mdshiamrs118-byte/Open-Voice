package app.openvoice

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast

class CommandsActivity : Activity() {

    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "Custom commands", 28f, bold = true))
        col.addView(
            Ui.label(
                this,
                "Say a phrase and Open Voice runs a list of actions: taps, gestures, waits, app launches, shell commands and more.",
                14f, top = 6
            )
        )
        col.addView(Ui.button(this, "+ New command") {
            startActivity(Intent(this, CommandEditorActivity::class.java))
        })

        val tools = Ui.row(this)
        tools.addView(Ui.small(this, "Add examples") { addExamples() })
        tools.addView(Ui.small(this, "Export") { export() })
        tools.addView(Ui.small(this, "Import") { importFromClipboard() })
        col.addView(tools)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val cmds = Store.commands(this)
        if (cmds.isEmpty()) {
            listBox.addView(Ui.label(this, "No custom commands yet.", 14f, top = 20))
            return
        }
        for (c in cmds) {
            val card = Ui.card(this)
            val phrases = if (c.phrases.isEmpty()) "shortcut only" else c.phrases.joinToString(" · ") { "“$it”" }
            card.addView(Ui.label(this, c.name + if (c.enabled) "" else "  (off)", 17f, bold = true))
            card.addView(Ui.label(this, phrases, 13f, top = 4))
            card.addView(
                Ui.label(
                    this,
                    "${c.mode.label} · ${c.actions.size} action(s)" + if (c.repeat > 1) " · ×${c.repeat}" else "",
                    12f, top = 4, color = 0xAAFFFFFF.toInt()
                )
            )
            card.setOnClickListener {
                startActivity(Intent(this, CommandEditorActivity::class.java).putExtra("id", c.id))
            }
            listBox.addView(card)
        }
    }

    private fun addExamples() {
        fun cmd(name: String, phrases: List<String>, actions: List<Action>, repeat: Int = 1) {
            if (Store.commands(this).any { it.name == name }) return
            Store.upsertCommand(
                this,
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
        render()
        Toast.makeText(this, "Examples added", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Open Voice commands", Store.exportJson(this)))
        Toast.makeText(this, "Commands and gestures copied to clipboard", Toast.LENGTH_LONG).show()
    }

    private fun importFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val n = Store.importJson(this, text)
        if (n < 0) Toast.makeText(this, "Clipboard doesn't contain Open Voice commands", Toast.LENGTH_LONG).show()
        else {
            Toast.makeText(this, "Imported $n command(s)", Toast.LENGTH_SHORT).show()
            render()
        }
    }
}
