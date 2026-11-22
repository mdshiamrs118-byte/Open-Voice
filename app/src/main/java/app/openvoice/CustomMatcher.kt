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
     * Phrases ending in {text} capture the rest of the sentence, e.g. "search for {text}".
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
                if (pn.endsWith("{text}")) {
                    val prefix = pn.removeSuffix("{text}").trim()
                    if (prefix.isNotEmpty() && hn.startsWith("$prefix ") && hn.length > prefix.length + 1) {
                        val skip = prefix.split(" ").size
                        val words = heard.trim().split(Regex("\\s+"))
                        val captured = words.drop(skip).joinToString(" ").trim()
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
