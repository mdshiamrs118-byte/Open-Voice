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
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    private lateinit var nameEdit: EditText
    private lateinit var phrasesEdit: EditText
    private lateinit var repeatEdit: EditText
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
        phrasesEdit = Ui.edit(this, "read feed\nsearch for {text}\nopen {text} app", cmd.phrases.joinToString("\n"), multiline = true)
        col.addView(phrasesEdit)
        col.addView(
            Ui.label(
                this,
                "{text} can sit anywhere in a phrase and captures those words. Use {text} in ANY action field (open app, tap position, wait, URL…). Leave phrases empty to trigger only from a shortcut. Custom phrases override built-in ones.",
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
                "Root and Shizuku run taps, swipes and keys as shell input, can start private app shortcuts, and unlock shell commands.",
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
        col.addView(
            Ui.label(
                this,
                "Tip: adding If / Repeat / While / Try inserts the matching end marker. Use ＋ on a row to insert below it.",
                12f, top = 2, color = 0xAAFFFFFF.toInt()
            )
        )
        actionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionsBox)
        col.addView(Ui.button(this, "+ Add action") {
            chooseType { spec -> addAction(spec, cmd.actions.size) }
        })

        col.addView(Ui.button(this, "Save") { if (save()) finish() })
        val row = Ui.row(this)
        row.addView(Ui.small(this, "Test run") { test() })
        row.addView(Ui.small(this, "Add home-screen shortcut") { pinShortcut() })
        if (!isNew) row.addView(Ui.small(this, "Delete") { confirmDelete() })
        col.addView(HorizontalScrollView(this).apply { addView(row) })

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
        val problem = MacroEngine.validate(cmd.actions)
        if (problem != null) {
            AlertDialog.Builder(this)
                .setTitle("Fix the blocks first")
                .setMessage(problem)
                .setPositiveButton("OK", null)
                .show()
            return false
        }
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
        Toast.makeText(this, "Running in 3 seconds ({text} = “test”). Switch to your screen.", Toast.LENGTH_LONG).show()
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
        var depth = 0
        cmd.actions.forEachIndexed { i, a ->
            if (a.type == "end_if" || a.type == "end_loop" || a.type == "end_try") {
                depth = (depth - 1).coerceAtLeast(0)
            }
            val shown = if (a.type == "else_if" || a.type == "else" || a.type == "catch") (depth - 1).coerceAtLeast(0) else depth

            val card = Ui.card(this)
            card.addView(Ui.label(this, "${i + 1}. " + "│ ".repeat(shown) + Specs.summary(this, a), 14f))

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
            r.addView(Ui.small(this, "＋") { chooseType { spec -> addAction(spec, i + 1) } })
            val spec = Specs.get(a.type)
            if (spec != null && spec.fields.isNotEmpty()) {
                r.addView(Ui.small(this, "Edit") {
                    showForm(spec, a) { na ->
                        cmd.actions[i] = na
                        renderActions()
                    }
                })
            }
            r.addView(Ui.small(this, "Copy") {
                cmd.actions.add(i + 1, a.copy())
                renderActions()
            })
            r.addView(Ui.small(this, "✕") {
                cmd.actions.removeAt(i)
                renderActions()
            })
            card.addView(HorizontalScrollView(this).apply { addView(r) })
            actionsBox.addView(card)

            if (a.type == "if" || a.type == "repeat" || a.type == "while" || a.type == "try") depth++
        }
    }

    private fun chooseType(onPick: (Spec) -> Unit) {
        val cats = Specs.all.map { it.category }.distinct()
        AlertDialog.Builder(this)
            .setTitle("Add action")
            .setItems(cats.toTypedArray()) { _, ci ->
                val list = Specs.all.filter { it.category == cats[ci] }
                AlertDialog.Builder(this)
                    .setTitle(cats[ci])
                    .setItems(list.map { it.title }.toTypedArray()) { _, i -> onPick(list[i]) }
                    .show()
            }
            .show()
    }

    private fun addAction(spec: Spec, at: Int) {
        if (spec.fields.isEmpty()) {
            insert(at, Specs.withEnds(Action(spec.type)))
        } else {
            showForm(spec, null) { a -> insert(at, Specs.withEnds(a)) }
        }
    }

    private fun insert(at: Int, list: List<Action>) {
        cmd.actions.addAll(at.coerceIn(0, cmd.actions.size), list)
        renderActions()
    }

    private fun showForm(spec: Spec, existing: Action?, onSave: (Action) -> Unit) {
        val pad = Ui.dp(this, 20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@CommandEditorActivity, 8), pad, Ui.dp(this@CommandEditorActivity, 8))
        }
        if (spec.help.isNotEmpty()) {
            form.addView(Ui.label(this, spec.help, 12f, top = 4, color = 0xAAFFFFFF.toInt()))
        }
        val getters = LinkedHashMap<String, () -> String>()
        val edits = HashMap<String, EditText>()

        for (f in spec.fields) {
            form.addView(Ui.label(this, f.label, 13f, top = 12))
            val cur = existing?.p(f.key, f.default) ?: f.default
            when (f.kind) {
                Kind.TEXT, Kind.NUMBER -> {
                    val e = Ui.edit(this, f.hint, cur, multiline = f.multiline)
                    form.addView(e)
                    edits[f.key] = e
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
            if (spec.type == "app_shortcut" && f.key == "shortcut") {
                form.addView(Ui.small(this, "Browse apps & shortcuts…") {
                    browseShortcuts(edits["app"], edits["shortcut"])
                })
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

    private fun browseShortcuts(appEdit: EditText?, scEdit: EditText?) {
        if (appEdit == null || scEdit == null) return
        Toast.makeText(this, "Scanning apps…", Toast.LENGTH_SHORT).show()
        Thread {
            val apps = try {
                AppShortcuts.appsWithShortcuts(this)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (apps.isEmpty()) {
                    Toast.makeText(this, "No apps with shortcuts found", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Choose app")
                        .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                            val (pkg, _, list) = apps[i]
                            AlertDialog.Builder(this)
                                .setTitle("Choose shortcut")
                                .setItems(list.map { it.label }.toTypedArray()) { _, j ->
                                    appEdit.setText(pkg)
                                    scEdit.setText(list[j].label)
                                }
                                .show()
                        }
                        .show()
                }
            }
        }.start()
    }

    private fun spinner(labels: List<String>, values: List<String>, current: String): Pair<Spinner, () -> String> {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = values.indexOf(current)
        if (idx >= 0) sp.setSelection(idx)
        return sp to { values.getOrElse(sp.selectedItemPosition) { "" } }
    }
}
