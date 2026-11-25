package app.openvoice

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** A visible browser for logging in to a web AI. The hidden browser later reuses the same session. */
class WebAiLoginActivity : Activity() {

    private lateinit var web: WebView
    private var siteId = ""
    private var select = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        siteId = intent.getStringExtra("site") ?: ""
        select = intent.getBooleanExtra("select", false)
        val site = WebSites.find(this, siteId)
        if (site == null) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@WebAiLoginActivity, 14), Ui.dp(this@WebAiLoginActivity, 10), Ui.dp(this@WebAiLoginActivity, 10), Ui.dp(this@WebAiLoginActivity, 10))
        }
        val title = TextView(this).apply {
            text = "Log in to ${site.name}, then tap Done"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(Ui.small(this, "Reload") { web.reload() })
        bar.addView(Ui.small(this, "Done") { done(site) })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        web.loadUrl(site.url)
    }

    private fun done(site: WebSite) {
        CookieManager.getInstance().flush()
        Prefs.setWebSetup(this, site.id, true)
        if (select) Prefs.setWebAiId(this, site.id)
        OpenVoiceService.instance?.resetWeb()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            try {
                web.destroy()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
