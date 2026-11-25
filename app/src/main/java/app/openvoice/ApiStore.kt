package app.openvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ApiKey(
    val id: String = UUID.randomUUID().toString(),
    var label: String,
    var baseUrl: String,
    var model: String,
    var key: String
) {
    @Volatile var exhaustedUntil = 0L
    @Volatile var invalid = false
    @Volatile var lastError = ""
    @Volatile var uses = 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("baseUrl", baseUrl).put("model", model)
        .put("key", Secrets.enc(key))
        .put("exhaustedUntil", exhaustedUntil).put("invalid", invalid)
        .put("lastError", lastError).put("uses", uses)

    companion object {
        fun fromJson(o: JSONObject): ApiKey {
            val k = ApiKey(o.getString("id"), o.optString("label"), o.optString("baseUrl"), o.optString("model"), Secrets.dec(o.optString("key")))
            k.exhaustedUntil = o.optLong("exhaustedUntil")
            k.invalid = o.optBoolean("invalid")
            k.lastError = o.optString("lastError")
            k.uses = o.optInt("uses")
            return k
        }
    }
}

class ApiPreset(val name: String, val baseUrl: String, val model: String)

/** OpenAI-compatible API keys, tried in order. A key that runs out is skipped until it recovers. */
object ApiStore {

    val presets = listOf(
        ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ApiPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        ApiPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        ApiPreset("Gemini (OpenAI-compatible)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        ApiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        ApiPreset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
        ApiPreset("Together", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        ApiPreset("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b"),
        ApiPreset("xAI", "https://api.x.ai/v1", "grok-3-mini"),
        ApiPreset("Custom / local server", "http://192.168.1.10:11434/v1", "llama3.2")
    )

    private var cache: MutableList<ApiKey>? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("open_voice_api", Context.MODE_PRIVATE)

    @Synchronized
    fun keys(c: Context): MutableList<ApiKey> {
        cache?.let { return it }
        val list = mutableListOf<ApiKey>()
        try {
            val arr = JSONArray(sp(c).getString("keys", "[]"))
            for (i in 0 until arr.length()) list.add(ApiKey.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    @Synchronized
    fun save(c: Context) {
        val arr = JSONArray()
        keys(c).forEach { arr.put(it.toJson()) }
        sp(c).edit().putString("keys", arr.toString()).apply()
    }

    fun usable(k: ApiKey) = !k.invalid && System.currentTimeMillis() >= k.exhaustedUntil

    fun hasUsable(c: Context) = keys(c).any { usable(it) }

    fun status(k: ApiKey): String {
        val now = System.currentTimeMillis()
        return when {
            k.invalid -> "⚠ Invalid key (${k.lastError})"
            now < k.exhaustedUntil -> {
                val mins = ((k.exhaustedUntil - now) / 60000).toInt() + 1
                "⏳ Out of quota/rate limit · retry in ${if (mins >= 60) "${mins / 60} h" else "$mins min"}"
            }
            k.lastError.isNotEmpty() -> "● Ready (last: ${k.lastError})"
            else -> "● Ready"
        }
    }

    fun reset(c: Context, k: ApiKey) {
        k.exhaustedUntil = 0
        k.invalid = false
        k.lastError = ""
        save(c)
    }

    fun mask(key: String) = if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)
}
