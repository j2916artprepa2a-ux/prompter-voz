package mx.prompter.voz

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sigue el guion palabra por palabra: alinea las últimas palabras que escuchó
 * con el guion cerca de donde vas (mismo algoritmo que la app web).
 * Lo que va entre [corchetes] son indicaciones: se ven, pero no se dicen.
 */
class Follower(val display: String) {

    val words = ArrayList<IntRange>()      // posiciones (en el texto) de las palabras que se dicen
    val norms = ArrayList<String>()
    val directions = ArrayList<IntRange>() // [indicaciones]
    var pos = 0                            // índice de la siguiente palabra por decir

    init {
        val bracket = Regex("\\[[^\\]\\n]*\\]")
        bracket.findAll(display).forEach { directions.add(it.range) }
        Regex("\\S+").findAll(display).forEach { m ->
            if (directions.any { m.range.first >= it.first && m.range.first <= it.last }) return@forEach
            val n = norm(m.value)
            if (n.isNotEmpty()) { words.add(m.range); norms.add(n) }
        }
    }

    val count get() = norms.size

    /** Posición en el texto de la palabra actual (para pintar y hacer scroll). */
    fun currentOffset(): Int = if (pos < words.size) words[pos].first else display.length

    /** Toque en el texto: brinca a la palabra en (o después de) ese carácter. */
    fun jumpToOffset(off: Int) {
        val k = words.indexOfFirst { it.last >= off }
        pos = if (k >= 0) k else words.size
    }

    fun follow(text: String): Boolean {
        val heard = text.split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }
        if (heard.isEmpty() || norms.isEmpty()) return false
        val tail = heard.takeLast(6)
        val lo = max(0, pos - 4)
        val hi = min(norms.size - 1, pos + 28)
        var best = -1
        var bestScore = 0.0
        for (j in lo..hi) {
            var score = 0.0
            var hits = 0
            var lastHit = false
            var s = j
            var t = tail.size - 1
            while (t >= 0 && s >= 0) {
                var m = same(tail[t], norms[s])
                if (m == 0.0 && s > 0) {
                    val m2 = same(tail[t], norms[s - 1])
                    if (m2 > 0.0) { m = m2 * 0.8; s-- }
                }
                if (m > 0.0) {
                    score += m * weight(norms[s]); hits++
                    if (t == tail.size - 1) lastHit = true
                }
                s--; t--
            }
            if (hits == 0) continue
            val dist = j - pos
            var adj = score - abs(dist) * 0.06 + (if (lastHit) 0.5 else 0.0)
            if (dist < -1) adj -= 1.2
            if (adj > bestScore) { bestScore = adj; best = j }
        }
        if (best < 0) return false
        val dist = best - pos
        val near = dist >= -1 && dist <= 3
        val ok = (near && bestScore >= 1.25) || bestScore >= 2.4 || (dist < -1 && bestScore >= 3.4)
        if (!ok) return false
        val np = min(best + 1, norms.size)
        if (np != pos) { pos = np; return true }
        return false
    }

    companion object {
        fun norm(s: String): String {
            val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            return d.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9ñ]"), "")
        }

        private fun lev(a: String, b: String): Int {
            if (a == b) return 0
            if (abs(a.length - b.length) > 2) return 3
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val tmp = prev; prev = cur; cur = tmp
            }
            return prev[b.length]
        }

        private fun same(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val l = min(a.length, b.length)
            if (l >= 4) {
                val d = lev(a, b)
                if (d <= (if (l >= 7) 2 else 1)) return 0.85
                if (l >= 5 && (a.startsWith(b) || b.startsWith(a))) return 0.7
            }
            return 0.0
        }

        private fun weight(w: String): Double = when {
            w.length <= 2 -> 0.6
            w.length <= 3 -> 0.8
            else -> 1.2
        }
    }
}
