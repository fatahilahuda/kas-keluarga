package io.github.fatahilahuda.muarakas.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private val BLN = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
private val BULAN = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
private val HARI = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")

fun dayLabel(iso: String): String {
    val d = try { LocalDate.parse(iso.take(10)) } catch (_: Exception) { return iso }
    val t = LocalDate.now()
    return when (d) {
        t -> "Hari ini"
        t.minusDays(1) -> "Kemarin"
        else -> HARI[d.dayOfWeek.value - 1] + ", " + d.dayOfMonth + " " + BLN[d.monthValue - 1]
    }
}

private fun syncText(s: SyncState): String = when (s) {
    SyncState.BUSY -> "Menyinkronkan…"
    SyncState.OFFLINE -> "Offline, nanti dicoba lagi"
    SyncState.ERROR -> "Belum tersinkron"
    SyncState.NOT_FOUND -> "Kode keluarga tidak ditemukan"
    else -> {
        val at = Store.lastSync
        if (at == 0L) "Belum sinkron" else {
            val m = (System.currentTimeMillis() - at) / 60000
            "Tersinkron " + when { m < 1 -> "baru saja"; m < 60 -> "$m mnt lalu"; m < 1440 -> "${m / 60} jam lalu"; else -> "${m / 1440} hari lalu" }
        } + (if (Store.pendingCount > 0) " · ${Store.pendingCount} menunggu" else "")
    }
}

// ---------------------------------------------------------------- pairing

@Composable
fun PairScreen() {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val secret = remember(attempt) { Store.newSecret() }
    val codeInput = rememberTextInput("Kode keluarga (12 huruf)") { t ->
        scope.launch {
            err = null
            val ok = try { Store.pairWithCode(t) } catch (e: Exception) { err = "Belum bisa terhubung. Periksa internet jam."; return@launch }
            if (!ok) err = "Kode keluarga tidak ditemukan."
        }
    }

    LaunchedEffect(attempt) {
        pin = null; err = null
        var p: String? = null
        for (i in 0 until 5) {
            val cand = Store.newPin()
            val ok = try { Store.pairOpen(cand, secret) } catch (e: RpcError) {
                err = if (e.status == 404) "Server belum siap untuk jam. Pakai kode keluarga dulu." else "Gagal membuat kode."; return@LaunchedEffect
            } catch (e: Exception) { err = "Jam belum online. Sambungkan ke HP atau Wi-Fi."; return@LaunchedEffect }
            if (ok) { p = cand; break }
        }
        val pp = p ?: run { err = "Gagal membuat kode."; return@LaunchedEffect }
        pin = pp
        val until = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < until) {
            delay(3000)
            try { if (Store.pairPoll(pp, secret)) return@LaunchedEffect } catch (_: Exception) {}
        }
        err = "Kode kedaluwarsa."; pin = null
    }

    Page {
        item { Heading("Sambungkan jam", "ke Muarakas di HP") }
        item {
            val p = pin
            if (p != null) Big(p.substring(0, 3) + " " + p.substring(3), Accent, 30)
            else if (err == null) CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = Accent)
        }
        item { Small(err ?: "Di HP: Muarakas → Pengaturan → Jam tangan, lalu ketik kode ini.", if (err != null) Warn else Muted) }
        if (err != null) item { ActionChip("Coba lagi", { attempt++ }, Ic.sync) }
        item { RowChip("Pakai kode keluarga", codeInput, secondary = "Dari halaman Sinkron di HP", icon = Ic.link) }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(k: Kas, onVoice: () -> Unit, onKeypad: () -> Unit, onTypeText: () -> Unit, onOpen: (String) -> Unit, onSync: () -> Unit) {
    val sync by Store.sync.collectAsState()
    val mk = Kas.monthKey()
    val t = k.totals(mk)
    val sisa = t.hin - t.hout
    val budget = k.budget
    Page {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().clickable { onOpen("summary") }) {
                Text(if (budget > 0) "Sisa anggaran " + BLN[LocalDate.now().monthValue - 1] else "Sisa " + BULAN[LocalDate.now().monthValue - 1],
                    color = Muted, fontSize = 12.sp)
                if (budget > 0) {
                    val left = budget - t.hout
                    Big(Fmt.short(left), if (left < 0) Bad else Ink, 24)
                    Bar((t.hout.toFloat() / budget).coerceIn(0f, 1f), if (t.hout > budget) Bad else if (t.hout >= budget * 0.8) Warn else Accent,
                        Modifier.padding(horizontal = 26.dp, vertical = 4.dp))
                } else Big(Fmt.short(sisa), if (sisa < 0) Bad else Ink, 24)
                Text("keluar " + Fmt.short(t.hout), color = Muted, fontSize = 11.sp)
            }
        }
        item { TwoButtons("Suara", Ic.mic, onVoice, "Nominal", Ic.keypad, onKeypad) }
        item { RowChip("Ketik", onTypeText, secondary = "misal: bakso 15rb", icon = Ic.note, bg = Color.Black) }
        item { RowChip("Ringkasan", { onOpen("summary") }, icon = Ic.chart, secondary = "Bulan ini & kategori") }
        item { RowChip("Riwayat", { onOpen("history") }, icon = Ic.list, secondary = k.recent(1).firstOrNull()?.let { (it.desc.ifBlank { k.cat(k.ctype(it), it.cat).n }) + " · " + Fmt.short(it.amount) }) }
        item { RowChip("Kantong", { onOpen("pockets") }, icon = Ic.wallet, secondary = "Total " + Fmt.short(k.balances().let { b -> k.activePockets.sumOf { b[it.id] ?: 0L } })) }
        if (k.shownBooks.isNotEmpty()) item { RowChip("Modul", { onOpen("modules") }, icon = Ic.box, secondary = "Usaha, target, utang, emas") }
        item { RowChip(syncText(sync), onSync, icon = Ic.sync, bg = Color.Black) }
        item { RowChip(if (k.solo) k.meName else "Jam ini milik " + k.meName, { onOpen("about") }, icon = Ic.user, bg = Color.Black) }
    }
}

// ---------------------------------------------------------------- catat

private val TYPE_LBL = mapOf("out" to "Keluar", "in" to "Masuk", "tf" to "Pindah saldo")

@Composable
fun KeypadScreen(draft: Store.Draft, onType: (String) -> Unit, onDone: (Long) -> Unit) {
    var v by remember { mutableLongStateOf(draft.amount) }
    val haptic = LocalHapticFeedback.current
    fun press(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        v = when (key) {
            "⌫" -> v / 10
            "000" -> if (v == 0L) 0L else if (v < 1_000_000_000L) v * 1000 else v
            else -> if (v < 10_000_000_000L) v * 10 + key.toLong() else v
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val col = when (draft.type) { "in" -> Accent; "tf" -> kColor(4); else -> Warn }
            Text(TYPE_LBL[draft.type] + " ▾", color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    onType(when (draft.type) { "out" -> "in"; "in" -> "tf"; else -> "out" })
                }.padding(horizontal = 8.dp, vertical = 2.dp))
            Text(if (v == 0L) "Rp 0" else Fmt.full(v), color = if (v == 0L) Muted else Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("000", "0", "⌫"))
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                rows.forEach { r ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        r.forEach { key ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Surface1).clickable { press(key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key == "⌫") Icon(Ic.del, contentDescription = "Hapus angka", tint = Muted, modifier = Modifier.size(18.dp))
                                else Text(key, color = Ink, fontSize = if (key.length > 1) 14.sp else 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.width(76.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (v > 0) Accent else Surface2)
                    .clickable(enabled = v > 0) { onDone(v) },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.check, contentDescription = "Lanjut", tint = if (v > 0) Color.Black else Muted, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun CatScreen(k: Kas, draft: Store.Draft, onPick: (String) -> Unit) {
    val type = draft.type
    val cats = k.cats[type].orEmpty()
    val ranked = if (draft.desc.isNotBlank()) Store.ai()?.rank(type, draft.desc)?.take(3)?.map { it.first } ?: emptyList() else emptyList()
    Page {
        item { Heading(if (type == "in") "Kategori pemasukan" else "Kategori", if (draft.amount > 0) Fmt.full(draft.amount) else null) }
        if (ranked.isNotEmpty()) {
            item { Small("Saran") }
            items(ranked) { id -> val c = k.cat(type, id); RowChip(c.n, { onPick(c.id) }, dot = kColor(k.catColorIndex(type, c.id)), bg = Surface2) }
            item { Small("Semua") }
        }
        items(cats) { c -> RowChip(c.n, { onPick(c.id) }, dot = kColor(k.catColorIndex(type, c.id)), value = if (c.id == draft.cat) "✓" else null, valueColor = Accent) }
    }
}

@Composable
fun PocketPickScreen(k: Kas, title: String, selected: String?, exclude: String?, onPick: (String) -> Unit) {
    val b = k.balances()
    Page {
        item { Heading(title) }
        items(k.activePockets.filter { it.id != exclude }) { p ->
            RowChip(p.n, { onPick(p.id) }, dot = kColor(p.k), value = Fmt.short(b[p.id] ?: 0L, rp = false),
                secondary = if (p.id == selected) "Dipilih" else if (p.save) "Tabungan" else null)
        }
    }
}

@Composable
fun ConfirmScreen(
    k: Kas, d: Store.Draft,
    onCat: () -> Unit, onPocket: () -> Unit, onTo: () -> Unit, onAmount: () -> Unit,
    onDate: () -> Unit, onNote: () -> Unit, onSave: () -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pocket = if (d.type == "tf") d.pocket else d.pocket ?: k.defaultPocket(d.type, d.cat ?: "lain", Store.learnedPocket(d.type, d.cat ?: "lain"))
    val dateLbl = listOf("Hari ini", "Kemarin", "2 hari lalu")[d.daysAgo]
    Page {
        item { Heading(TYPE_LBL[d.type] ?: "") }
        item {
            Text(Fmt.full(d.amount), color = if (d.type == "in") Accent else Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable { onAmount() })
        }
        if (d.type == "tf") {
            item { RowChip("Dari", onPocket, value = k.pocketName(pocket), icon = Ic.wallet) }
            item { RowChip("Ke", onTo, value = k.pocketName(d.to), icon = Ic.swap) }
        } else {
            val c = k.cat(d.type, d.cat ?: "lain")
            item { RowChip(c.n, onCat, secondary = "Kategori", dot = kColor(k.catColorIndex(d.type, c.id))) }
            item { RowChip(if (pocket != null) k.pocketName(pocket) else "Pilih kantong", onPocket, secondary = "Kantong", icon = Ic.wallet) }
        }
        item { RowChip(dateLbl, onDate, secondary = "Tanggal · ketuk untuk ganti", icon = Ic.cal) }
        item { RowChip(d.desc.ifBlank { "Tambah catatan" }, onNote, secondary = if (d.desc.isBlank()) "Opsional" else "Catatan", icon = Ic.note) }
        if (d.type == "in" && (d.cat ?: "") in k.hideCats) item { Small("Pemasukan ini otomatis dirahasiakan dari pasangan.") }
        item {
            ActionChip("Simpan", {
                if (!saving) { saving = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSave() }
            }, Ic.check, enabled = !saving && (d.type != "tf" || (d.pocket != null && d.to != null)))
        }
    }
}

@Composable
fun DoneScreen(k: Kas, d: Store.Draft?, onBack: () -> Unit) {
    LaunchedEffect(Unit) { delay(1600); onBack() }
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Icon(Ic.check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(30.dp))
            }
            Gap(8)
            Text("Tersimpan", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (d != null) Text(
                Fmt.full(d.amount) + " · " + if (d.type == "tf") k.pocketName(d.to) else k.cat(d.type, d.cat ?: "lain").n,
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- ringkasan

@Composable
fun SummaryScreen(k: Kas) {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    val mk = "%04d-%02d".format(ym.year, ym.monthValue)
    val t = k.totals(mk)
    val sisa = t.hin - t.hout
    val budget = k.budget
    val isNow = ym == YearMonth.now()
    val cats = t.cats.entries.sortedByDescending { it.value }
    val maxCat = cats.firstOrNull()?.value ?: 1L
    val cb = k.catBudgets()
    val status = when {
        t.hin <= 0 && t.hout <= 0 -> "Belum ada catatan" to Muted
        t.hin <= 0 -> "Belum ada pemasukan" to Bad
        t.hout > t.hin -> "Defisit" to Bad
        t.hout >= t.hin * 0.8 -> "Waspada" to Warn
        else -> "Sehat" to Accent
    }
    Page {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Text("‹", color = Accent, fontSize = 20.sp, modifier = Modifier.clickable { ym = ym.minusMonths(1) }.padding(horizontal = 10.dp))
                Text(BULAN[ym.monthValue - 1] + if (ym.year != YearMonth.now().year) " " + ym.year else "", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("›", color = if (isNow) Surface2 else Accent, fontSize = 20.sp, modifier = Modifier.clickable(enabled = !isNow) { ym = ym.plusMonths(1) }.padding(horizontal = 10.dp))
            }
        }
        item { Big(Fmt.short(sisa), if (sisa < 0) Bad else Ink) }
        item { Text(status.first, color = status.second, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        item { RowChip("Masuk", {}, value = Fmt.short(t.hin), valueColor = Accent, dot = Accent) }
        item { RowChip("Keluar", {}, value = Fmt.short(t.hout), dot = Warn) }
        if (budget > 0) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Small("Anggaran " + Fmt.short(budget), center = false)
                Bar(t.hout.toFloat() / budget, if (t.hout > budget) Bad else if (t.hout >= budget * 0.8) Warn else Accent)
                if (isNow) {
                    val today = LocalDate.now()
                    val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
                    val left = budget - t.hout
                    Small(if (left > 0) "Jatah harian " + Fmt.short(left / daysLeft) else "Anggaran terlewati " + Fmt.short(-left), center = false)
                }
            }
        }
        if (cats.isNotEmpty()) item { Small("Pengeluaran per kategori") }
        items(cats.take(8)) { e ->
            val c = k.cat("out", e.key)
            val lim = cb[e.key]
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(kColor(k.catColorIndex("out", c.id)), 8.dp)
                    Text(" " + c.n, color = Ink, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(Fmt.short(e.value, rp = false), color = if (lim != null && e.value >= lim) Bad else Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Bar(if (lim != null) e.value.toFloat() / lim else e.value.toFloat() / maxCat,
                    if (lim != null && e.value >= lim) Bad else if (lim != null && e.value >= lim * 0.8) Warn else kColor(k.catColorIndex("out", c.id)))
                if (lim != null) Text("batas " + Fmt.short(lim), color = Muted, fontSize = 10.sp)
            }
        }
        if (k.modOn("usaha") && k.usaha(mk).isNotEmpty()) {
            item { Small("Usaha") }
            items(k.usaha(mk)) { u -> RowChip(u.book.n, {}, secondary = "omzet " + Fmt.short(u.omzet), value = Fmt.short(u.laba), valueColor = if (u.laba < 0) Bad else Accent, dot = kColor(u.book.k)) }
        }
    }
}

// ---------------------------------------------------------------- riwayat

@Composable
fun HistoryScreen(k: Kas, onOpen: (String) -> Unit) {
    var limit by remember { mutableIntStateOf(40) }
    val list = k.recent(limit)
    Page {
        item { Heading("Riwayat") }
        if (list.isEmpty()) item { Small("Belum ada catatan.") }
        var lastDay = ""
        list.forEach { t ->
            if (t.date != lastDay) { lastDay = t.date; val lbl = dayLabel(t.date); item { Small(lbl, Accent) } }
            item { TxChip(k, t) { onOpen(t.id) } }
        }
        if (list.size >= limit) item { RowChip("Muat lebih banyak", { limit += 40 }, bg = Color.Black) }
    }
}

@Composable
fun TxChip(k: Kas, t: Tx, onClick: () -> Unit) {
    val ct = k.ctype(t)
    when (t.type) {
        "tf" -> RowChip(t.desc.ifBlank { "Pindah saldo" }, onClick, secondary = k.pocketName(t.pocket) + " → " + k.pocketName(t.to),
            value = Fmt.short(t.amount, rp = false), valueColor = Muted, icon = Ic.swap)
        "loan" -> RowChip(t.desc.ifBlank { "Pinjaman" }, onClick, secondary = k.book(t.biz)?.n ?: k.pocketName(t.pocket),
            value = (if (t.dir > 0) "+" else "−") + Fmt.short(t.amount, rp = false), valueColor = Muted, icon = Ic.swap)
        else -> {
            val c = k.cat(ct, t.cat)
            val who = if (!k.solo && t.by != null && t.by != k.me) k.memName(t.by) + " · " else ""
            RowChip(t.desc.ifBlank { c.n }, onClick, secondary = who + (k.book(t.biz)?.n?.let { "$it · " } ?: "") + c.n,
                value = (if (t.type == "in") "+" else "") + Fmt.short(t.amount, rp = false),
                valueColor = if (t.type == "in") Accent else Ink, dot = kColor(k.catColorIndex(ct, t.cat)))
        }
    }
}

@Composable
fun TxScreen(k: Kas, id: String, onDelete: () -> Unit) {
    val t = k.visible.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    Page {
        if (t == null) { item { Small("Catatan ini sudah dihapus.") }; return@Page }
        val ct = k.ctype(t)
        item { Heading(dayLabel(t.date)) }
        item { Big(Fmt.full(t.amount), if (t.type == "in") Accent else Ink, 22) }
        item { Small(t.desc.ifBlank { if (t.type == "tf") "Pindah saldo" else k.cat(ct, t.cat).n }, Ink) }
        if (t.type == "tf") {
            item { RowChip(k.pocketName(t.pocket) + " → " + k.pocketName(t.to), {}, icon = Ic.swap) }
        } else {
            item { RowChip(k.cat(ct, t.cat).n, {}, secondary = "Kategori", dot = kColor(k.catColorIndex(ct, t.cat))) }
            item { RowChip(k.pocketName(t.pocket), {}, secondary = "Kantong", icon = Ic.wallet) }
        }
        k.book(t.biz)?.let { b -> item { RowChip(b.n, {}, secondary = "Buku", icon = Ic.box) } }
        if (!k.solo) item { RowChip(k.memName(t.by), {}, secondary = "Dicatat oleh", icon = Ic.user) }
        if (t.by == k.me) item {
            if (!confirm) RowChip("Hapus catatan", { confirm = true }, icon = Ic.trash, bg = Color.Black)
            else ActionChip("Ya, hapus", onDelete, Ic.trash, color = Bad)
        }
    }
}

// ---------------------------------------------------------------- kantong & modul

@Composable
fun PocketsScreen(k: Kas) {
    val b = k.balances()
    val ps = k.activePockets
    Page {
        item { Heading("Kantong", "Total " + Fmt.full(ps.sumOf { b[it.id] ?: 0L })) }
        items(ps) { p ->
            val tgt = k.targetFor(p.id)
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(kColor(p.k))
                    Text(" " + p.n, color = Ink, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(Fmt.short(b[p.id] ?: 0L, rp = false), color = if ((b[p.id] ?: 0L) < 0) Bad else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (tgt != null && tgt.tgt > 0) {
                    Gap(4)
                    Bar((tgt.pct / 100).toFloat(), kColor(p.k))
                    Text("${Math.round(tgt.pct)}% dari " + Fmt.short(tgt.tgt), color = Muted, fontSize = 10.sp)
                } else if (p.save) Text("Tabungan", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun ModulesScreen(k: Kas) {
    val mk = Kas.monthKey()
    val us = if (k.modOn("usaha")) k.usaha(mk) else emptyList()
    val tg = k.targets()
    val ci = k.cicilan()
    val pi = k.piutang()
    val iv = k.invest()
    Page {
        item { Heading("Modul") }
        if (us.isNotEmpty()) {
            item { Small("Usaha · " + BULAN[LocalDate.now().monthValue - 1], Accent) }
            items(us) { u -> RowChip(u.book.n, {}, secondary = "omzet " + Fmt.short(u.omzet) + " · biaya " + Fmt.short(u.biaya), value = Fmt.short(u.laba, rp = false), valueColor = if (u.laba < 0) Bad else Accent, dot = kColor(u.book.k)) }
        }
        if (tg.isNotEmpty()) {
            item { Small("Target tabungan", Accent) }
            items(tg) { x ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Row { Text(x.book.n, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1); Text("${Math.round(x.pct)}%", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Bar((x.pct / 100).toFloat(), kColor(x.book.k))
                    Text(Fmt.short(x.bal) + " dari " + Fmt.short(x.tgt), color = Muted, fontSize = 10.sp)
                }
            }
        }
        if (ci.isNotEmpty() || pi.isNotEmpty()) item { Small("Utang & piutang", Accent) }
        items(ci) { c -> RowChip(c.book.n, {}, secondary = if (c.rem <= 0) "Lunas" else if (c.inst > 0) "±" + Math.ceil(c.rem.toDouble() / c.inst).toInt() + " bulan lagi" else "Sisa utang", value = Fmt.short(c.rem, rp = false), valueColor = if (c.rem > 0) Warn else Accent, dot = kColor(c.book.k)) }
        items(pi) { p -> RowChip(p.book.n, {}, secondary = "Belum kembali", value = Fmt.short(p.out, rp = false), dot = kColor(p.book.k)) }
        if (iv.isNotEmpty()) {
            item { Small("Emas & investasi", Accent) }
            items(iv) { v ->
                RowChip(v.book.n, {}, secondary = Fmt.qty(v.qty) + " " + v.unit + if (v.price > 0) (if (v.gain >= 0) " · +" else " · ") + Fmt.short(v.gain) else " · harga belum ada",
                    value = if (v.price > 0) Fmt.short(v.value, rp = false) else "—", valueColor = if (v.gain < 0) Bad else Ink, dot = kColor(v.book.k))
            }
        }
        if (us.isEmpty() && tg.isEmpty() && ci.isEmpty() && pi.isEmpty() && iv.isEmpty()) item { Small("Belum ada modul yang aktif. Aktifkan di HP: Pengaturan → Modul.") }
    }
}

@Composable
fun AboutScreen(k: Kas, onMe: (String) -> Unit, onUnpair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Page {
        item { Heading("Jam ini", if (k.solo) "Dipakai sendiri" else "Milik " + k.meName) }
        if (!k.solo && k.members.size > 1) {
            item { Small("Catatan dari jam tercatat atas nama:") }
            items(k.members) { m -> RowChip(m.n, { onMe(m.id) }, icon = Ic.user, value = if (m.id == k.me) "✓" else null, valueColor = Accent) }
        }
        item {
            if (!confirm) RowChip("Putuskan jam", { confirm = true }, icon = Ic.link, bg = Color.Black)
            else ActionChip("Ya, putuskan", onUnpair, Ic.link, color = Bad)
        }
        item { Small("Data tetap aman di HP dan server. Jam hanya lupa kodenya.") }
    }
}
