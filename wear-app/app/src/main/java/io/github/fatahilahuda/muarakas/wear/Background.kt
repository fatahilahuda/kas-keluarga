package io.github.fatahilahuda.muarakas.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

private fun openApp(ctx: Context, go: String?): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (go != null) i.putExtra("go", go)
    return PendingIntent.getActivity(ctx, (go ?: "open").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class KasComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val g = TileData.load(this)
        return build(request.complicationType, g, openApp(this, if (g.paired) "summary" else null))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, TileData.sample(), null)

    private fun build(type: ComplicationType, g: TileData, tap: PendingIntent?): ComplicationData? {
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
