package app.openvoice

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import org.json.JSONArray
import org.json.JSONObject

class WebSite(
    val id: String,
    val name: String,
    val url: String,
    val inputSel: List<String>,
    val sendSel: List<String>,
    val domains: List<String>,
    val custom: Boolean = false
)

/** Chat websites that can be driven through the hidden browser. Selectors are hints; generic detection backs them up. */
object WebSites {

    val builtIn = listOf(
        WebSite("chatgpt", "ChatGPT", "https://chatgpt.com/",
            listOf("#prompt-textarea", "div#prompt-textarea"),
            listOf("button[data-testid=\"send-button\"]", "button[aria-label=\"Send prompt\"]"),
            listOf("chatgpt.com", "openai.com", "auth.openai.com")),
        WebSite("gemini", "Gemini", "https://gemini.google.com/app",
            listOf("rich-textarea div.ql-editor", "div.ql-editor[contenteditable=\"true\"]"),
            listOf("button.send-button", "button[aria-label=\"Send message\"]"),
            listOf("gemini.google.com", "google.com", "accounts.google.com")),
        WebSite("duckai", "DuckDuckGo AI Chat", "https://duckduckgo.com/aichat",
            listOf("textarea[name=\"user-prompt\"]"),
            listOf("button[type=\"submit\"]"),
            listOf("duckduckgo.com", "duck.ai")),
        WebSite("deepseek", "DeepSeek", "https://chat.deepseek.com/",
            listOf("textarea#chat-input"),
            emptyList(),
            listOf("deepseek.com")),
        WebSite("qwen", "Qwen", "https://chat.qwen.ai/",
            listOf("textarea#chat-input"),
            listOf("button#send-message-button", "button[type=\"submit\"]"),
            listOf("qwen.ai", "alibabacloud.com")),
        WebSite("claude", "Claude", "https://claude.ai/new",
            listOf("div[contenteditable=\"true\"].ProseMirror"),
            listOf("button[aria-label=\"Send message\"]"),
            listOf("claude.ai", "anthropic.com")),
        WebSite("grok", "Grok", "https://grok.com/",
            listOf("textarea"),
            listOf("button[type=\"submit\"]"),
            listOf("grok.com", "x.ai", "x.com")),
        WebSite("perplexity", "Perplexity", "https://www.perplexity.ai/",
            listOf("textarea"),
            listOf("button[aria-label*=\"Submit\"]"),
            listOf("perplexity.ai")),
        WebSite("copilot", "Microsoft Copilot", "https://copilot.microsoft.com/",
            listOf("textarea#userInput"),
            listOf("button[aria-label*=\"Submit\"]", "button[data-testid=\"submit-button\"]"),
            listOf("copilot.microsoft.com", "microsoft.com", "live.com")),
        WebSite("mistral", "Le Chat (Mistral)", "https://chat.mistral.ai/chat",
            listOf("textarea"), emptyList(), listOf("mistral.ai")),
        WebSite("kimi", "Kimi", "https://www.kimi.com/",
            listOf("div[contenteditable=\"true\"]"), emptyList(), listOf("kimi.com", "moonshot.cn")),
        WebSite("glm", "Z.ai (GLM)", "https://chat.z.ai/",
            listOf("textarea"), emptyList(), listOf("z.ai"))
    )

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("open_voice_web", Context.MODE_PRIVATE)

    fun customSites(c: Context): List<WebSite> {
        val out = ArrayList<WebSite>()
        try {
            val arr = JSONArray(sp(c).getString("custom", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.getString("url")
                val host = android.net.Uri.parse(url).host ?: continue
                out.add(WebSite(o.getString("id"), o.getString("name"), url, emptyList(), emptyList(), listOf(host), true))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun addCustom(c: Context, name: String, url: String): Boolean {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (name.isBlank() || android.net.Uri.parse(u).host == null) return false
        val arr = try {
            JSONArray(sp(c).getString("custom", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        arr.put(JSONObject().put("id", "custom-" + System.currentTimeMillis()).put("name", name.trim()).put("url", u))
        sp(c).edit().putString("custom", arr.toString()).apply()
        return true
    }

    fun removeCustom(c: Context, id: String) {
        val arr = try {
            JSONArray(sp(c).getString("custom", "[]"))
        } catch (e: Exception) {
            return
        }
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != id) out.put(arr.getJSONObject(i))
        sp(c).edit().putString("custom", out.toString()).apply()
    }

    fun all(c: Context): List<WebSite> = builtIn + customSites(c)
    fun find(c: Context, id: String): WebSite? = all(c).firstOrNull { it.id == id }

    /** Deletes the cookies and stored data of one site, which logs you out of it. */
    fun clearSite(c: Context, site: WebSite) {
        val cm = CookieManager.getInstance()
        for (d in site.domains) {
            for (host in listOf(d, "www.$d")) {
                val url = "https://$host"
                val cookies = cm.getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    for (part in cookies.split(";")) {
                        val name = part.substringBefore("=").trim()
                        if (name.isEmpty()) continue
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$d")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$d")
                    }
                }
                WebStorage.getInstance().deleteOrigin(url)
            }
        }
        cm.flush()
        Prefs.setWebSetup(c, site.id, false)
        if (Prefs.webAiId(c) == site.id) Prefs.setWebAiId(c, "")
        OpenVoiceService.instance?.resetWeb()
    }

    /** Wipes every web AI session, cookie and cache. */
    fun clearAll(c: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        try {
            WebView(c).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
        } catch (_: Exception) {
        }
        try {
            WebViewDatabase.getInstance(c).clearHttpAuthUsernamePassword()
        } catch (_: Exception) {
        }
        all(c).forEach { Prefs.setWebSetup(c, it.id, false) }
        Prefs.setWebAiId(c, "")
        OpenVoiceService.instance?.resetWeb()
    }
}
