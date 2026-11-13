package io.github.fatahilahuda.muarakas.wear

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** One synced row, exactly as kas_pull returns it. */
data class Item(val kind: String, val id: String, val u: Long, val data: JSONObject)

data class Cat(val id: String, val n: String, val k: Int, val words: String = "")
data class Member(val id: String, val n: String)
data class Pocket(val id: String, val n: String, val k: Int, val start: Long, val save: Boolean, val archived: Boolean)
data class Book(val id: String, val n: String, val kind: String, val k: Int, val pocket: String?, val raw: JSONObject, val archived: Boolean)

data class Tx(
    val id: String, val type: String, val amount: Long, val desc: String, val cat: String,
    val pocket: String?, val to: String?, val date: String, val ts: Long, val by: String?,
    val biz: String?, val dir: Int, val tfk: String?, val qty: Double, val fee: Boolean,
    val hidden: Boolean, val sealed: Boolean, val revealed: Boolean,
)

/** One line on the shared shopping list (module "List belanja"; synced as kind "x", t = "shop"). */
data class ShopItem(val id: String, val n: String, val price: Long, val done: Boolean, val raw: JSONObject)
data class Totals(val hin: Long, val hout: Long, val cats: Map<String, Long>, val n: Int)
data class Usaha(val book: Book, val omzet: Long, val biaya: Long) { val laba get() = omzet - biaya }
data class Target(val book: Book, val bal: Long, val tgt: Long) { val pct get() = if (tgt > 0) min(100.0, bal * 100.0 / tgt) else 0.0 }
data class Cicilan(val book: Book, val total: Long, val paid: Long, val inst: Long) { val rem get() = max(0L, total - paid) }
data class Piutang(val book: Book, val given: Long, val back: Long) { val out get() = max(0L, given - back) }
data class Invest(val book: Book, val qty: Double, val invested: Long, val price: Long, val unit: String) {
    val value get() = (qty * price).toLong()
    val gain get() = if (price > 0) value - invested else 0L
}

/** Built-in categories and keywords, extracted from the phone app's index.html. */
class Seed(json: JSONObject) {
    val cats: Map<String, List<Cat>>
    val seed: Map<String, Map<String, String>>
    val stop: Set<String>

    init {
        val c = json.getJSONObject("cats")
        cats = TYPES.associateWith { t ->
            val a = c.getJSONArray(t)
            (0 until a.length()).map { i -> val o = a.getJSONObject(i); Cat(o.getString("id"), o.getString("n"), i % 7) }
        }
        val s = json.getJSONObject("seed")
        seed = TYPES.associateWith { t ->
            val o = s.optJSONObject(t) ?: JSONObject()
            o.keys().asSequence().associateWith { o.getString(it) }
        }
        val st = json.getJSONArray("stop")
        stop = (0 until st.length()).map { st.getString(it) }.toSet()
    }

    companion object { val TYPES = listOf("out", "in", "bout", "bin") }
}

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null
private fun JSONObject.num(k: String): Long = if (has(k) && !isNull(k)) optDouble(k, 0.0).let { if (it.isNaN()) 0L else Math.round(it) } else 0L
private fun JSONObject.flag(k: String): Boolean = has(k) && !isNull(k) && opt(k).let { it == true || it == 1 || it == "1" }

/**
 * A read-only view of the family's data, computed the same way the phone does
 * (balances(), totals(), the book stats), so both screens show the same numbers.
 */
class Kas(val seed: Seed, items: Collection<Item>, val me: String, val goldIdr: Long) {
    val settings: JSONObject
    val members: List<Member>
    val pockets: List<Pocket>
    val books: List<Book>
    val cats: Map<String, List<Cat>>
    val allTx: List<Tx>
    val shop: List<ShopItem>
    val visible: List<Tx>
    private val deadCatTo: List<Triple<String, String, String>> // type, deleted cat, moved to

    init {
        settings = items.firstOrNull { it.kind == "settings" }?.data ?: JSONObject()
        members = items.filter { it.kind == "member" && !it.data.flag("fam") }
            .map { Member(it.id, it.data.str("n") ?: it.id) }.sortedBy { it.id }
        pockets = items.filter { it.kind == "pocket" }.map {
            val d = it.data
            Pocket(it.id, d.str("n") ?: "Kantong", d.optInt("k", 7), d.num("start"), d.flag("save"), d.flag("archived"))
        }
        books = items.filter { it.kind == "biz" }.map {
            val d = it.data
            Book(it.id, d.str("n") ?: "Buku", d.str("kind") ?: "usaha", d.optInt("k", 7), d.str("pocket"), d, d.flag("archived"))
        }
        val defs = settings.optJSONArray("cats") ?: JSONArray()
        val defList = (0 until defs.length()).mapNotNull { defs.optJSONObject(it) }
        cats = applyCats(defList)
        deadCatTo = defList.filter { it.flag("del") && it.str("c") != "lain" }
            .map { Triple(it.str("t") ?: "", it.str("c") ?: "", it.str("to") ?: "lain") }

        val deleted = items.filter { it.kind == "del" }.map { it.id }.toSet()
        allTx = items.filter { it.kind == "tx" && it.id !in deleted }.map { toTx(it.data) }
            .filter { it.date.length >= 10 }
        visible = allTx.filter { !masked(it) }
        val delAt = items.filter { it.kind == "del" }.associate { it.id to it.u }
        shop = items.filter { it.kind == "x" && it.data.optString("t") == "shop" && (delAt[it.id] ?: 0L) < it.u }
            .map { ShopItem(it.id, it.data.str("n") ?: "Barang", it.data.num("price"), it.data.flag("done"), it.data) }
            .sortedBy { it.n.lowercase() }
    }

    private fun toTx(d: JSONObject): Tx {
        var cat = d.str("cat") ?: "lain"
        val tx = Tx(
            id = d.str("id") ?: "", type = d.str("type") ?: "out", amount = d.num("amount"), desc = d.str("desc") ?: "",
            cat = cat, pocket = d.str("pocket"), to = d.str("to"), date = d.str("date") ?: "", ts = d.num("ts"),
            by = d.str("by"), biz = d.str("biz"), dir = if (d.num("dir") < 0) -1 else 1, tfk = d.str("tfk"),
            qty = d.optDouble("qty", 0.0).let { if (it.isNaN()) 0.0 else it }, fee = d.flag("fee"),
            hidden = d.flag("hidden"), sealed = d.flag("sealed"), revealed = d.has("revealedAt") && !d.isNull("revealedAt"),
        )
        // Catatan in a deleted category show under the category they were moved to (remapCats on the phone).
        val ct = ctype(tx)
        deadCatTo.firstOrNull { it.first == ct && it.second == cat }?.let { r ->
            cat = if ((cats[ct] ?: emptyList()).any { it.id == r.third }) r.third else "lain"
            return tx.copy(cat = cat)
        }
        return tx
    }

    private fun applyCats(defs: List<JSONObject>): Map<String, List<Cat>> {
        val by = defs.associateBy { (it.str("t") ?: "") + ":" + (it.str("c") ?: "") }
        return Seed.TYPES.associateWith { t ->
            val list = mutableListOf<Cat>()
            seed.cats[t].orEmpty().forEach { c0 ->
                val d = by["$t:${c0.id}"]
                if (d != null && d.flag("del") && c0.id != "lain") return@forEach
                list += if (d != null) c0.copy(n = d.str("n") ?: c0.n, k = if (d.has("k")) d.optInt("k", c0.k) else c0.k, words = d.str("w") ?: "") else c0
            }
            defs.filter { it.str("t") == t && it.flag("custom") && !it.flag("del") }
                .sortedBy { it.num("at") }
                .forEach { d -> list += Cat(d.str("c") ?: "x", d.str("n") ?: "Kategori", d.optInt("k", list.size % 7), d.str("w") ?: "") }
            val li = list.indexOfFirst { it.id == "lain" }
            if (li >= 0 && li != list.lastIndex) list.add(list.removeAt(li))
            list
        }
    }

    val solo get() = settings.optString("who") == "solo"
    fun memName(id: String?) = members.firstOrNull { it.id == id }?.n ?: "—"
    val meName get() = memName(me)
    /** Income categories the owner keeps secret from the partner (Profil on the phone). */
    val hideCats: Set<String> = items.firstOrNull { it.kind == "member" && it.id == me }?.data?.optJSONArray("hideCats")
        ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    /** Sound choices set on the phone (Pengaturan → Suara), stored on this person's member row. */
    private val snd = items.firstOrNull { it.kind == "member" && it.id == me }?.data?.optJSONObject("snd")
    val soundOpen: String = snd?.optString("open")?.takeIf { it.isNotEmpty() } ?: "muara"
    val soundSave: String = snd?.optString("save")?.takeIf { it.isNotEmpty() } ?: "koin"

    fun book(id: String?) = books.firstOrNull { it.id == id }
    fun pocket(id: String?) = pockets.firstOrNull { it.id == id }
    fun pocketName(id: String?) = pocket(id)?.n ?: "Kantong terhapus"
    val activePockets get() = pockets.filter { !it.archived }
    fun isUsaha(biz: String?) = biz != null && book(biz)?.kind == "usaha"
    fun ctype(t: Tx) = when {
        t.type == "tf" -> "tf"
        t.biz != null && isUsaha(t.biz) -> if (t.type == "in") "bin" else "bout"
        else -> t.type
    }
    fun cat(type: String, id: String): Cat {
        val l = cats[type] ?: cats["out"].orEmpty()
        return l.firstOrNull { it.id == id } ?: l.lastOrNull() ?: Cat(id, id, 7)
    }
    fun catColorIndex(type: String, id: String): Int = if (id == "lain") 7 else cat(type, id).k % 7

    private fun masked(t: Tx): Boolean {
        if (t.sealed) return true
        if (t.by != null && t.by != me && (t.hidden || t.revealed)) return true
        return false
    }

    fun balances(): Map<String, Long> {
        val b = HashMap<String, Long>()
        pockets.forEach { b[it.id] = it.start }
        visible.forEach { t ->
            fun add(p: String?, d: Long) { if (p != null && b.containsKey(p)) b[p] = b[p]!! + d }
            when (t.type) {
                "loan" -> add(t.pocket, t.dir * t.amount)
                "in" -> add(t.pocket, t.amount)
                "out" -> add(t.pocket, -t.amount)
                else -> { add(t.pocket, -t.amount); add(t.to, t.amount) }
            }
        }
        return b
    }

    /** Household month totals (scope 'home' on the phone): business books stay out. */
    fun totals(mk: String): Totals {
        var hin = 0L; var hout = 0L; var n = 0
        val cats = HashMap<String, Long>()
        visible.forEach { t ->
            if (!t.date.startsWith(mk)) return@forEach
            if (t.biz != null && isUsaha(t.biz)) return@forEach
            n++
            when (t.type) {
                "in" -> hin += t.amount
                "out" -> { hout += t.amount; cats[t.cat] = (cats[t.cat] ?: 0L) + t.amount }
            }
        }
        return Totals(hin, hout, cats, n)
    }

    val budget get() = settings.num("budget")
    fun catBudgets(): Map<String, Long> {
        val o = settings.optJSONObject("cbud") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.num(it) }.filterValues { it > 0 }
    }

    private val kindMod = mapOf("usaha" to "usaha", "cicilan" to "utang", "piutang" to "utang", "investasi" to "emas")
    fun modOn(k: String): Boolean {
        val m = settings.optJSONObject("mods")
        if (m != null && m.has(k) && m.opt(k) is Boolean) return m.getBoolean(k)
        return books.any { kindMod[it.kind] == k && !it.raw.flag("sample") && !it.raw.flag("sample0") }
    }
    private fun kindOn(kind: String) = kindMod[kind]?.let { modOn(it) } ?: true
    val shownBooks get() = books.filter { !it.archived && kindOn(it.kind) }

    fun usaha(mk: String): List<Usaha> = shownBooks.filter { it.kind == "usaha" }.map { b ->
        var omzet = 0L; var biaya = 0L
        visible.forEach { t ->
            if (t.biz != b.id || !t.date.startsWith(mk)) return@forEach
            if (t.type == "in") omzet += t.amount else if (t.type == "out" && t.cat != "aset") biaya += t.amount
        }
        Usaha(b, omzet, biaya)
    }

    fun targets(): List<Target> {
        val bal = balances()
        return shownBooks.filter { it.kind == "tabungan" }.map { Target(it, bal[it.pocket] ?: 0L, it.raw.num("target")) }
    }
    fun targetFor(pocketId: String) = targets().firstOrNull { it.book.pocket == pocketId }

    fun cicilan(): List<Cicilan> = shownBooks.filter { it.kind == "cicilan" }.map { b ->
        val all = visible.filter { it.biz == b.id }
        val paid = all.filter { (it.type == "out" && !it.fee) || (it.type == "loan" && it.dir < 0) }.sumOf { it.amount }
        val cair = all.filter { it.type == "loan" && it.dir > 0 }.sumOf { it.amount }
        Cicilan(b, b.raw.num("total") + cair, paid, b.raw.num("inst"))
    }

    fun piutang(): List<Piutang> = shownBooks.filter { it.kind == "piutang" }.map { b ->
        var given = 0L; var back = 0L
        visible.forEach { t -> if (t.biz == b.id && t.type == "loan") { if (t.dir < 0) given += t.amount else back += t.amount } }
        Piutang(b, given, back)
    }

    fun invest(): List<Invest> = shownBooks.filter { it.kind == "investasi" }.map { b ->
        var qty = b.raw.optDouble("qty0", 0.0).let { if (it.isNaN()) 0.0 else it }
        var inv = b.raw.num("cost0")
        visible.forEach { t ->
            if (t.biz != b.id) return@forEach
            if (t.tfk == "beli") { qty += t.qty; inv += t.amount } else if (t.tfk == "jual") { qty -= t.qty; inv -= t.amount }
        }
        Invest(b, qty, inv, price(b), b.raw.str("unit") ?: "gram")
    }

    private fun price(b: Book): Long {
        if (b.raw.str("src") == "gold" && goldIdr > 0) return Math.round(goldIdr * (1 + b.raw.optDouble("premium", 0.0) / 100.0))
        val p = b.raw.num("price"); if (p > 0) return p
        val ph = b.raw.optJSONObject("ph") ?: return 0L
        val last = ph.keys().asSequence().sorted().lastOrNull() ?: return 0L
        return ph.num(last)
    }
    val needsGold get() = shownBooks.any { it.kind == "investasi" && it.raw.str("src") == "gold" }

    /** Pocket a new catatan goes to when nobody picks one (defaultPocket on the phone). */
    fun defaultPocket(type: String, cat: String, learned: String?): String? {
        fun ok(id: String?) = id != null && pocket(id)?.archived == false
        if (ok(learned)) return learned
        if (type == "in") mapOf("gaji" to "gaji", "usaha" to "usaha", "invest" to "emas", "freelance" to "side1")[cat]?.let { if (ok(it)) return it }
        if (type == "out" && cat == "modal" && ok("usaha")) return "usaha"
        return (activePockets.firstOrNull { !it.save } ?: activePockets.firstOrNull())?.id
    }

    fun recent(limit: Int): List<Tx> = visible.sortedWith(compareByDescending<Tx> { it.date }.thenByDescending { it.ts }).take(limit)

    companion object {
        fun monthKey(d: LocalDate = LocalDate.now()) = "%04d-%02d".format(d.year, d.monthValue)
    }
}
