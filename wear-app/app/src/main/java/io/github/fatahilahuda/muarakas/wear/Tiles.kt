package io.github.fatahilahuda.muarakas.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate

/** A name, an amount and a palette colour: one row on the Kategori or Kantong tile. */
data class TRow(val name: String, val amount: Long, val color: Int)

/** Everything the tiles and the complication show, read once from the store. */
data class TileData(
    val paired: Boolean,
    val month: String,
    val out: Long,
    val inc: Long,
    val budget: Long,
    val cats: List<TRow>,
    val pockets: List<TRow>,
    val total: Long,
) {
    val left get() = if (budget > 0) budget - out else inc - out
    val frac get() = if (budget > 0) out.toFloat() / budget else if (inc > 0) out.toFloat() / inc else 0f

    companion object {
        private val BLN = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
        private fun month() = BLN[LocalDate.now().monthValue - 1]

        fun load(ctx: Context): TileData {
            Store.init(ctx)
            val k = Store.kas.value ?: return TileData(false, month(), 0, 0, 0, emptyList(), emptyList(), 0)
            return from(k)
        }

        fun from(k: Kas): TileData {
            val t = k.totals(Kas.monthKey())
            val cats = t.cats.entries.sortedByDescending { it.value }.take(3)
                .map { TRow(k.cat("out", it.key).n, it.value, TileStyle.k(k.catColorIndex("out", it.key))) }
            val b = k.balances()
            val ps = k.activePockets.map { TRow(it.n, b[it.id] ?: 0L, TileStyle.k(it.k)) }
            return TileData(true, month(), t.hout, t.hin, k.budget, cats, ps.sortedByDescending { it.amount }.take(3), ps.sumOf { it.amount })
        }

        fun sample() = TileData(
            true, "November", 1_284_000, 6_000_000, 4_000_000,
            listOf(TRow("Makan & Minum", 612_000, TileStyle.k(0)), TRow("Transportasi", 318_000, TileStyle.k(3)), TRow("Jajan & Nongkrong", 204_000, TileStyle.k(1))),
            listOf(TRow("Rekening", 8_450_000, TileStyle.k(1)), TRow("Tabungan Haji", 3_000_000, TileStyle.k(4)), TRow("Dompet", 215_000, TileStyle.k(0))),
            11_665_000,
        )
    }
}

object TileStyle {
    val ACCENT = 0xFF3BB3AB.toInt()
    val INK = 0xFFE5EDEA.toInt()
    val MUTED = 0xFF8FA39D.toInt()
    val SURFACE = 0xFF22302B.toInt()
    val BAD = 0xFFE57B73.toInt()
    val WARN = 0xFFE3AE4D.toInt()
    val BLACK = 0xFF000000.toInt()
    private val K = longArrayOf(0xFF7F96FF, 0xFFE9A866, 0xFF4FC1B4, 0xFFD97AB3, 0xFFA496F0, 0xFFE0C25A, 0xFF8DBE66, 0xFF6F7F7A)
    fun k(i: Int) = K[((i % 8) + 8) % 8].toInt()
}

/**
 * Tile layouts, built from plain data so they can be rendered in tests too.
 * Tiles cannot scroll on Wear OS, so the content is split over three tiles the user swipes between.
 */
object TileLayouts {
    const val RES_VERSION = "2"
    private const val W = 136f // content width that fits the smallest round watches

    fun resources(): ResourceBuilders.Resources {
        fun img(id: Int) = ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build()).build()
        return ResourceBuilders.Resources.Builder().setVersion(RES_VERSION)
            .addIdToImageMapping("mark", img(R.drawable.mark))
            .addIdToImageMapping("mic", img(R.drawable.ic_mic))
            .addIdToImageMapping("keypad", img(R.drawable.ic_keypad))
            .build()
    }

    fun launch(pkg: String, id: String, go: String): M.Clickable = M.Clickable.Builder()
        .setId(id)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(pkg)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping("go", ActionBuilders.AndroidStringExtra.Builder().setValue(go).build())
                    .build(),
            ).build(),
        ).build()

    private fun text(s: String, size: Float, color: Int, weight: Int = L.FONT_WEIGHT_NORMAL, lines: Int = 1): L.LayoutElement =
        L.Text.Builder().setText(s).setMaxLines(lines)
            .setOverflow(L.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(L.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).setWeight(weight).build())
            .build()

    private fun gap(h: Float, w: Float = 1f): L.LayoutElement = L.Spacer.Builder().setHeight(dp(h)).setWidth(dp(w)).build()

    private fun image(id: String, size: Float, tint: Int? = null): L.LayoutElement {
        val b = L.Image.Builder().setResourceId(id).setWidth(dp(size)).setHeight(dp(size))
        if (tint != null) b.setColorFilter(L.ColorFilter.Builder().setTint(argb(tint)).build())
        return b.build()
    }

    private fun circleButton(icon: String, bg: Int, fg: Int, click: M.Clickable, size: Float = 46f): L.LayoutElement =
        L.Box.Builder().setWidth(dp(size)).setHeight(dp(size))
            .setModifiers(
                M.Modifiers.Builder().setClickable(click)
                    .setBackground(M.Background.Builder().setColor(argb(bg)).setCorner(M.Corner.Builder().setRadius(dp(size / 2)).build()).build())
                    .build(),
            )
            .addContent(image(icon, size * 0.46f, fg))
            .build()

    private fun ring(frac: Float, color: Int): List<L.LayoutElement> {
        fun arc(len: Float, c: Int) = L.Arc.Builder()
            .setAnchorAngle(degrees(0f)).setAnchorType(L.ARC_ANCHOR_START)
            .addContent(L.ArcLine.Builder().setLength(degrees(len)).setThickness(dp(6f)).setColor(argb(c)).build())
            .build()
        val out = mutableListOf<L.LayoutElement>(arc(360f, TileStyle.SURFACE))
        if (frac > 0f) out += arc(360f * frac.coerceIn(0.015f, 1f), color)
        return out
    }

    private fun root(click: M.Clickable, vararg content: L.LayoutElement): L.LayoutElement {
        val b = L.Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
            .setModifiers(M.Modifiers.Builder().setClickable(click).build())
        content.forEach { b.addContent(it) }
        return b.build()
    }

    private fun short(n: Long) = Fmt.short(n)

    fun notPaired(pkg: String): L.LayoutElement = root(
        launch(pkg, "open", "home"),
        L.Column.Builder().setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
            .addContent(image("mark", 26f)).addContent(gap(8f))
            .addContent(text("Ketuk untuk menyambungkan", 13f, TileStyle.INK, L.FONT_WEIGHT_MEDIUM, 2))
            .addContent(text("Muarakas ke HP", 12f, TileStyle.MUTED))
            .build(),
    )

    /** Tile 1: budget ring, what's left, and the two quick-record buttons. */
    fun budget(pkg: String, d: TileData): L.LayoutElement {
        if (!d.paired) return notPaired(pkg)
        val tone = when { d.left < 0 || d.frac > 1f -> TileStyle.BAD; d.frac >= 0.8f -> TileStyle.WARN; else -> TileStyle.ACCENT }
        val col = L.Column.Builder().setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
            .addContent(image("mark", 18f))
            .addContent(gap(4f))
            .addContent(text((if (d.budget > 0) "Sisa anggaran " else "Sisa ") + d.month, 12f, TileStyle.MUTED))
            .addContent(text(short(d.left), 26f, if (d.left < 0) TileStyle.BAD else TileStyle.INK, L.FONT_WEIGHT_BOLD))
            .addContent(
                text(
                    if (d.budget > 0) "terpakai " + Math.round(d.frac * 100) + "%" else "keluar " + Fmt.short(d.out, rp = false),
                    12f, tone, L.FONT_WEIGHT_MEDIUM,
                ),
            )
            .addContent(gap(10f))
            .addContent(
                L.Row.Builder().setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
                    .addContent(circleButton("mic", TileStyle.ACCENT, TileStyle.BLACK, launch(pkg, "voice", "voice")))
                    .addContent(gap(1f, 12f))
                    .addContent(circleButton("keypad", TileStyle.SURFACE, TileStyle.INK, launch(pkg, "keypad", "keypad")))
                    .build(),
            )
            .build()
        val items = ring(if (d.budget > 0 || d.inc > 0) d.frac else 0f, tone) + col
        return root(launch(pkg, "open", "summary"), *items.toTypedArray())
    }

    /** One labelled bar: name left, amount right, coloured fill under it. */
    private fun barRow(r: TRow, frac: Float, showBar: Boolean): L.LayoutElement {
        val head = L.Row.Builder().setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
            .addContent(
                L.Box.Builder().setWidth(dp(10f)).setHeight(dp(14f)).setHorizontalAlignment(L.HORIZONTAL_ALIGN_START)
                    .addContent(L.Box.Builder().setWidth(dp(7f)).setHeight(dp(7f))
                        .setModifiers(M.Modifiers.Builder().setBackground(M.Background.Builder().setColor(argb(r.color)).setCorner(M.Corner.Builder().setRadius(dp(3.5f)).build()).build()).build())
                        .build())
                    .build(),
            )
            .addContent(L.Box.Builder().setWidth(dp(W - 10f - 52f)).setHorizontalAlignment(L.HORIZONTAL_ALIGN_START).addContent(text(r.name, 12f, TileStyle.INK, L.FONT_WEIGHT_MEDIUM)).build())
            .addContent(L.Box.Builder().setWidth(dp(52f)).setHorizontalAlignment(L.HORIZONTAL_ALIGN_END).addContent(text(Fmt.short(r.amount, rp = false), 12f, if (r.amount < 0) TileStyle.BAD else TileStyle.INK, L.FONT_WEIGHT_BOLD)).build())
            .build()
        val col = L.Column.Builder().setHorizontalAlignment(L.HORIZONTAL_ALIGN_START).addContent(head)
        if (showBar) {
            val fill = (W * frac.coerceIn(0.03f, 1f))
            col.addContent(gap(3f)).addContent(
                L.Box.Builder().setWidth(dp(W)).setHeight(dp(4f)).setHorizontalAlignment(L.HORIZONTAL_ALIGN_START)
                    .setModifiers(M.Modifiers.Builder().setBackground(M.Background.Builder().setColor(argb(TileStyle.SURFACE)).setCorner(M.Corner.Builder().setRadius(dp(2f)).build()).build()).build())
                    .addContent(L.Box.Builder().setWidth(dp(fill)).setHeight(dp(4f))
                        .setModifiers(M.Modifiers.Builder().setBackground(M.Background.Builder().setColor(argb(r.color)).setCorner(M.Corner.Builder().setRadius(dp(2f)).build()).build()).build())
                        .build())
                    .build(),
            )
        }
        return col.build()
    }

    private fun listTile(pkg: String, go: String, label: String, big: String, rows: List<TRow>, empty: String, bars: Boolean): L.LayoutElement {
        val col = L.Column.Builder().setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(label, 12f, TileStyle.MUTED))
            .addContent(text(big, 22f, TileStyle.INK, L.FONT_WEIGHT_BOLD))
            .addContent(gap(8f))
        if (rows.isEmpty()) col.addContent(text(empty, 12f, TileStyle.MUTED, L.FONT_WEIGHT_NORMAL, 2))
        val max = rows.maxOfOrNull { it.amount }?.coerceAtLeast(1L) ?: 1L
        rows.forEachIndexed { i, r ->
            if (i > 0) col.addContent(gap(if (bars) 7f else 5f))
            col.addContent(barRow(r, r.amount.toFloat() / max, bars))
        }
        return root(launch(pkg, "open-$go", go), col.build())
    }

    /** Tile 2: where this month's money went. */
    fun cats(pkg: String, d: TileData): L.LayoutElement =
        if (!d.paired) notPaired(pkg)
        else listTile(pkg, "summary", "Keluar " + d.month, short(d.out), d.cats, "Belum ada pengeluaran bulan ini", true)

    /** Tile 3: pocket balances. */
    fun pockets(pkg: String, d: TileData): L.LayoutElement =
        if (!d.paired) notPaired(pkg)
        else listTile(pkg, "pockets", "Total kantong", short(d.total), d.pockets, "Belum ada kantong", false)

    fun tile(root: L.LayoutElement): TileBuilders.Tile = TileBuilders.Tile.Builder()
        .setResourcesVersion(RES_VERSION)
        .setFreshnessIntervalMillis(30 * 60_000L)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
        .build()
}

abstract class BaseTile : TileService() {
    abstract fun layout(d: TileData): L.LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        Futures.immediateFuture(TileLayouts.tile(layout(TileData.load(this))))

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(TileLayouts.resources())
}

class KasTileService : BaseTile() { override fun layout(d: TileData) = TileLayouts.budget(packageName, d) }
class KasCatTileService : BaseTile() { override fun layout(d: TileData) = TileLayouts.cats(packageName, d) }
class KasPocketTileService : BaseTile() { override fun layout(d: TileData) = TileLayouts.pockets(packageName, d) }
