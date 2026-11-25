package app.openvoice

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast

/** OpenAI-compatible API keys. They are tried top to bottom; one that runs out is skipped. */
class ApiKeysActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val dim = 0xAAFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "API keys", 28f, bold = true))
        col.addView(
            Ui.label(
                this,
                "Add keys for any OpenAI-compatible service (OpenAI, OpenRouter, Groq, Gemini, DeepSeek, Mistral, your own server…). Keys are used in this order. If one runs out of quota or gets rate limited, the next one takes over automatically; when every key is exhausted, Open Voice falls back to the local model. Keys are stored encrypted on this phone.",
                13f, top = 6, color = dim
            )
        )
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        col.addView(Ui.button(this, "+ Add API key") { edit(null) })
        col.addView(Ui.small(this, "Reset all key statuses") {
            ApiStore.keys(this).forEach { ApiStore.reset(this, it) }
            render()
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val keys = ApiStore.keys(this)
        if (keys.isEmpty()) {
            listBox.addView(Ui.label(this, "No keys yet.", 14f, top = 16, color = dim))
            return
        }
        keys.forEachIndexed { i, k ->
            val card = Ui.card(this)
            card.addView(Ui.label(this, "${i + 1}. ${k.label}", 16f, bold = true))
            card.addView(Ui.label(this, "${k.model} · ${ApiStore.mask(k.key)}", 12.5f, top = 2, color = dim))
            card.addView(Ui.label(this, k.baseUrl, 11f, top = 2, color = 0x77FFFFFF))
            card.addView(Ui.label(this, ApiStore.status(k) + if (k.uses > 0) " · used ${k.uses}×" else "", 12.5f, top = 6))
            val row = Ui.row(this)
            row.addView(Ui.small(this, "▲") {
                if (i > 0) {
                    keys.add(i - 1, keys.removeAt(i))
                    ApiStore.save(this)
                    render()
                }
            })
            row.addView(Ui.small(this, "▼") {
                if (i < keys.size - 1) {
                    keys.add(i + 1, keys.removeAt(i))
                    ApiStore.save(this)
                    render()
                }
            })
            row.addView(Ui.small(this, "Test") {
                Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = ApiClient.test(k)
                    runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
                }.start()
            })
            row.addView(Ui.small(this, "Reset") {
                ApiStore.reset(this, k)
                render()
            })
            row.addView(Ui.small(this, "Edit") { edit(k) })
            row.addView(Ui.small(this, "Delete") {
                keys.remove(k)
                ApiStore.save(this)
                render()
            })
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            listBox.addView(card)
        }
    }

    private fun edit(existing: ApiKey?) {
        val pad = Ui.dp(this, 20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@ApiKeysActivity, 8), pad, Ui.dp(this@ApiKeysActivity, 8))
        }
        val presetSpinner = Spinner(this)
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, ApiStore.presets.map { it.name }
        )
        val label = Ui.edit(this, "Label", existing?.label ?: "")
        val base = Ui.edit(this, "Base URL", existing?.baseUrl ?: ApiStore.presets[0].baseUrl)
        val model = Ui.edit(this, "Model", existing?.model ?: ApiStore.presets[0].model)
        val key = Ui.edit(this, "API key (leave empty for a local server)", existing?.key ?: "")
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        if (existing == null) {
            presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val pr = ApiStore.presets[pos]
                    base.setText(pr.baseUrl)
                    model.setText(pr.model)
                    if (label.text.isNullOrBlank() || ApiStore.presets.any { it.name == label.text.toString() }) label.setText(pr.name)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            form.addView(Ui.label(this, "Provider", 13f))
            form.addView(presetSpinner)
        }
        form.addView(Ui.label(this, "Label", 13f, top = 10))
        form.addView(label)
        form.addView(Ui.label(this, "Base URL (…/v1)", 13f, top = 10))
        form.addView(base)
        form.addView(Ui.label(this, "Model name", 13f, top = 10))
        form.addView(model)
        form.addView(Ui.label(this, "API key", 13f, top = 10))
        form.addView(key)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add API key" else "Edit API key")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val b = base.text.toString().trim()
                val m = model.text.toString().trim()
                if (b.isEmpty() || m.isEmpty()) {
                    Toast.makeText(this, "Base URL and model are required", Toast.LENGTH_LONG).show()
                } else if (existing == null) {
                    val k = ApiKey(label = label.text.toString().trim().ifEmpty { "Key ${ApiStore.keys(this).size + 1}" },
                        baseUrl = b, model = m, key = key.text.toString().trim())
                    ApiStore.keys(this).add(k)
                    ApiStore.save(this)
                    render()
                } else {
                    existing.label = label.text.toString().trim().ifEmpty { existing.label }
                    existing.baseUrl = b
                    existing.model = m
                    existing.key = key.text.toString().trim()
                    existing.invalid = false
                    existing.exhaustedUntil = 0
                    ApiStore.save(this)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
