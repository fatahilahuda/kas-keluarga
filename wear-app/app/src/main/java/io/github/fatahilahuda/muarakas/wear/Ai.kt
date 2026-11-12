package io.github.fatahilahuda.muarakas.wear

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** Same naive Bayes the phone uses (predict() in index.html), on the watch's own copy of the data. */
class Ai(private val kas: Kas, learned: JSONObject) {
    private class Model(val counts: Map<String, Map<String, Double>>, val totals: Map<String, Double>, val vocab: Set<String>)

    private val models: Map<String, Model> = listOf("out", "in").associateWith { type -> build(type, learned.optJSONObject(type)) }

    fun feats(text: String): List<String> {
        val f = mutableListOf<String>()
        text.lowercase().replace(Regex("[^a-z\\s]"), " ").split(Regex("\\s+")).forEach { t ->
            if (t.length < 2 || t in kas.seed.stop) return@forEach
            f += t
            if (t.length >= 5) f += "p:" + t.take(4)
        }
        return f
    }

    private fun build(type: String, learned: JSONObject?): Model {
        val counts = HashMap<String, HashMap<String, Double>>()
        val totals = HashMap<String, Double>()
        val vocab = HashSet<String>()
        val cats = kas.cats[type].orEmpty()
        cats.forEach { counts[it.id] = HashMap(); totals[it.id] = 0.0 }
        fun add(c: String, fs: List<String>, w: Double) {
            val m = counts[c] ?: return
            fs.forEach { m[it] = (m[it] ?: 0.0) + w; totals[c] = totals[c]!! + w; vocab += it }
        }
        val seed = kas.seed.seed[type].orEmpty()
        seed.forEach { (c, words) -> words.split(" ").forEach { add(c, feats(it), 1.0) } }
        // A deleted built-in category hands its seed words to the category its catatan moved to.
        val defs = kas.settings.optJSONArray("cats")
        if (defs != null) for (i in 0 until defs.length()) {
            val d = defs.optJSONObject(i) ?: continue
            val to = d.optString("to")
            if (d.optString("t") == type && d.optBoolean("del") && to.isNotEmpty()) seed[d.optString("c")]?.split(" ")?.forEach { add(to, feats(it), 1.0) }
        }
        cats.forEach { c -> c.words.lowercase().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.forEach { add(c.id, feats(it), 3.0) } }
        learned?.keys()?.forEach { c ->
            val o = learned.optJSONObject(c) ?: return@forEach
            o.keys().forEach { f -> add(c, listOf(f), o.optDouble(f, 0.0) * 3) }
        }
        return Model(counts, totals, vocab)
    }

    /** Best categories for this text, most likely first. Empty when no word is known. */
    fun rank(type: String, text: String): List<Pair<String, Double>> {
        val md = models[type] ?: return emptyList()
        val fs = feats(text).filter { it in md.vocab }
        if (fs.isEmpty()) return emptyList()
        val a = 0.05
        val v = md.vocab.size
        val sc = kas.cats[type].orEmpty().map { c ->
            val t = md.totals[c.id] ?: 0.0
            val cm = md.counts[c.id].orEmpty()
            c.id to fs.sumOf { f -> ln(((cm[f] ?: 0.0) + a) / (t + a * v)) }
        }
        val mx = sc.maxOf { it.second }
        val ps = sc.map { it.first to exp(it.second - mx) }
        val sum = ps.sumOf { it.second }
        return ps.map { it.first to it.second / sum }.sortedByDescending { it.second }
    }
}

object Amount {
    data class Found(val value: Long, val start: Int, val len: Int)

    private val re = Regex("(\\d+(?:[.,]\\d+)*)\\s*(rb|ribu|k|jt|juta)?(?![a-z0-9])", RegexOption.IGNORE_CASE)

    /** "bakso 15rb", "gaji 6,2jt", "bensin 30" (the phone's parseAmount). */
    fun parse(str: String): Found? {
        var best: Found? = null
        var bestScore = -1
        re.findAll(str).forEach { m ->
            val raw = m.groupValues[1]
            val suf = m.groupValues[2].lowercase()
            var v: Double = if (suf.isNotEmpty()) {
                val mult = if (suf == "jt" || suf == "juta") 1e6 else 1e3
                if (Regex("^\\d+[.,]\\d{1,2}$").matches(raw)) raw.replace(',', '.').toDouble() * mult
                else (raw.replace(Regex("[.,]"), "").toLongOrNull() ?: 0L) * mult
            } else {
                val x = if (Regex("^\\d+,\\d{1,2}$").matches(raw)) raw.substringBefore(',').toDouble()
                else (raw.replace(Regex("[.,]"), "").toLongOrNull() ?: 0L).toDouble()
                if (x > 0 && x < 1000) x * 1000 else x
            }
            val score = (if (suf.isNotEmpty()) 2 else 0) + (if (v >= 1000) 1 else 0)
            val f = Found(v.roundToLong(), m.range.first, m.value.length)
            if (best == null || score > bestScore || (score == bestScore && f.value > best!!.value)) { best = f; bestScore = score }
        }
        return best
    }

    /** Speech often says "lima belas ribu"; turn common Indonesian number words into digits first. */
    fun normalizeSpeech(s: String): String {
        val units = mapOf("satu" to 1L, "dua" to 2L, "tiga" to 3L, "empat" to 4L, "lima" to 5L, "enam" to 6L, "tujuh" to 7L, "delapan" to 8L, "sembilan" to 9L)
        val words = s.lowercase().replace("rp", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var total = 0L; var cur = 0L; var last = 0L; var any = false
        fun flush() { if (any) out += (total + cur + last).toString(); total = 0; cur = 0; last = 0; any = false }
        for (w in words) {
            when {
                w in units -> { if (last > 0) { cur += last }; last = units.getValue(w); any = true }
                w == "sepuluh" -> { cur += 10; any = true }
                w == "sebelas" -> { cur += 11; any = true }
                w == "seratus" -> { cur += 100; any = true }
                w == "seribu" -> { total += 1000; any = true }
                w == "sejuta" -> { total += 1_000_000; any = true }
                w == "belas" && last > 0 -> { cur += last + 10; last = 0 }
                w == "puluh" && last > 0 -> { cur += last * 10; last = 0 }
                w == "ratus" && last > 0 -> { cur += last * 100; last = 0 }
                (w == "ribu" || w == "juta") && any -> {
                    val g = (cur + last).let { if (it == 0L) 1L else it }
                    total += g * (if (w == "ribu") 1000L else 1_000_000L); cur = 0; last = 0
                }
                else -> { flush(); out += w }
            }
        }
        flush()
        return out.joinToString(" ")
    }
}

object Fmt {
    private val nf = NumberFormat.getIntegerInstance(Locale("id", "ID"))
    fun full(n: Long) = (if (n < 0) "−" else "") + "Rp " + nf.format(abs(n))
    fun num(n: Long) = nf.format(n)
    /** Short form like the phone's fmtS: Rp 1,2 jt, Rp 350 rb. */
    fun short(n: Long, rp: Boolean = true): String {
        val a = abs(n).toDouble()
        val s = when {
            a >= 1e9 -> dec(a / 1e9) + " M"
            a >= 1e6 -> dec(a / 1e6) + " jt"
            a >= 1e3 -> Math.round(a / 1e3).toString() + " rb"
            else -> Math.round(a).toString()
        }
        return (if (n < 0) "−" else "") + (if (rp) "Rp " else "") + s
    }
    private fun dec(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
    }
    fun qty(x: Double): String = NumberFormat.getNumberInstance(Locale("id", "ID")).apply { maximumFractionDigits = 3 }.format(x)
}
