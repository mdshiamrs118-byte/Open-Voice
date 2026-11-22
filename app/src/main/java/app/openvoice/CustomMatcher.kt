package app.openvoice

object CustomMatcher {

    class Match(val command: CustomCommand, val captured: String?)

    fun norm(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}{} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Finds the custom command whose trigger phrase matches [heard].
     * {text} can sit anywhere in a phrase and captures those words, e.g. "search for {text}"
     * or "open {text} app".
     */
    fun match(heard: String, cmds: List<CustomCommand>): Match? {
        val hn = norm(heard)
        if (hn.isEmpty()) return null

        // pass 1: exact / template
        for (c in cmds) {
            if (!c.enabled) continue
            for (phrase in c.phrases) {
                val pn = norm(phrase)
                if (pn.isEmpty()) continue
                if (pn.contains("{text}")) {
                    val idx = pn.indexOf("{text}")
                    val pre = pn.substring(0, idx).trim().split(" ").filter { it.isNotEmpty() }
                    val suf = pn.substring(idx + 6).replace("{text}", "").trim().split(" ").filter { it.isNotEmpty() }
                    if (pre.size + suf.size == 0) continue
                    val hw = hn.split(" ").filter { it.isNotEmpty() }
                    if (hw.size > pre.size + suf.size &&
                        hw.take(pre.size) == pre && hw.takeLast(suf.size) == suf
                    ) {
                        val rawWords = heard.trim().split(Regex("\\s+"))
                        val source = if (rawWords.size == hw.size) rawWords else hw
                        val captured = source.subList(pre.size, source.size - suf.size).joinToString(" ").trim()
                        if (captured.isNotEmpty()) return Match(c, captured)
                    }
                } else if (hn == pn) {
                    return Match(c, null)
                }
            }
        }
        // pass 2: tolerate one wrong letter on longer phrases
        for (c in cmds) {
            if (!c.enabled) continue
            for (phrase in c.phrases) {
                val pn = norm(phrase)
                if (pn.length >= 8 && !pn.contains("{text}") && lev(hn, pn) <= 1) return Match(c, null)
            }
        }
        return null
    }

    private fun lev(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
