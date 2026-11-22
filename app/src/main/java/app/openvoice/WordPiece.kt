package app.openvoice

import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** BERT (uncased) WordPiece tokenizer, loaded from a Hugging Face tokenizer.json. */
class WordPiece(private val vocab: HashMap<String, Int>) {

    private val unk = vocab["[UNK]"] ?: 100
    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102

    companion object {
        fun load(file: File): WordPiece {
            val v = JSONObject(file.readText()).getJSONObject("model").getJSONObject("vocab")
            val map = HashMap<String, Int>(v.length() * 2)
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = v.getInt(k)
            }
            return WordPiece(map)
        }
    }

    fun encode(text: String, maxLen: Int): IntArray {
        val ids = ArrayList<Int>()
        ids.add(cls)
        for (w in basic(text)) {
            wordPiece(w, ids)
            if (ids.size >= maxLen - 1) break
        }
        while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(sep)
        return ids.toIntArray()
    }

    private fun basic(text: String): List<String> {
        val cleaned = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
        }
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> flush()
                ch.isLetterOrDigit() -> cur.append(ch)
                else -> {
                    flush()
                    out.add(ch.toString())
                }
            }
        }
        flush()
        return out
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        if (word.length > 100) {
            out.add(unk)
            return
        }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                var sub = word.substring(start, end)
                if (start > 0) sub = "##$sub"
                val id = vocab[sub]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found == -1) {
                out.add(unk)
                return
            }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }
}
