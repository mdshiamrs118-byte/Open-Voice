package app.openvoice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast

/** Choose the web AI used by AI mode, log in to it, and wipe its session. */
class WebAiActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val dim = 0xAAFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.screen(this)
        col.addView(Ui.label(this, "Web AI", 28f, bold = true))
        col.addView(
            Ui.label(
                this,
                "Open Voice sends your request to a chat website running in a hidden browser and carries out the steps it answers with. Pick one, log in once, and it keeps working in the background.",
                13f, top = 6, color = dim
            )
        )
        col.addView(
            Ui.label(
                this,
                "Heads up: this depends on each site's page layout, so a site update can break it. Some sites block embedded browsers or show checks. Using a chat site this way may be against its terms. If it fails, Open Voice falls back to the local model.",
                12f, top = 8, color = 0x88FFFFFF.toInt()
            )
        )
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        col.addView(Ui.button(this, "+ Add another web AI by URL") { addCustom() })
        col.addView(Ui.small(this, "Clear ALL web AI logins & data") {
            AlertDialog.Builder(this)
                .setTitle("Clear everything?")
                .setMessage("This logs you out of every web AI and deletes their cookies, storage and cache.")
                .setPositiveButton("Clear") { _, _ ->
                    WebSites.clearAll(this)
                    Toast.makeText(this, "All web AI data cleared", Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val selected = Prefs.webAiId(this)
        for (site in WebSites.all(this)) {
            val isSel = site.id == selected
            val setup = Prefs.webSetup(this, site.id)
            val card = Ui.card(this)
            card.addView(Ui.label(this, site.name, 16f, bold = true))
            card.addView(Ui.label(this, site.url, 12f, top = 2, color = dim))
            card.addView(
                Ui.label(
                    this,
                    when {
                        isSel && setup -> "● In use · logged in"
                        isSel -> "● In use · log in needed"
                        setup -> "Logged in"
                        else -> "Not set up"
                    },
                    12.5f, top = 6, color = if (isSel) Color.WHITE else dim
                )
            )
            val row = Ui.row(this)
            if (!isSel) row.addView(Ui.small(this, "Use") {
                if (!Prefs.webSetup(this, site.id)) {
                    // first time: show the site so you can log in
                    Toast.makeText(this, "Log in to ${site.name}, then tap Done", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(this, WebAiLoginActivity::class.java).putExtra("site", site.id).putExtra("select", true)
                    )
                } else {
                    Prefs.setWebAiId(this, site.id)
                    OpenVoiceService.instance?.resetWeb()
                    render()
                }
            })
            row.addView(Ui.small(this, if (setup) "Open / re-login" else "Log in") {
                startActivity(
                    Intent(this, WebAiLoginActivity::class.java).putExtra("site", site.id).putExtra("select", isSel)
                )
            })
            row.addView(Ui.small(this, "Clear login") {
                WebSites.clearSite(this, site)
                Toast.makeText(this, "${site.name} login and cache cleared", Toast.LENGTH_SHORT).show()
                render()
            })
            if (site.custom) row.addView(Ui.small(this, "Remove") {
                WebSites.clearSite(this, site)
                WebSites.removeCustom(this, site.id)
                render()
            })
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            listBox.addView(card)
        }
    }

    private fun addCustom() {
        val pad = Ui.dp(this, 20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@WebAiActivity, 8), pad, Ui.dp(this@WebAiActivity, 8))
        }
        val name = Ui.edit(this, "Name, e.g. My AI")
        val url = Ui.edit(this, "https://chat.example.com/")
        form.addView(Ui.label(this, "Name", 13f))
        form.addView(name)
        form.addView(Ui.label(this, "Chat page URL", 13f, top = 10))
        form.addView(url)
        AlertDialog.Builder(this)
            .setTitle("Add a web AI")
            .setView(form)
            .setPositiveButton("Add") { _, _ ->
                if (WebSites.addCustom(this, name.text.toString(), url.text.toString())) render()
                else Toast.makeText(this, "Enter a name and a valid URL", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
