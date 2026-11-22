package app.openvoice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast

class CommandEditorActivity : Activity() {

    private lateinit var cmd: CustomCommand
    private var isNew = true

    private lateinit var nameEdit: android.widget.EditText
    private lateinit var phrasesEdit: android.widget.EditText
    private lateinit var repeatEdit: android.widget.EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var enabledSw: Switch
    private lateinit var contSw: Switch
    private lateinit var actionsBox: LinearLayout
    private val modeIds = LinkedHashMap<Mode, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra("id")?.let { Store.findCommand(this, it) }
        isNew = existing == null
        cmd = existing?.copyDeep() ?: CustomCommand()

        val col = Ui.screen(this)
        col.addView(Ui.label(this, if (isNew) "New command" else "Edit command", 28f, bold = true))

        col.addView(Ui.label(this, "Name", 13f, top = 18))
        nameEdit = Ui.edit(this, "e.g. Read feed", cmd.name)
        col.addView(nameEdit)

        col.addView(Ui.label(this, "Trigger phrases (one per line)", 13f, top = 14))
        phrasesEdit = Ui.edit(this, "read feed\nsearch for {text}", cmd.phrases.joinToString("\n"), multiline = true)
        col.addView(phrasesEdit)
        col.addView(
            Ui.label(
                this,
                "End a phrase with {text} to capture what you say after it. Leave empty to trigger only from a shortcut. Custom phrases override built-in ones.",
                12f, top = 4, color = 0xAAFFFFFF.toInt()
            )
        )

        col.addView(Ui.label(this, "Run with", 13f, top = 18))
        modeGroup = RadioGroup(this)
        for (m in Mode.values()) {
            val rb = RadioButton(this).apply {
                text = m.label
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            modeIds[m] = rb.id
            modeGroup.addView(rb)
        }
        modeGroup.check(modeIds[cmd.mode]!!)
        col.addView(modeGroup)
        col.addView(
            Ui.label(
                this,
                "Root and Shizuku run taps, swipes and keys as shell input, and unlock shell commands. Check their status on the main screen.",
                12f, top = 2, color = 0xAAFFFFFF.toInt()
            )
        )

        col.addView(Ui.label(this, "Repeat whole sequence (times)", 13f, top = 16))
        repeatEdit = Ui.edit(this, "1", cmd.repeat.toString(), number = true)
        col.addView(repeatEdit)

        enabledSw = Switch(this).apply {
            text = "Enabled"
            setTextColor(Color.WHITE)
            isChecked = cmd.enabled
        }
        col.addView(enabledSw)
        contSw = Switch(this).apply {
            text = "Keep going if an action fails"
            setTextColor(Color.WHITE)
            isChecked = cmd.continueOnError
        }
        col.addView(contSw)

        col.addView(Ui.label(this, "Actions", 20f, bold = true, top = 26))
        actionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionsBox)
        col.addView(Ui.button(this, "+ Add action") {
            chooseType { spec ->
                showForm(spec, null) { a ->
                    cmd.actions.add(a)
                    renderActions()
                }
            }
        })

        col.addView(Ui.button(this, "Save") { if (save()) finish() })
        val row = Ui.row(this)
        row.addView(Ui.small(this, "Test run") { test() })
        row.addView(Ui.small(this, "Add home-screen shortcut") { pinShortcut() })
        if (!isNew) row.addView(Ui.small(this, "Delete") { confirmDelete() })
        col.addView(row)

        renderActions()
    }

    // ------------------------------------------------------------------

    private fun readFields(): Boolean {
        val name = nameEdit.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the command a name", Toast.LENGTH_SHORT).show()
            return false
        }
        cmd.name = name
        cmd.phrases = phrasesEdit.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        cmd.mode = modeIds.entries.firstOrNull { it.value == modeGroup.checkedRadioButtonId }?.key ?: Mode.NORMAL
        cmd.repeat = (repeatEdit.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)
        cmd.enabled = enabledSw.isChecked
        cmd.continueOnError = contSw.isChecked
        return true
    }

    private fun save(): Boolean {
        if (!readFields()) return false
        Store.upsertCommand(this, cmd)
        isNew = false
        return true
    }

    private fun test() {
        if (!readFields()) return
        val svc = OpenVoiceService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on the Open Voice accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Running in 3 seconds. Switch to your screen.", Toast.LENGTH_LONG).show()
        svc.runCustom(cmd.copyDeep(), "test", 3000)
        moveTaskToBack(true)
    }

    private fun pinShortcut() {
        if (!save()) return
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, RunCommandActivity::class.java)
            .setAction(RunCommandActivity.ACTION_SHORTCUT)
            .putExtra("id", cmd.id)
        val info = ShortcutInfo.Builder(this, "cmd_${cmd.id}")
            .setShortLabel(cmd.name.take(12))
            .setLongLabel(cmd.name)
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete “${cmd.name}”?")
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteCommand(this, cmd.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Action list
    // ------------------------------------------------------------------
    private fun renderActions() {
        actionsBox.removeAllViews()
        if (cmd.actions.isEmpty()) {
            actionsBox.addView(Ui.label(this, "No actions yet. Add one below.", 14f, top = 8))
            return
        }
        cmd.actions.forEachIndexed { i, a ->
            val card = Ui.card(this)
            card.addView(Ui.label(this, "${i + 1}. ${Specs.summary(this, a)}", 14f))
            val r = Ui.row(this)
            r.addView(Ui.small(this, "▲") {
                if (i > 0) {
                    cmd.actions.add(i - 1, cmd.actions.removeAt(i))
                    renderActions()
                }
            })
            r.addView(Ui.small(this, "▼") {
                if (i < cmd.actions.size - 1) {
                    cmd.actions.add(i + 1, cmd.actions.removeAt(i))
                    renderActions()
                }
            })
            r.addView(Ui.small(this, "Edit") {
                val spec = Specs.get(a.type)
                if (spec != null) showForm(spec, a) { na ->
                    cmd.actions[i] = na
                    renderActions()
                }
            })
            r.addView(Ui.small(this, "✕") {
                cmd.actions.removeAt(i)
                renderActions()
            })
            card.addView(r)
            actionsBox.addView(card)
        }
    }

    private fun chooseType(onPick: (Spec) -> Unit) {
        val specs = Specs.all
        AlertDialog.Builder(this)
            .setTitle("Add action")
            .setItems(specs.map { it.title }.toTypedArray()) { _, i -> onPick(specs[i]) }
            .show()
    }

    private fun showForm(spec: Spec, existing: Action?, onSave: (Action) -> Unit) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@CommandEditorActivity, 20), Ui.dp(this@CommandEditorActivity, 8),
                Ui.dp(this@CommandEditorActivity, 20), Ui.dp(this@CommandEditorActivity, 8))
        }
        val getters = LinkedHashMap<String, () -> String>()

        for (f in spec.fields) {
            form.addView(Ui.label(this, f.label, 13f, top = 12))
            val cur = existing?.p(f.key, f.default) ?: f.default
            when (f.kind) {
                Kind.TEXT, Kind.NUMBER -> {
                    val e = Ui.edit(this, f.hint, cur, number = f.kind == Kind.NUMBER)
                    form.addView(e)
                    getters[f.key] = { e.text.toString().trim() }
                }
                Kind.CHOICE -> {
                    val sp = spinner(f.options, f.options, cur)
                    form.addView(sp.first)
                    getters[f.key] = sp.second
                }
                Kind.GESTURE -> {
                    val gs = Store.gestures(this)
                    if (gs.isEmpty()) {
                        form.addView(Ui.label(this, "No gestures yet. Record one in Gestures first.", 13f, top = 4))
                        getters[f.key] = { "" }
                    } else {
                        val sp = spinner(gs.map { it.name }, gs.map { it.id }, cur)
                        form.addView(sp.first)
                        getters[f.key] = sp.second
                    }
                }
                Kind.COMMAND -> {
                    val names = Store.commands(this).map { it.name }.filter { it != cmd.name }
                    if (names.isEmpty()) {
                        form.addView(Ui.label(this, "No other commands yet.", 13f, top = 4))
                        getters[f.key] = { "" }
                    } else {
                        val sp = spinner(names, names, cur)
                        form.addView(sp.first)
                        getters[f.key] = sp.second
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(spec.title)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                onSave(Action(spec.type, LinkedHashMap(getters.mapValues { it.value() })))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun spinner(labels: List<String>, values: List<String>, current: String): Pair<Spinner, () -> String> {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = values.indexOf(current)
        if (idx >= 0) sp.setSelection(idx)
        return sp to { values.getOrElse(sp.selectedItemPosition) { "" } }
    }
}
