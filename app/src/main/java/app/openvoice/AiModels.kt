package app.openvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ModelFile(val path: String, val name: String, val approxBytes: Long)

/**
 * A small on-device text-embedding model used for intent matching. These are not chat models:
 * they only turn a sentence into numbers so Open Voice can find the closest command.
 */
class AiModel(
    val id: String,
    val title: String,
    val desc: String,
    val repo: String,
    val files: List<ModelFile>,
    val pooling: String = "mean",
    val prefix: String = "",
    val calib: Float = 0f,
    val custom: Boolean = false
) {
    val approxMb: Int get() = (files.sumOf { it.approxBytes } / 1_000_000L).toInt().coerceAtLeast(1)
}

object ModelStore {

    private fun std(
        id: String, title: String, desc: String, repo: String, onnxMb: Long,
        pooling: String = "mean", prefix: String = "", calib: Float = 0f
    ) = AiModel(
        id, title, desc, repo,
        listOf(
            ModelFile("onnx/model_quantized.onnx", "model.onnx", onnxMb * 1_000_000L),
            ModelFile("tokenizer.json", "tokenizer.json", 700_000L)
        ),
        pooling, prefix, calib
    )

    val builtIn: List<AiModel> = listOf(
        std("bge-micro", "Micro · bge-micro-v2", "Smallest and fastest. Good for everyday phrases on older phones.",
            "Xenova/bge-micro-v2", 17, calib = 0.10f),
        std("minilm-l6", "Recommended · MiniLM L6", "Best balance of size, speed and accuracy for command matching.",
            "Xenova/all-MiniLM-L6-v2", 23),
        std("multi-qa-l6", "Short phrases · multi-qa MiniLM", "Tuned for short questions and requests, so it suits spoken commands.",
            "Xenova/multi-qa-MiniLM-L6-cos-v1", 23),
        std("minilm-l12", "Accurate · MiniLM L12", "Twice as deep as L6. Slightly better with unusual wording.",
            "Xenova/all-MiniLM-L12-v2", 33),
        std("gte-small", "Sharp · GTE small", "Strong general-purpose English matching.",
            "Xenova/gte-small", 34, calib = 0.12f),
        std("bge-small", "Strong · BGE small", "High accuracy for English phrases.",
            "Xenova/bge-small-en-v1.5", 34, pooling = "cls", calib = 0.15f),
        std("e5-small", "Careful · E5 small", "Cautious matcher that avoids guessing.",
            "Xenova/e5-small-v2", 34, prefix = "query: ", calib = 0.20f)
    )

    // ---- user-added models (Hugging Face repo id) ----
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("open_voice_models", Context.MODE_PRIVATE)

    fun customModels(c: Context): List<AiModel> {
        val out = ArrayList<AiModel>()
        try {
            val arr = JSONArray(sp(c).getString("custom", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val onnx = o.optString("onnx", "onnx/model_quantized.onnx")
                out.add(
                    AiModel(
                        o.getString("id"), o.getString("repo"), "Added by you from Hugging Face.", o.getString("repo"),
                        listOf(ModelFile(onnx, "model.onnx", 30_000_000L), ModelFile("tokenizer.json", "tokenizer.json", 700_000L)),
                        o.optString("pooling", "mean"), o.optString("prefix", ""), 0.05f, custom = true
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun addCustom(c: Context, repo: String, onnxPath: String, pooling: String, prefix: String): AiModel? {
        val r = repo.trim()
        if (!r.contains('/')) return null
        val id = "custom-" + r.replace(Regex("[^A-Za-z0-9]"), "-").lowercase()
        val arr = try {
            JSONArray(sp(c).getString("custom", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return find(c, id)
        arr.put(
            JSONObject().put("id", id).put("repo", r)
                .put("onnx", onnxPath.ifBlank { "onnx/model_quantized.onnx" })
                .put("pooling", pooling).put("prefix", prefix)
        )
        sp(c).edit().putString("custom", arr.toString()).apply()
        return find(c, id)
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

    fun all(c: Context): List<AiModel> = builtIn + customModels(c)
    fun find(c: Context, id: String): AiModel? = all(c).firstOrNull { it.id == id }

    fun dir(c: Context, id: String): File = File(c.applicationContext.filesDir, "ai/$id").also { it.mkdirs() }

    fun ready(c: Context, m: AiModel): Boolean {
        val d = dir(c, m.id)
        return m.files.all { File(d, it.name).let { f -> f.exists() && f.length() > 0 } }
    }

    // ---- downloads ----
    class Job {
        @Volatile var cancelled = false
        @Volatile var progress = 0f
        @Volatile var error: String? = null
        @Volatile var done = false
    }

    val jobs = ConcurrentHashMap<String, Job>()

    fun cancel(id: String) {
        jobs[id]?.cancelled = true
    }

    fun delete(c: Context, m: AiModel) {
        cancel(m.id)
        dir(c, m.id).deleteRecursively()
        jobs.remove(m.id)
        if (m.custom) removeCustom(c, m.id)
        if (Prefs.aiModel(c) == m.id) {
            Prefs.setAiModel(c, "")
            AiEngine.unload()
            Prefs.set(c, Prefs.KEY_AI_MODE, false)
        }
    }

    fun download(c: Context, m: AiModel, onUpdate: () -> Unit) {
        if (jobs[m.id]?.done == false) return
        val app = c.applicationContext
        val job = Job()
        jobs[m.id] = job
        Thread {
            try {
                val dir = dir(app, m.id)
                val total = m.files.sumOf { it.approxBytes }.coerceAtLeast(1L)
                var doneBytes = 0L
                var lastTick = 0L
                for (f in m.files) {
                    val target = File(dir, f.name)
                    if (target.exists() && target.length() > 0) {
                        doneBytes += f.approxBytes
                        continue
                    }
                    val part = File(dir, f.name + ".part")
                    val conn = URL("https://huggingface.co/${m.repo}/resolve/main/${f.path}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "OpenVoice-Android")
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for ${f.path}")
                    var got = 0L
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } > 0) {
                                if (job.cancelled) throw IOException("cancelled")
                                out.write(buf, 0, n)
                                got += n
                                job.progress = ((doneBytes + got).toFloat() / total).coerceAtMost(0.99f)
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 400) {
                                    lastTick = now
                                    onUpdate()
                                }
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw IOException("couldn't save ${f.name}")
                    doneBytes += maxOf(got, 1L)
                }
                job.progress = 1f
                if (Prefs.aiModel(app).isEmpty()) Prefs.setAiModel(app, m.id)
            } catch (e: Exception) {
                job.error = if (job.cancelled) "cancelled" else (e.message ?: "download failed")
                dir(app, m.id).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            } finally {
                job.done = true
                onUpdate()
            }
        }.start()
    }
}
