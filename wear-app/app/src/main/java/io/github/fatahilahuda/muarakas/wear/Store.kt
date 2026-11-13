package io.github.fatahilahuda.muarakas.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate

class RpcError(val status: Int, message: String) : Exception(message)

enum class SyncState { IDLE, BUSY, OK, OFFLINE, ERROR, NOT_FOUND }

/**
 * Everything the watch keeps: the family code, the synced rows and catatan still waiting to be
 * sent. Talks to the same Supabase RPCs as the phone (kas_pull / kas_push).
 */
object Store {
    private const val SB_URL = "https://atblbmkqvcwlukwrwkra.supabase.co"
    private const val SB_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImF0YmxibWtxdmN3bHVrd3J3a3JhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTIyNDM2NDYsImV4cCI6MjEwNzgxOTY0Nn0.HqqmpFLFXUfJ8FBdfOSHJ-lP7uMzotgnM1qAY86Nfpk"

    private lateinit var app: Context
    private lateinit var seed: Seed
    private val lock = Mutex()

    private var code: String? = null
    private var me: String = "m1"
    private var rev: Long = 0
    private var syncedAt: Long = 0
    private val items = LinkedHashMap<String, Item>()
    private val pending = mutableListOf<JSONObject>()
    private var learned = JSONObject()
    private var catPocket = JSONObject()
    private var goldIdr: Long = 0
    private var goldAt: Long = 0

    private val _kas = MutableStateFlow<Kas?>(null)
    val kas: StateFlow<Kas?> = _kas
    private val _sync = MutableStateFlow(SyncState.IDLE)
    val sync: StateFlow<SyncState> = _sync
    val lastSync get() = syncedAt
    val pendingCount get() = pending.size

    fun init(ctx: Context) {
        if (this::app.isInitialized) return
        synchronized(this) {
            if (this::app.isInitialized) return
            app = ctx.applicationContext
            seed = Seed(JSONObject(app.assets.open("seed.json").bufferedReader().use { it.readText() }))
            load()
        }
    }

    val paired get() = code != null
    fun ai(): Ai? = _kas.value?.let { Ai(it, learned) }
    fun learnedPocket(type: String, cat: String): String? = catPocket.optJSONObject(type)?.optString(cat)?.takeIf { it.isNotEmpty() }

    // ---------- storage ----------
    private val file get() = File(app.filesDir, "kas.json")

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            code = o.optString("code").takeIf { it.isNotEmpty() }
            me = o.optString("me", "m1")
            rev = o.optLong("rev")
            syncedAt = o.optLong("syncedAt")
            learned = o.optJSONObject("learned") ?: JSONObject()
            catPocket = o.optJSONObject("catPocket") ?: JSONObject()
            goldIdr = o.optLong("goldIdr"); goldAt = o.optLong("goldAt")
            val a = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                val it = Item(r.getString("kind"), r.getString("id"), r.optLong("u"), r.optJSONObject("data") ?: JSONObject())
                items[it.kind + ":" + it.id] = it
            }
            val p = o.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until p.length()) pending += p.getJSONObject(i)
        } catch (e: Exception) {
            // A broken file is not worth crashing over; the next pull rebuilds it.
            items.clear(); pending.clear(); rev = 0
        }
        rebuild()
    }

    private fun persist() {
        val o = JSONObject()
        o.put("code", code ?: ""); o.put("me", me); o.put("rev", rev); o.put("syncedAt", syncedAt)
        o.put("learned", learned); o.put("catPocket", catPocket); o.put("goldIdr", goldIdr); o.put("goldAt", goldAt)
        val a = JSONArray()
        items.values.forEach { a.put(JSONObject().put("kind", it.kind).put("id", it.id).put("u", it.u).put("data", it.data)) }
        o.put("items", a)
        o.put("pending", JSONArray(pending))
        val tmp = File(app.filesDir, "kas.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun rebuild() {
        _kas.value = if (code == null) null else Kas(seed, items.values, me, goldIdr)
    }

    private fun refreshSurfaces() {
        try {
            val u = TileService.getUpdater(app)
            u.requestUpdate(KasTileService::class.java)
            u.requestUpdate(KasCatTileService::class.java)
            u.requestUpdate(KasPocketTileService::class.java)
        } catch (_: Exception) {}
        try {
            ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, KasComplicationService::class.java)).requestUpdateAll()
        } catch (_: Exception) {}
    }

    // ---------- network ----------
    private fun rpc(fn: String, body: JSONObject): String = http("$SB_URL/rest/v1/rpc/$fn", body)

    private fun http(url: String, body: JSONObject?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 25000
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("apikey", SB_KEY)
                c.setRequestProperty("Authorization", "Bearer $SB_KEY")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val st = c.responseCode
            val text = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (st !in 200..299) {
                val msg = try { JSONObject(text).optString("message", text) } catch (_: Exception) { text }
                throw RpcError(st, msg)
            }
            return text
        } finally { c.disconnect() }
    }

    // ---------- pairing ----------
    private val rnd = SecureRandom()
    fun newPin(): String = (100000 + rnd.nextInt(900000)).toString()
    fun newSecret(): String {
        val a = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        return (1..32).map { a[rnd.nextInt(a.length)] }.joinToString("")
    }

    suspend fun pairOpen(pin: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        rpc("kas_watch_new", JSONObject().put("p_pin", pin).put("p_secret", secret)).trim() == "true"
    }

    /** Returns true once the phone has handed over the family code. */
    suspend fun pairPoll(pin: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        val a = JSONArray(rpc("kas_watch_claim", JSONObject().put("p_pin", pin).put("p_secret", secret)).ifBlank { "[]" })
        if (a.length() == 0) return@withContext false
        val r = a.getJSONObject(0)
        connect(r.getString("code"), r.optString("me").ifEmpty { "m1" })
        true
    }

    /** Fallback: type the 12-character family code shown in the phone's Sinkron page. */
    suspend fun pairWithCode(raw: String): Boolean = withContext(Dispatchers.IO) {
        val c = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (c.length != 12) return@withContext false
        if (rpc("kas_exists", JSONObject().put("p_code", c)).trim() != "true") return@withContext false
        connect(c, "m1")
        true
    }

    private suspend fun connect(c: String, who: String) {
        lock.withLock {
            code = c; me = who; rev = 0; items.clear(); pending.clear(); syncedAt = 0
            persist(); rebuild()
        }
        syncNow()
    }

    suspend fun setMe(id: String) = lock.withLock { me = id; persist(); rebuild(); refreshSurfaces() }

    suspend fun unpair() = lock.withLock {
        code = null; items.clear(); pending.clear(); rev = 0; syncedAt = 0
        persist(); rebuild(); refreshSurfaces()
    }

    // ---------- sync ----------
    suspend fun syncNow(): SyncState = withContext(Dispatchers.IO) {
        val c = code ?: return@withContext SyncState.IDLE
        if (_sync.value == SyncState.BUSY) return@withContext SyncState.BUSY
        _sync.value = SyncState.BUSY
        val result = try {
            lock.withLock {
                pushPending(c)
                pull(c)
                syncedAt = System.currentTimeMillis()
                persist()
            }
            maybeGold()
            SyncState.OK
        } catch (e: RpcError) {
            if (e.message?.contains("family not found") == true) SyncState.NOT_FOUND else SyncState.ERROR
        } catch (e: java.io.IOException) {
            SyncState.OFFLINE
        } catch (e: Exception) {
            SyncState.ERROR
        }
        rebuild(); refreshSurfaces()
        _sync.value = result
        result
    }

    private fun pull(c: String) {
        for (round in 0 until 50) {
            val a = JSONArray(rpc("kas_pull", JSONObject().put("p_code", c).put("p_since", rev)).ifBlank { "[]" })
            if (a.length() == 0) return
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                val d = r.opt("data")
                val it = Item(r.getString("kind"), r.getString("id"), r.optLong("u"), if (d is JSONObject) d else JSONObject())
                items[it.kind + ":" + it.id] = it
                rev = maxOf(rev, r.optLong("rev"))
            }
            if (a.length() < 1000) return
        }
    }

    private fun pushPending(c: String) {
        while (pending.isNotEmpty()) {
            val chunk = pending.take(200)
            rpc("kas_push", JSONObject().put("p_code", c).put("p_items", JSONArray(chunk)))
            repeat(chunk.size) { pending.removeAt(0) }
            persist()
        }
    }

    private fun maybeGold() {
        val k = _kas.value ?: return
        if (!k.needsGold || System.currentTimeMillis() - goldAt < 3 * 3600_000L) return
        try {
            val usd = JSONObject(http("https://api.gold-api.com/price/XAU", null)).optDouble("price")
            val rate = JSONObject(http("https://open.er-api.com/v6/latest/USD", null)).optJSONObject("rates")?.optDouble("IDR") ?: 0.0
            if (usd > 0 && rate > 0) { goldIdr = Math.round(usd * rate / 31.1034768); goldAt = System.currentTimeMillis(); persist() }
        } catch (_: Exception) {}
    }

    // ---------- writing ----------
    private fun uid(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) +
        (1..6).map { "0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)] }.joinToString("")

    private fun queue(kind: String, id: String, u: Long, data: JSONObject) {
        items["$kind:$id"] = Item(kind, id, u, data)
        pending += JSONObject().put("kind", kind).put("id", id).put("u", u).put("data", data)
    }

    data class Draft(
        val type: String = "out",       // out, in, tf
        val amount: Long = 0,
        val desc: String = "",
        val cat: String? = null,
        val pocket: String? = null,
        val to: String? = null,
        val daysAgo: Int = 0,
    )

    /** Saves a catatan the same shape the phone's Catat form writes, then syncs in the background. */
    suspend fun add(d: Draft): Tx? {
        val k = _kas.value ?: return null
        lock.withLock {
            val now = System.currentTimeMillis()
            val date = LocalDate.now().minusDays(d.daysAgo.toLong()).toString()
            val o = JSONObject().put("id", uid()).put("type", d.type).put("amount", d.amount)
                .put("date", date).put("ts", now).put("u", now).put("by", me)
            if (d.type == "tf") {
                o.put("cat", "tf").put("pocket", d.pocket).put("to", d.to)
                    .put("desc", d.desc.ifBlank { "Pindah ke " + k.pocketName(d.to) })
            } else {
                val cat = d.cat ?: "lain"
                o.put("cat", cat).put("pocket", d.pocket ?: k.defaultPocket(d.type, cat, learnedPocket(d.type, cat)))
                    .put("desc", d.desc.replaceFirstChar { it.uppercase() })
                if (d.type == "in" && cat in k.hideCats) o.put("hidden", true)
                learn(d.type, d.desc, cat, d.pocket)
            }
            queue("tx", o.getString("id"), now, o)
            persist(); rebuild(); refreshSurfaces()
        }
        return _kas.value?.visible?.maxByOrNull { it.ts }
    }

    /** Ticks or unticks a shopping-list item; the phone sees it on its next sync. */
    suspend fun toggleShop(id: String) {
        lock.withLock {
            val it = _kas.value?.shop?.firstOrNull { s -> s.id == id } ?: return
            val now = System.currentTimeMillis()
            val d = JSONObject(it.raw.toString()).put("done", !it.done).put("u", now)
            queue("x", id, now, d)
            persist(); rebuild(); refreshSurfaces()
        }
        syncSoon()
    }

    /** "telur, minyak dan gula" → three items, same split as the phone. */
    suspend fun addShop(text: String): Int {
        val names = text.split(Regex("\\s*(?:,|;|\\bdan\\b|\\bsama\\b|\\bserta\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return 0
        lock.withLock {
            names.forEach { n ->
                val now = System.currentTimeMillis()
                val id = "s" + uid()
                queue("x", id, now, JSONObject().put("id", id).put("t", "shop").put("n", n.replaceFirstChar { c -> c.uppercase() })
                    .put("price", 0).put("done", false).put("by", me).put("u", now))
            }
            persist(); rebuild(); refreshSurfaces()
        }
        syncSoon()
        return names.size
    }

    private fun syncSoon() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { syncNow() }
    }

    suspend fun delete(id: String) {
        lock.withLock {
            val now = System.currentTimeMillis()
            queue("del", id, now, JSONObject().put("ts", now))
            persist(); rebuild(); refreshSurfaces()
        }
    }

    private fun learn(type: String, text: String, cat: String, pocket: String?) {
        if (pocket != null) catPocket.put(type, (catPocket.optJSONObject(type) ?: JSONObject()).put(cat, pocket))
        val k = _kas.value ?: return
        val fs = Ai(k, learned).feats(text).distinct()
        if (fs.isEmpty()) return
        val t = learned.optJSONObject(type) ?: JSONObject().also { learned.put(type, it) }
        val c = t.optJSONObject(cat) ?: JSONObject().also { t.put(cat, it) }
        fs.forEach { c.put(it, c.optInt(it) + 1) }
    }
}
