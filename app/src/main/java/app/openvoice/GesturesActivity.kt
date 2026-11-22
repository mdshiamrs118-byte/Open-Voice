package app.openvoice

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast

class GesturesActivity : Activity() {

    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "Gestures", 28f, bold = true))
        col.addView(
            Ui.label(
                this,
                "Record any gesture, including multi-finger ones like pinch, then use it as an action in a custom command.",
                14f, top = 6
            )
        )
        col.addView(Ui.button(this, "● Record new gesture") { record() })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun record() {
        val svc = OpenVoiceService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on the Open Voice accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        svc.startRecorder()
        moveTaskToBack(true)
    }

    private fun render() {
        listBox.removeAllViews()
        val list = Store.gestures(this)
        if (list.isEmpty()) {
            listBox.addView(Ui.label(this, "No gestures yet.", 14f, top = 20))
            return
        }
        for (g in list) {
            val card = Ui.card(this)
            card.addView(Ui.label(this, g.name, 17f, bold = true))
            card.addView(
                Ui.label(
                    this, "${g.strokes.size} stroke(s) · ${g.totalMs} ms", 12f, top = 4,
                    color = 0xAAFFFFFF.toInt()
                )
            )
            val r = Ui.row(this)
            r.addView(Ui.small(this, "Test") { test(g) })
            r.addView(Ui.small(this, "Rename") { rename(g) })
            r.addView(Ui.small(this, "Delete") {
                AlertDialog.Builder(this)
                    .setTitle("Delete “${g.name}”?")
                    .setPositiveButton("Delete") { _, _ ->
                        Store.deleteGesture(this, g.id)
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            card.addView(r)
            listBox.addView(card)
        }
    }

    private fun test(g: Gesture) {
        val svc = OpenVoiceService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on the Open Voice accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Playing in 3 seconds. Switch to your screen.", Toast.LENGTH_LONG).show()
        svc.playGestureAfter(g, 3000)
        moveTaskToBack(true)
    }

    private fun rename(g: Gesture) {
        val e = Ui.edit(this, "Name", g.name)
        AlertDialog.Builder(this)
            .setTitle("Rename gesture")
            .setView(e)
            .setPositiveButton("Save") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) {
                    g.name = n
                    Store.saveGestures(this)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
