package io.github.fatahilahuda.muarakas.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Keeps the tile and complication fresh even when the app isn't opened. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (!Store.paired) return Result.success()
        val r = Store.syncNow()
        return if (r == SyncState.OFFLINE) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("muarakas-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Month numbers shown on the tile and complication. */
private data class Glance(val paired: Boolean, val out: Long, val inc: Long, val budget: Long) {
    val left get() = if (budget > 0) budget - out else inc - out
}

private fun glance(ctx: Context): Glance {
    Store.init(ctx)
    val k = Store.kas.value ?: return Glance(false, 0, 0, 0)
    val t = k.totals(Kas.monthKey())
    return Glance(true, t.hout, t.hin, k.budget)
}

private fun openApp(ctx: Context, go: String?): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (go != null) i.putExtra("go", go)
    return PendingIntent.getActivity(ctx, (go ?: "open").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

private val ACCENT = 0xFF3BB3AB.toInt()
private val INK = 0xFFE5EDEA.toInt()
private val MUTED = 0xFF8FA39D.toInt()
private val SURFACE = 0xFF22302B.toInt()
private val BAD = 0xFFE57B73.toInt()
private val WARN = 0xFFE3AE4D.toInt()

class KasTileService : TileService() {
    private val res = "1"

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val g = glance(this)
        val root = if (g.paired) layout(g) else text("Buka Muarakas di jam untuk menyambungkan", 13f, INK, false)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(res)
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(res).build())

    private fun launch(id: String, go: String): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping("go", ActionBuilders.AndroidStringExtra.Builder().setValue(go).build())
                            .build(),
                    ).build(),
            ).build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            ).build()

    private fun pill(label: String, bg: Int, fg: Int, click: ModifiersBuilders.Clickable): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(66f)).setHeight(dp(40f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(click)
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(bg))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build()).build(),
                    ).build(),
            )
            .addContent(text(label, 13f, fg, true))
            .build()

    private fun gap(h: Float, w: Float = 1f) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).setWidth(dp(w)).build()

    private fun layout(g: Glance): LayoutElementBuilders.LayoutElement {
        val now = LocalDate.now()
        val bulan = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")[now.monthValue - 1]
        val frac = if (g.budget > 0) (g.out.toFloat() / g.budget).coerceIn(0f, 1f) else 0f
        val arcColor = if (g.budget > 0 && g.out > g.budget) BAD else if (g.budget > 0 && g.out >= g.budget * 0.8) WARN else ACCENT

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(if (g.budget > 0) "Sisa anggaran $bulan" else "Sisa $bulan", 12f, MUTED, false))
            .addContent(text(Fmt.short(g.left), 24f, if (g.left < 0) BAD else INK, true))
            .addContent(text("keluar " + Fmt.short(g.out), 12f, MUTED, false))
            .addContent(gap(10f))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(pill("Suara", ACCENT, 0xFF000000.toInt(), launch("voice", "voice")))
                    .addContent(gap(1f, 8f))
                    .addContent(pill("Nominal", SURFACE, INK, launch("keypad", "keypad")))
                    .build(),
            )
            .build()

        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch("open", "summary")).build())
        if (g.budget > 0) {
            box.addContent(
                LayoutElementBuilders.Arc.Builder()
                    .setAnchorAngle(degrees(0f))
                    .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                    .addContent(LayoutElementBuilders.ArcLine.Builder().setLength(degrees(360f)).setThickness(dp(5f)).setColor(argb(SURFACE)).build())
                    .build(),
            )
            if (frac > 0f) box.addContent(
                LayoutElementBuilders.Arc.Builder()
                    .setAnchorAngle(degrees(0f))
                    .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                    .addContent(LayoutElementBuilders.ArcLine.Builder().setLength(degrees(360f * frac)).setThickness(dp(5f)).setColor(argb(arcColor)).build())
                    .build(),
            )
        }
        box.addContent(column)
        return box.build()
    }
}

class KasComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val g = glance(this)
        return build(request.complicationType, g, openApp(this, if (g.paired) "summary" else null))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, Glance(true, 1_250_000, 6_000_000, 4_000_000), null)

    private fun build(type: ComplicationType, g: Glance, tap: PendingIntent?): ComplicationData? {
        val shortOut = if (g.paired) Fmt.short(g.out, rp = false).replace(" ", "") else "—"
        val desc = PlainComplicationText.Builder("Pengeluaran bulan ini " + Fmt.short(g.out)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(shortOut).build(), desc)
                .setTitle(PlainComplicationText.Builder("Keluar").build())
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> {
                val max = if (g.budget > 0) g.budget.toFloat() else maxOf(g.inc, g.out, 1L).toFloat()
                RangedValueComplicationData.Builder(g.out.toFloat().coerceAtMost(max), 0f, max, desc)
                    .setText(PlainComplicationText.Builder(shortOut).build())
                    .setTitle(PlainComplicationText.Builder("Keluar").build())
                    .setTapAction(tap)
                    .build()
            }
            else -> null
        }
    }
}
