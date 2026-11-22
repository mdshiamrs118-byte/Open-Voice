package app.openvoice

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Download, choose and delete the small on-device models used by AI mode. */
class ModelsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var statusLine: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val dim = 0xAAFFFFFF.toInt()
    private var firstRender = true

    private val tick = object : Runnable {
        override fun run() {
            render()
            val busy = ModelStore.jobs.values.any { !it.done } ||
                AiEngine.statusText.startsWith("Indexing") || AiEngine.statusText.startsWith("Loading")
            if (busy) ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "AI models", 28f, bold = true))
        col.addView(
            Ui.label(
                this,
                "Tiny on-device models that help Open Voice understand everyday wording and chain several tasks in one sentence. They are not chatbots: they only match what you say to a command. Everything runs on the phone; the only network use is downloading a model.",
                13f, top = 6, color = dim
            )
        )
        statusLine = Ui.label(this, "", 13f, top = 14)
        col.addView(statusLine)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        col.addView(Ui.button(this, "+ Add a model from Hugging Face") { addCustom() })
        col.addView(
            Ui.label(
                this,
                "Models are downloaded from huggingface.co. Any English BERT-style sentence embedding model exported to ONNX should work (it needs onnx/model_quantized.onnx and tokenizer.json, or give the path).",
                12f, top = 10, color = 0x88FFFFFF.toInt()
            )
        )
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun kick() {
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private fun render() {
        val active = AiEngine.activeModel(this)
        statusLine.text = when {
            active == null -> "No model installed yet. Download one below."
            else -> "Active: ${active.title} · ${AiEngine.statusText}"
        }
        listBox.removeAllViews()
        for (m in ModelStore.all(this)) {
            val ready = ModelStore.ready(this, m)
            val job = ModelStore.jobs[m.id]
            val downloading = job != null && !job.done
            val isActive = ready && Prefs.aiModel(this) == m.id

            val card = Ui.card(this)
            card.addView(Ui.label(this, m.title + "  ·  ~${m.approxMb} MB", 16f, bold = true))
            card.addView(Ui.label(this, m.desc, 12.5f, top = 3, color = dim))
            card.addView(
                Ui.label(
                    this,
                    when {
                        downloading -> "Downloading ${((job?.progress ?: 0f) * 100).toInt()}%"
                        !ready && job?.error != null -> "⚠ ${job.error}"
                        isActive -> "● In use"
                        ready -> "Ready"
                        else -> "Not downloaded"
                    },
                    12.5f, top = 6, color = if (isActive) Color.WHITE else dim
                )
            )
            if (downloading) {
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = ((job?.progress ?: 0f) * 100).toInt()
                    progressTintList = ColorStateList.valueOf(Color.WHITE)
                    progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(this@ModelsActivity, 6)).apply {
                        topMargin = Ui.dp(this@ModelsActivity, 8)
                    }
                }
                card.addView(bar)
            }

            val row = Ui.row(this)
            when {
                downloading -> row.addView(Ui.small(this, "Cancel") {
                    ModelStore.cancel(m.id)
                    kick()
                })
                !ready -> {
                    row.addView(Ui.small(this, if (job?.error != null) "Retry" else "Download") {
                        ModelStore.download(this, m) { runOnUiThread { kick() } }
                        kick()
                    })
                    if (m.custom) row.addView(Ui.small(this, "Remove") {
                        ModelStore.delete(this, m)
                        render()
                    })
                }
                else -> {
                    if (!isActive) row.addView(Ui.small(this, "Use this model") {
                        Prefs.setAiModel(this, m.id)
                        AiEngine.unload()
                        AiEngine.preload(this)
                        kick()
                    })
                    row.addView(Ui.small(this, "Delete") {
                        AlertDialog.Builder(this)
                            .setTitle("Delete ${m.title}?")
                            .setPositiveButton("Delete") { _, _ ->
                                ModelStore.delete(this, m)
                                render()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    })
                }
            }
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            listBox.addView(card)
            if (firstRender) Anim.appear(card, 0, 8)
        }
        firstRender = false
    }

    private fun addCustom() {
        val pad = Ui.dp(this, 20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@ModelsActivity, 8), pad, Ui.dp(this@ModelsActivity, 8))
        }
        val repo = Ui.edit(this, "e.g. Xenova/all-MiniLM-L6-v2")
        val onnx = Ui.edit(this, "onnx/model_quantized.onnx", "onnx/model_quantized.onnx")
        val prefix = Ui.edit(this, "optional, e.g. query: ")
        val pooling = Spinner(this)
        pooling.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("mean", "cls"))
        form.addView(Ui.label(this, "Hugging Face repo", 13f))
        form.addView(repo)
        form.addView(Ui.label(this, "ONNX file in the repo", 13f, top = 10))
        form.addView(onnx)
        form.addView(Ui.label(this, "Pooling (mean for most; cls for BGE)", 13f, top = 10))
        form.addView(pooling)
        form.addView(Ui.label(this, "Text prefix", 13f, top = 10))
        form.addView(prefix)
        AlertDialog.Builder(this)
            .setTitle("Add a model")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setPositiveButton("Add") { _, _ ->
                val m = ModelStore.addCustom(
                    this, repo.text.toString(), onnx.text.toString().trim(),
                    pooling.selectedItem.toString(), prefix.text.toString()
                )
                if (m == null) {
                    Toast.makeText(this, "Enter a repo like owner/name", Toast.LENGTH_LONG).show()
                } else {
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
