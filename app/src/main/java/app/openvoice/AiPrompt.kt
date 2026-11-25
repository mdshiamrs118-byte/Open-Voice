package app.openvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Builds the instruction sent to API / Web AIs, and turns their JSON answer into something we can run. */
object AiPrompt {

    fun nonce(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun catalog(allowShell: Boolean): String {
        val sb = StringBuilder()
        for (s in Specs.all) {
            if (s.category == Specs.C_FLOW && s.type !in setOf("wait", "stop", "call")) continue
            if (s.type == "shell" && !allowShell) continue
            if (s.type == "gesture" || s.type == "set_clipboard") continue
            val fields = s.fields.joinToString(",") { f ->
                if (f.kind == Kind.CHOICE && f.options.size <= 8) "${f.key}(${f.options.joinToString("|")})" else f.key
            }
            sb.append("- ").append(s.type).append(": ").append(fields.ifEmpty { "-" }).append('\n')
        }
        sb.append("- key options: ").append(Specs.keyOptions.joinToString("|")).append('\n')
        sb.append("- control flow (use rarely): if{check,left,op,right} else_if{...} else end_if · repeat{count} while{check,left,op,right} end_loop break continue · wait_until{check,left,op,right,timeout}\n")
        sb.append("  checks: ${Specs.checks.joinToString("|")} · ops: equals|not_equals|contains|starts_with|ends_with|gt|lt|gte|lte\n")
        return sb.toString()
    }

    fun build(
        ctx: Context, a: AiContext, request: String, nonce: String, foreground: String,
        allowShell: Boolean, shellMode: Mode, shareApps: Boolean, shareScreen: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("You are the planner of an Android voice-control app. Turn the user's spoken request into a JSON plan the app can execute.\n")
        sb.append("Reply with ONLY one JSON object, nothing else, shaped exactly like:\n")
        sb.append("{\"id\":\"<ID>\",\"mode\":\"normal\",\"steps\":[{\"action\":\"open_app\",\"name\":\"Chrome\"},{\"action\":\"wait\",\"seconds\":1.5},{\"action\":\"click_text\",\"text\":\"Search\"}],\"reply\":\"short note, max 60 chars\"}\n")
        sb.append("The ID for this request is: $nonce — copy it into \"id\".\n")
        if (allowShell) {
            sb.append("\"mode\" is \"normal\" (accessibility actions) or \"shell\" (runs input/tap/swipe/key actions and the shell action directly through ${shellMode.name.lowercase()}). Use shell only when it clearly helps. Never run destructive commands.\n")
        } else {
            sb.append("\"mode\" must be \"normal\". Do not use the shell action.\n")
        }
        sb.append("ACTIONS (action: params). Params are strings or numbers; tap positions are percent of the screen (0-100):\n")
        sb.append(catalog(allowShell))
        sb.append("Built-in voice commands can be run with {\"action\":\"voice\",\"phrase\":\"...\"}, for example: ")
        sb.append(BuiltIns.groups.joinToString(" | ") { it.say.substringBefore(" — ") }.take(1400)).append('\n')
        sb.append("RULES: keep plans short (max 15 steps); add a wait of 1 to 2 seconds after opening an app or changing screens; prefer click_text over tap coordinates; use type for text input and key enter to submit; put {text} nowhere. ")
        sb.append("If the request can't be done or is unclear, return an empty steps list and explain in reply.\n")
        sb.append("CONTEXT: foreground app: ${foreground.ifBlank { "unknown" }}.")
        if (shareApps) sb.append(" Installed apps: ${a.apps.map { it.first }.take(90).joinToString(", ")}.")
        if (shareScreen && a.screenTexts.isNotEmpty()) sb.append(" Visible screen text: ${a.screenTexts.take(40).joinToString(" | ")}.")
        val customs = a.customs.map { it.name }.filter { it.isNotBlank() }
        if (customs.isNotEmpty()) sb.append(" The user's own commands (run with the call action): ${customs.joinToString(", ")}.")
        sb.append("\nUSER REQUEST: ").append(request)
        return sb.toString()
    }
}

/** Reads the AI's JSON and converts it into our own action list (the same actions custom commands use). */
object AiPlan {

    private val danger = Regex(
        "(?i)(\\brm\\s+-|\\brm\\s+/|mkfs|\\bdd\\s+if|pm\\s+(uninstall|clear|disable|hide)|\\bwipe\\b|factory|\\bformat\\b|setprop|chmod\\s+7|\\bsu\\b|curl|wget|\\bnc\\s|/dev/block|reboot\\s+bootloader|recovery|\\bflash\\b)"
    )

    private val typeAlias = mapOf(
        "click" to "click_text", "press" to "click_text", "tap_text" to "click_text", "click_button" to "click_text",
        "open" to "open_app", "launch" to "open_app", "start_app" to "open_app",
        "url" to "open_uri", "open_url" to "open_uri", "browse" to "open_uri", "open_link" to "open_uri",
        "sleep" to "wait", "delay" to "wait", "pause" to "wait",
        "command" to "voice", "builtin" to "voice",
        "say" to "toast", "message" to "toast", "show" to "toast",
        "write" to "type", "input" to "type", "enter_text" to "type",
        "swipe_up" to "swipe", "run" to "call", "run_command" to "call"
    )

    private val paramAlias = mapOf(
        "open_app" to mapOf("name" to listOf("app", "name", "package", "label")),
        "click_text" to mapOf("text" to listOf("text", "label", "target", "name", "button")),
        "type" to mapOf("text" to listOf("text", "value", "content", "message")),
        "open_uri" to mapOf("uri" to listOf("uri", "url", "link", "data")),
        "key" to mapOf("key" to listOf("key", "name", "button")),
        "wait" to mapOf("seconds" to listOf("seconds", "s", "duration", "time")),
        "scroll" to mapOf("direction" to listOf("direction", "dir")),
        "voice" to mapOf("phrase" to listOf("phrase", "command", "cmd", "text")),
        "shell" to mapOf("command" to listOf("command", "cmd")),
        "toast" to mapOf("message" to listOf("message", "text")),
        "speak" to mapOf("text" to listOf("text", "message")),
        "call" to mapOf("command" to listOf("command", "name"))
    )

    /** Finds the JSON object in a model reply (handles fences and chatter around it). */
    fun extractJson(content: String, nonce: String?): String? {
        val text = content.replace("```json", "").replace("```", "")
        var from = 0
        while (true) {
            val s = text.indexOf('{', from)
            if (s < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in s until text.length) {
                val c = text[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                } else {
                    if (c == '"') inStr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            val cand = text.substring(s, i + 1)
                            try {
                                val o = JSONObject(cand)
                                if (o.has("steps") && (nonce == null || o.optString("id") == nonce || !o.has("id"))) return cand
                            } catch (_: Exception) {
                            }
                            break
                        }
                    }
                }
            }
            from = s + 1
        }
    }

    private fun pick(o: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                return if (v is Double && v == Math.floor(v) && v < 1e9) v.toLong().toString() else v.toString()
            }
        }
        return null
    }

    fun parse(
        ctx: Context, content: String, nonce: String?, a: AiContext,
        allowShell: Boolean, shellMode: Mode, source: String
    ): AiEngine.PlanResult? {
        val json = extractJson(content, nonce) ?: return null
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val reply = o.optString("reply").trim().ifEmpty { null }
        val mode = if (allowShell && o.optString("mode") == "shell") shellMode else Mode.NORMAL
        val arr = o.optJSONArray("steps") ?: JSONArray()
        val actions = ArrayList<Action>()
        for (i in 0 until minOf(arr.length(), 25)) {
            val s = arr.optJSONObject(i) ?: continue
            var type = s.optString("action").trim().lowercase().replace(' ', '_')
            type = typeAlias[type] ?: type
            val spec = Specs.get(type) ?: continue
            if (type == "shell" && mode == Mode.NORMAL) continue
            if (type in setOf("gesture", "set_clipboard")) continue
            val params = LinkedHashMap<String, String>()
            for (f in spec.fields) {
                val keys = (paramAlias[type]?.get(f.key) ?: emptyList()) + f.key
                var v = pick(s, keys) ?: continue
                if (f.key == "key") v = v.lowercase().replace(' ', '_').let { if (it == "lock") "lock_screen" else it }
                if (type == "wait" && f.key == "seconds") {
                    val sec = (v.toDoubleOrNull() ?: 1.0).let { if (s.has("ms") && !s.has("seconds")) (s.optDouble("ms") / 1000.0) else it }
                    v = sec.coerceIn(0.0, 15.0).toString()
                }
                params[f.key] = v
            }
            if (type == "wait" && !params.containsKey("seconds") && s.has("ms")) {
                params["seconds"] = (s.optDouble("ms") / 1000.0).coerceIn(0.0, 15.0).toString()
            }
            if (type == "shell" && danger.containsMatchIn(params["command"].orEmpty())) continue
            actions.add(Action(type, params))
        }
        if (actions.isEmpty()) {
            return AiEngine.PlanResult(emptyList(), null, false, reply ?: "nothing to do", source)
        }
        val bad = MacroEngine.validate(actions)
        if (bad != null) return AiEngine.PlanResult(emptyList(), "the AI's plan was invalid ($bad)", false, null, source)

        val cmd = CustomCommand(name = "AI plan", mode = mode, actions = actions)
        val label = reply ?: actions.take(3).joinToString(" → ") { Specs.summary(ctx, it).substringBefore(":") }
        return AiEngine.PlanResult(listOf(PlanStep.Custom(cmd, null, label)), null, false, reply, source)
    }
}
