package io.github.fatahilahuda.muarakas.wear

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.tiles.renderer.TileRenderer
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executor

/** Renders the main screens and tiles to PNGs so the design can be reviewed without a watch. */
class ScreenshotTest {
    private val large = DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 450, screenHeight = 450)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = large, theme = "android:Theme.DeviceDefault.NoActionBar")

    private val kas: Kas by lazy {
        val seed = Seed(JSONObject(File("src/main/assets/seed.json").readText()))
        val d = LocalDate.now()
        fun day(n: Int) = d.minusDays(minOf(n.toLong(), d.dayOfMonth - 1L)).toString()
        var i = 0
        fun tx(type: String, amt: Long, cat: String, pocket: String, desc: String, dayAgo: Int, by: String = "m1") =
            Item("tx", "t${++i}", 1, JSONObject("""{"id":"t$i","type":"$type","amount":$amt,"cat":"$cat","pocket":"$pocket","desc":"$desc","date":"${day(dayAgo)}","ts":${1000 - i},"by":"$by"}"""))
        Kas(
            seed,
            listOf(
                Item("settings", "main", 1, JSONObject("""{"budget":4000000,"cbud":{"jajan":300000}}""")),
                Item("member", "m1", 1, JSONObject("""{"id":"m1","n":"Huda"}""")),
                Item("member", "m2", 1, JSONObject("""{"id":"m2","n":"Istri"}""")),
                Item("pocket", "bank", 1, JSONObject("""{"id":"bank","n":"Rekening","k":1,"start":3000000}""")),
                Item("pocket", "dompet", 1, JSONObject("""{"id":"dompet","n":"Dompet","k":0,"start":500000}""")),
                Item("pocket", "haji", 1, JSONObject("""{"id":"haji","n":"Tabungan Haji","k":4,"start":2500000,"save":true}""")),
                Item("biz", "tHaji", 1, JSONObject("""{"id":"tHaji","n":"Haji 2030","kind":"tabungan","k":4,"pocket":"haji","target":10000000}""")),
                tx("in", 6_000_000, "gaji", "bank", "Gaji November", 30),
                tx("out", 45_000, "makan", "dompet", "Bakso", 0),
                tx("out", 18_000, "jajan", "dompet", "Es kopi susu", 0, "m2"),
                tx("out", 150_000, "transport", "bank", "Bensin", 1),
                tx("out", 612_000, "harian", "bank", "Belanja bulanan", 3, "m2"),
                tx("out", 320_000, "tagihan", "bank", "Token listrik", 5),
                tx("out", 140_000, "makan", "dompet", "Makan keluarga", 6),
                tx("tf", 500_000, "tf", "bank", "Setor haji", 8).let { Item(it.kind, it.id, 1, it.data.put("to", "haji")) },
            ),
            "m1", 0,
        )
    }

    private fun shot(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) { MuaraTheme(content) }

    @Test fun home() = shot("home") { HomeScreen(kas, {}, {}, {}, {}, {}) }

    @Test fun homeSmall() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.WEAR_OS_SMALL_ROUND)
        shot("home_small") { HomeScreen(kas, {}, {}, {}, {}, {}) }
    }

    @Test fun voiceListening() = shot("voice_listening") {
        VoiceView(kas, VoiceUi(VoicePhase.LISTENING, heard = "bakso lima belas ribu", level = 0.6f), {}, {})
    }

    @Test fun voiceError() = shot("voice_error") {
        VoiceView(kas, VoiceUi(VoicePhase.ERROR, error = "Belum terdengar. Ketuk untuk coba lagi."), {}, {})
    }

    @Test fun keypad() = shot("keypad") { KeypadScreen(Store.Draft(amount = 45000), {}, {}) }

    @Test fun confirm() = shot("confirm") {
        ConfirmScreen(kas, Store.Draft(amount = 45000, desc = "bakso", cat = "makan"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun summary() = shot("summary") { SummaryScreen(kas) }
    @Test fun history() = shot("history") { HistoryScreen(kas) {} }
    @Test fun pockets() = shot("pockets") { PocketsScreen(kas) }

    private fun tile(name: String, root: LayoutElementBuilders.LayoutElement) = paparazzi.snapshot(name) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            factory = { ctx ->
                FrameLayout(ctx).also { parent ->
                    TileRenderer(ctx, Executor { it.run() }) { }
                        .inflateAsync(LayoutElementBuilders.Layout.Builder().setRoot(root).build(), TileLayouts.resources(), parent)
                }
            },
        )
    }

    @Test fun tileBudget() = tile("tile_budget", TileLayouts.budget("pkg", TileData.from(kas)))
    @Test fun tileCats() = tile("tile_cats", TileLayouts.cats("pkg", TileData.from(kas)))
    @Test fun tilePockets() = tile("tile_pockets", TileLayouts.pockets("pkg", TileData.from(kas)))
}
