package io.github.fatahilahuda.muarakas.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val go = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        SyncWorker.schedule(this)
        go.value = intent?.getStringExtra("go")
        setContent { App(go) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        go.value = intent.getStringExtra("go")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Store.syncNow() }
    }
}

@Composable
fun App(go: MutableState<String?>) {
    val kas by Store.kas.collectAsState()
    MuaraTheme {
        val k = kas
        if (k == null) PairScreen() else MainNav(k, go)
    }
}

private val IN_WORDS = setOf(
    "gaji", "gajian", "bonus", "thr", "terima", "diterima", "dapat", "dapet", "masuk", "pemasukan", "hadiah",
    "kiriman", "dikirim", "jual", "penjualan", "laku", "cashback", "refund", "dividen", "komisi", "honor", "fee",
)

/** "bakso 15rb" → amount 15000, category Makan & Minum. */
fun draftFromText(k: Kas, text: String, base: Store.Draft): Store.Draft {
    val norm = Amount.normalizeSpeech(text)
    val f = Amount.parse(norm)
    val desc = (if (f != null) norm.substring(0, f.start) + " " + norm.substring(f.start + f.len) else norm)
        .replace(Regex("\\s+"), " ").trim()
    val words = desc.lowercase().split(" ").toSet()
    val type = if (base.type == "tf") "tf" else if (words.any { it in IN_WORDS }) "in" else base.type
    val cat = if (type == "tf") null else Store.ai()?.rank(type, desc)?.firstOrNull()?.first
    return base.copy(type = type, amount = f?.value ?: base.amount, desc = desc, cat = cat ?: base.cat,
        pocket = if (type == "tf") base.pocket else null)
}

@Composable
fun MainNav(k: Kas, go: MutableState<String?>) {
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(Store.Draft()) }
    var lastSaved by remember { mutableStateOf<Store.Draft?>(null) }

    fun next() {
        val d = draft
        when {
            d.amount <= 0 -> nav.navigate("keypad")
            d.type == "tf" && d.pocket == null -> nav.navigate("pick/from")
            d.type == "tf" && d.to == null -> nav.navigate("pick/to")
            d.type != "tf" && d.cat == null -> nav.navigate("cats")
            else -> nav.navigate("confirm")
        }
    }

    val voice = rememberTextInput("Ucapkan, misal: bakso 15 ribu") { t ->
        draft = draftFromText(k, t, Store.Draft(type = if (draft.type == "in") "in" else "out"))
        next()
    }
    val noteInput = rememberTextInput("Catatan") { t ->
        draft = draft.copy(desc = t.trim())
        if (draft.type != "tf" && draft.cat == null) Store.ai()?.rank(draft.type, t)?.firstOrNull()?.let { draft = draft.copy(cat = it.first) }
    }

    LaunchedEffect(go.value) {
        when (go.value) {
            "voice" -> { draft = Store.Draft(); voice() }
            "keypad" -> { draft = Store.Draft(); nav.navigate("keypad") }
            "summary" -> nav.navigate("summary")
        }
        go.value = null
    }

    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                k,
                onVoice = { draft = Store.Draft(); voice() },
                onKeypad = { draft = Store.Draft(); nav.navigate("keypad") },
                onOpen = { nav.navigate(it) },
                onSync = { scope.launch { Store.syncNow() } },
            )
        }
        composable("keypad") {
            KeypadScreen(draft, onType = { draft = draft.copy(type = it, cat = null, pocket = null, to = null) }, onDone = { amt ->
                draft = draft.copy(amount = amt)
                next()
            })
        }
        composable("cats") {
            CatScreen(k, draft) { c ->
                draft = draft.copy(cat = c)
                nav.popBackStack()
                if (nav.currentDestination?.route != "confirm") next()
            }
        }
        composable("pick/from") {
            PocketPickScreen(k, if (draft.type == "tf") "Pindah dari" else "Kantong", draft.pocket, exclude = null) { p ->
                draft = draft.copy(pocket = p)
                nav.popBackStack()
                if (nav.currentDestination?.route != "confirm") next()
            }
        }
        composable("pick/to") {
            PocketPickScreen(k, "Pindah ke", draft.to, exclude = draft.pocket) { p ->
                draft = draft.copy(to = p)
                nav.popBackStack()
                if (nav.currentDestination?.route != "confirm") next()
            }
        }
        composable("confirm") {
            ConfirmScreen(
                k, draft,
                onCat = { nav.navigate("cats") },
                onPocket = { nav.navigate("pick/from") },
                onTo = { nav.navigate("pick/to") },
                onAmount = { nav.navigate("keypad") },
                onDate = { draft = draft.copy(daysAgo = (draft.daysAgo + 1) % 3) },
                onNote = noteInput,
                onSave = {
                    val d = draft
                    scope.launch {
                        Store.add(d)
                        lastSaved = d
                        nav.navigate("done") { popUpTo("home") }
                        Store.syncNow()
                    }
                },
            )
        }
        composable("done") {
            DoneScreen(k, lastSaved) { nav.popBackStack("home", inclusive = false) }
        }
        composable("summary") { SummaryScreen(k) }
        composable("history") { HistoryScreen(k) { nav.navigate("tx/$it") } }
        composable("tx/{id}") { e ->
            val id = e.arguments?.getString("id") ?: ""
            TxScreen(k, id) {
                scope.launch { Store.delete(id); nav.popBackStack(); Store.syncNow() }
            }
        }
        composable("pockets") { PocketsScreen(k) }
        composable("modules") { ModulesScreen(k) }
        composable("about") {
            AboutScreen(k, onMe = { id -> scope.launch { Store.setMe(id) } }, onUnpair = { scope.launch { Store.unpair() } })
        }
    }
}
