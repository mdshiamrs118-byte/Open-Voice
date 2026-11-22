package app.openvoice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * AI mode brain. A tiny embedding model turns a spoken sentence into a vector; the closest known intent
 * wins. Sentences with several tasks are split into steps. Arguments (app names, numbers, on-screen text)
 * are pulled out with rules. There is no text generation and nothing leaves the phone.
 */
object AiEngine {

    private class Entry(val intent: String, val vec: FloatArray)
    private class Resolved(val steps: List<PlanStep>, val conf: Float)
    private class Seq(val items: List<Resolved>, val minConf: Float)

    class PlanResult(val steps: List<PlanStep>, val error: String? = null, val engineFailed: Boolean = false)

    @Volatile
    var statusText = "Not loaded"

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var tok: WordPiece? = null
    private var loaded: AiModel? = null
    private var entries: List<Entry> = emptyList()
    private val customCache = HashMap<String, FloatArray>()
    private val loadLock = Any()
    private val runLock = Any()
    private val planLock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private var failNote: String? = null

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------
    fun activeModel(ctx: Context): AiModel? {
        val m = ModelStore.find(ctx, Prefs.aiModel(ctx)) ?: return null
        return if (ModelStore.ready(ctx, m)) m else null
    }

    fun ready(ctx: Context) = activeModel(ctx) != null

    fun preload(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { ensureLoaded(app) }
    }

    fun unload() {
        synchronized(loadLock) {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            session = null
            tok = null
            loaded = null
            entries = emptyList()
            customCache.clear()
            statusText = "Not loaded"
        }
    }

    fun ensureLoaded(ctx: Context): Boolean {
        return synchronized(loadLock) {
            val m = activeModel(ctx) ?: return@synchronized false
            if (loaded?.id == m.id && session != null) return@synchronized true
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            session = null
            entries = emptyList()
            customCache.clear()
            try {
                statusText = "Loading ${m.title}…"
                val dir = ModelStore.dir(ctx, m.id)
                val e = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                opts.setIntraOpNumThreads(2)
                val s = e.createSession(File(dir, "model.onnx").absolutePath, opts)
                env = e
                session = s
                tok = WordPiece.load(File(dir, "tokenizer.json"))
                loaded = m
                buildIndex(dir, m)
                statusText = "Ready"
                true
            } catch (t: Throwable) {
                session = null
                loaded = null
                statusText = "Failed: ${t.message ?: t.javaClass.simpleName}"
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // Embeddings
    // ------------------------------------------------------------------
    private fun embedRaw(text: String): FloatArray? {
        val m = loaded ?: return null
        val t = tok ?: return null
        val s = session ?: return null
        val e = env ?: return null
        val ids = t.encode(m.prefix + text, 64)
        val n = ids.size
        val idArr = arrayOf(LongArray(n) { ids[it].toLong() })
        val maskArr = arrayOf(LongArray(n) { 1L })
        val typeArr = arrayOf(LongArray(n))
        var result: FloatArray? = null
        synchronized(runLock) {
            val tensors = ArrayList<OnnxTensor>()
            try {
                val inputs = HashMap<String, OnnxTensor>()
                for (name in s.inputNames) {
                    val arr = when (name) {
                        "input_ids" -> idArr
                        "attention_mask" -> maskArr
                        "token_type_ids" -> typeArr
                        else -> continue
                    }
                    val tensor = OnnxTensor.createTensor(e, arr)
                    tensors.add(tensor)
                    inputs[name] = tensor
                }
                s.run(inputs).use { res ->
                    val v = res.get(0).value
                    @Suppress("UNCHECKED_CAST")
                    if (v is Array<*> && v.isNotEmpty() && v[0] is Array<*>) {
                        result = pool((v[0] as Array<FloatArray>), m.pooling)
                    } else if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) {
                        result = (v as Array<FloatArray>)[0]
                    }
                }
            } finally {
                tensors.forEach { it.close() }
            }
        }
        return result?.let { normalize(it) }
    }

    private fun pool(hidden: Array<FloatArray>, mode: String): FloatArray {
        if (hidden.isEmpty()) return FloatArray(0)
        if (mode == "cls") return hidden[0].copyOf()
        val dim = hidden[0].size
        val out = FloatArray(dim)
        for (row in hidden) for (i in 0 until dim) out[i] += row[i]
        for (i in 0 until dim) out[i] /= hidden.size
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    // ------------------------------------------------------------------
    // Intent index (cached on disk per model)
    // ------------------------------------------------------------------
    private fun buildIndex(dir: File, m: AiModel) {
        val items = ArrayList<Pair<String, String>>()
        for (i in AiIntents.all) for (ex in i.examples) items.add(i.id to ex)
        val cache = File(dir, "index_v${AiIntents.VERSION}.bin")
        if (cache.exists()) {
            try {
                DataInputStream(cache.inputStream().buffered()).use { inp ->
                    val count = inp.readInt()
                    val dim = inp.readInt()
                    if (count == items.size && dim > 0) {
                        val list = ArrayList<Entry>(count)
                        for (i in 0 until count) {
                            val v = FloatArray(dim) { inp.readFloat() }
                            list.add(Entry(items[i].first, v))
                        }
                        entries = list
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }
        val out = ArrayList<Entry>(items.size)
        for ((ix, p) in items.withIndex()) {
            statusText = "Indexing ${ix + 1}/${items.size}…"
            val v = embedRaw(p.second) ?: throw IllegalStateException("embedding failed")
            out.add(Entry(p.first, v))
        }
        entries = out
        try {
            DataOutputStream(cache.outputStream().buffered()).use { o ->
                o.writeInt(out.size)
                o.writeInt(out[0].vec.size)
                for (e in out) for (x in e.vec) o.writeFloat(x)
            }
        } catch (_: Exception) {
        }
    }

    private fun customVec(modelId: String, text: String): FloatArray? {
        val key = "$modelId|$text"
        customCache[key]?.let { return it }
        val v = embedRaw(text) ?: return null
        customCache[key] = v
        return v
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------
    fun plan(ctx: Context, text: String, a: AiContext, baseThreshold: Float): PlanResult =
        synchronized(planLock) { planLocked(ctx, text, a, baseThreshold) }

    private val strongSplit = Regex(
        "\\s*(?:\\band then\\b|\\band after that\\b|\\bafter that\\b|\\bafterwards\\b|\\bfollowed by\\b|\\bthen\\b|;)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val weakSplit = Regex("\\s*(?:,|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

    private fun planLocked(ctx: Context, text: String, a: AiContext, base: Float): PlanResult {
        if (!ensureLoaded(ctx)) return PlanResult(emptyList(), "AI model isn't ready ($statusText)", engineFailed = true)
        val m = loaded ?: return PlanResult(emptyList(), "AI model isn't ready", engineFailed = true)
        val thr = base + m.calib
        failNote = null

        val parts = strongSplit.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val all = ArrayList<PlanStep>()
        var prev: PlanStep? = null
        for (part in parts) {
            val items = bestOfPart(ctx, m, part, a, thr, prev)
                ?: return PlanResult(emptyList(), failNote ?: "not sure what to do with “$part”")
            for (r in items) {
                all.addAll(r.steps)
                prev = r.steps.lastOrNull { it !is PlanStep.Pause } ?: prev
            }
        }
        if (all.isEmpty()) return PlanResult(emptyList(), "nothing to do")
        for (s in all) {
            if (s is PlanStep.Builtin && !BuiltIns.isEnabled(ctx, s.cmd.groupId())) {
                return PlanResult(emptyList(), "“${s.label}” is turned off in Commands")
            }
        }
        if (all.count { it !is PlanStep.Pause } > 8) return PlanResult(emptyList(), "that's too many steps (max 8)")
        return PlanResult(all)
    }

    private fun bestOfPart(ctx: Context, m: AiModel, part: String, a: AiContext, thr: Float, prev: PlanStep?): List<Resolved>? {
        val weak = weakSplit.split(part).map { it.trim() }.filter { it.isNotEmpty() }
        val whole = resolveSeq(ctx, m, listOf(part), a, thr, prev)
        if (weak.size <= 1) return whole?.items
        if (whole != null && whole.minConf >= 0.9f) return whole.items
        val split = resolveSeq(ctx, m, weak, a, thr, prev)
        if (split != null) return split.items
        return whole?.items
    }

    private fun resolveSeq(ctx: Context, m: AiModel, segs: List<String>, a: AiContext, thr: Float, startPrev: PlanStep?): Seq? {
        val out = ArrayList<Resolved>()
        var prev = startPrev
        var min = 1f
        for (seg in segs) {
            var r: Resolved? = carry(seg, prev, a, true)
            if (r == null) {
                val one = resolveOne(ctx, m, seg, a, thr)
                r = if (one != null && one.conf >= thr) one else carry(seg, prev, a, false)
            }
            if (r == null) return null
            out.add(r)
            if (r.conf < min) min = r.conf
            prev = r.steps.lastOrNull { it !is PlanStep.Pause } ?: prev
        }
        return Seq(out, min)
    }

    /** "turn on wifi and bluetooth": the second part has no verb, so it inherits the previous action. */
    private fun carry(seg: String, prev: PlanStep?, a: AiContext, bareOnly: Boolean): Resolved? {
        val last = (prev as? PlanStep.Builtin)?.cmd ?: return null
        val words = a.norm(seg).split(" ").filter { it.isNotEmpty() }
        if (bareOnly && words.size > 4) return null
        when (last) {
            is Command.Toggle -> {
                val key = SystemToggles.find(seg) ?: return null
                val hasVerb = words.any { it in setOf("turn", "switch", "enable", "disable", "toggle", "activate", "off", "on") }
                if (bareOnly && hasVerb) return null
                val c = Command.Toggle(key, last.state)
                return Resolved(listOf(PlanStep.Builtin(c, AiIntents.describe(c))), 0.6f)
            }
            is Command.OpenApp -> {
                val app = a.findApp(seg) ?: return null
                if (bareOnly && words.size > 3) return null
                val c = Command.OpenApp(app)
                return Resolved(listOf(PlanStep.Builtin(c, AiIntents.describe(c))), 0.6f)
            }
            else -> return null
        }
    }

    private fun rule(ctx: Context, seg: String, a: AiContext): Resolved? {
        val cm = CustomMatcher.match(seg, a.customs)
        if (cm != null) return Resolved(listOf(PlanStep.Custom(cm.command, cm.captured, cm.command.name)), 1f)
        val cmd = CommandParser.parse(seg)
        if (cmd == Command.Unknown) return null
        if (!BuiltIns.isEnabled(ctx, cmd.groupId())) return null
        val conf = when (cmd) {
            is Command.OpenApp -> if (a.findApp(cmd.name) != null) 1f else 0.4f
            is Command.ClickText -> 0.9f
            is Command.Toggle -> if (SystemToggles.known(cmd.name)) 1f else 0.55f
            else -> 1f
        }
        val c = if (cmd is Command.OpenApp) Command.OpenApp(a.findApp(cmd.name) ?: cmd.name) else cmd
        return Resolved(listOf(PlanStep.Builtin(c, AiIntents.describe(c))), conf)
    }

    private fun resolveOne(ctx: Context, m: AiModel, seg: String, a: AiContext, thr: Float): Resolved? {
        val r = rule(ctx, seg, a)
        if (r != null && r.conf >= 0.9f) return r

        val q = embedRaw(seg)
        if (q == null) return r
        val per = HashMap<String, Float>()
        for (e in entries) {
            val s = dot(q, e.vec)
            if (s > (per[e.intent] ?: -1f)) per[e.intent] = s
        }
        for (c in a.customs) {
            if (c.phrases.any { it.contains("{text}") }) continue
            for (t in listOf(c.name) + c.phrases) {
                if (t.isBlank()) continue
                val v = customVec(m.id, t) ?: continue
                val s = dot(q, v)
                val id = "custom:${c.id}"
                if (s > (per[id] ?: -1f)) per[id] = s
            }
        }
        val top = per.entries.maxByOrNull { it.value }
        if (top == null) return r
        if (top.value < thr) {
            failNote = "not sure what “$seg” means (closest: ${top.key.removePrefix("custom:")} ${(top.value * 100).toInt()}%)"
            return if (r != null && r.conf >= thr) r else null
        }
        val steps: List<PlanStep>? = if (top.key.startsWith("custom:")) {
            val c = a.customs.firstOrNull { "custom:${it.id}" == top.key }
            c?.let { listOf(PlanStep.Custom(it, null, it.name)) }
        } else {
            AiIntents.all.firstOrNull { it.id == top.key }?.build?.invoke(seg, a)
        }
        if (steps == null) {
            failNote = "understood “$seg” as “${top.key}” but couldn't tell what to act on"
            return if (r != null && r.conf >= thr) r else null
        }
        val sem = Resolved(steps, top.value)
        return if (r != null && r.conf >= thr && r.conf >= sem.conf) r else sem
    }
}
