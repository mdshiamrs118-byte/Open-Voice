package app.openvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to OpenAI-compatible /chat/completions endpoints and rotates through the saved keys. */
object ApiClient {

    private const val OK = 0
    private const val QUOTA = 1
    private const val RATE = 2
    private const val AUTH = 3
    private const val OTHER = 4

    private class Reply(val kind: Int, val content: String?, val error: String)

    /** Why the last [plan] call gave up (shown to the user). */
    @Volatile
    var lastNote = ""

    private fun call(k: ApiKey, prompt: String, maxTokens: Int = 1000): Reply {
        return try {
            val conn = URL(k.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (k.key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${k.key}")
            val body = JSONObject()
                .put("model", k.model)
                .put("temperature", 0)
                .put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                Reply(OK, content, "")
            } else {
                val low = text.lowercase()
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(120)
                } catch (e: Exception) {
                    text.take(120)
                }
                val quota = "insufficient_quota" in low || "quota" in low || "billing" in low ||
                    "resource_exhausted" in low || "credit" in low || "exceeded" in low && "limit" in low
                when {
                    code == 402 || (code == 429 && quota) -> Reply(QUOTA, null, msg)
                    code == 429 -> Reply(RATE, null, msg)
                    code == 401 || code == 403 -> Reply(AUTH, null, msg)
                    else -> Reply(OTHER, null, "HTTP $code: $msg")
                }
            }
        } catch (e: Exception) {
            Reply(OTHER, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Sends a tiny request so the user can check a key. Returns a human message. */
    fun test(k: ApiKey): String {
        val r = call(k, "Reply with the single word OK.", 8)
        return if (r.kind == OK) "Works: “${r.content?.trim()?.take(40)}”" else "Failed: ${r.error}"
    }

    /**
     * Tries each usable key in order. A key that is out of quota or rate limited is parked and the next
     * one is used. Returns null when every key failed, so the caller can fall back to the local model.
     */
    fun plan(
        ctx: Context, prompt: String, nonce: String, a: AiContext,
        allowShell: Boolean, shellMode: Mode
    ): AiEngine.PlanResult? {
        val keys = ApiStore.keys(ctx)
        if (keys.isEmpty()) {
            lastNote = "no API keys"
            return null
        }
        val now = System.currentTimeMillis()
        var tried = 0
        var skipped = 0
        for (k in keys) {
            if (!ApiStore.usable(k)) {
                skipped++
                continue
            }
            tried++
            val r = call(k, prompt)
            when (r.kind) {
                OK -> {
                    val parsed = AiPlan.parse(ctx, r.content.orEmpty(), nonce, a, allowShell, shellMode, "API · ${k.label}")
                    if (parsed != null) {
                        k.uses++
                        k.lastError = ""
                        ApiStore.save(ctx)
                        return parsed
                    }
                    k.lastError = "unreadable reply"
                    ApiStore.save(ctx)
                }
                QUOTA -> {
                    k.exhaustedUntil = now + 6 * 3600_000L
                    k.lastError = r.error.take(80)
                    ApiStore.save(ctx)
                }
                RATE -> {
                    k.exhaustedUntil = now + 90_000L
                    k.lastError = "rate limited"
                    ApiStore.save(ctx)
                }
                AUTH -> {
                    k.invalid = true
                    k.lastError = r.error.take(80)
                    ApiStore.save(ctx)
                }
                else -> {
                    k.exhaustedUntil = now + 20_000L
                    k.lastError = r.error.take(80)
                    ApiStore.save(ctx)
                }
            }
        }
        lastNote = if (tried == 0) "all API keys are exhausted" else "all API keys failed"
        return null
    }
}
