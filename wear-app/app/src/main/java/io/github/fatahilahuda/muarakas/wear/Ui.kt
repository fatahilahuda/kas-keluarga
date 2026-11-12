package io.github.fatahilahuda.muarakas.wear

import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Typography
import androidx.wear.input.RemoteInputIntentHelper

val Accent = Color(0xFF3BB3AB)
val Ink = Color(0xFFE5EDEA)
val Muted = Color(0xFF8FA39D)
val Surface1 = Color(0xFF17211E)
val Surface2 = Color(0xFF22302B)
val Bad = Color(0xFFE57B73)
val Warn = Color(0xFFE3AE4D)
private val K = listOf(0xFF7F96FF, 0xFFE9A866, 0xFF4FC1B4, 0xFFD97AB3, 0xFFA496F0, 0xFFE0C25A, 0xFF8DBE66, 0xFF6F7F7A)
fun kColor(i: Int): Color = Color(K[((i % 8) + 8) % 8])

/** Plus Jakarta Sans for text, Bricolage Grotesque for big numbers: the phone app's pair. */
val Jakarta = FontFamily(
    Font(R.font.jakarta_medium, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
)
val Display = FontFamily(Font(R.font.bricolage_bold, FontWeight.Bold))

@Composable
fun MuaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = Typography(defaultFontFamily = Jakarta),
        colors = Colors(
            primary = Accent, primaryVariant = Accent, secondary = Accent, secondaryVariant = Accent,
            background = Color.Black, surface = Surface1, error = Bad,
            onPrimary = Color.Black, onSecondary = Color.Black, onBackground = Ink, onSurface = Ink,
            onSurfaceVariant = Muted, onError = Color.Black,
        ),
        content = content,
    )
}

/** A scrolling round-screen page with the clock on top and the scroll indicator on the side. */
@Composable
fun Page(content: ScalingLazyListScope.() -> Unit) {
    val st = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = st) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = st,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun Heading(text: String, sub: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(text, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Big(text: String, color: Color = Ink, size: Int = 26) {
    Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.Bold, fontFamily = Display, textAlign = TextAlign.Center,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}

@Composable
fun Small(text: String, color: Color = Muted, center: Boolean = true) {
    Text(text, color = color, fontSize = 12.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Thin progress bar: phone-style meter. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** A row chip: label on the left, optional value on the right. */
@Composable
fun RowChip(
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    value: String? = null,
    valueColor: Color = Ink,
    dot: Color? = null,
    icon: ImageVector? = null,
    bg: Color = Surface1,
    enabled: Boolean = true,
    iconColor: Color = Accent,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = Ink, secondaryContentColor = Muted, iconColor = Accent),
        icon = when {
            icon != null -> ({ Mono(icon, iconColor) })
            dot != null -> ({ Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Dot(dot) } })
            else -> null
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (value != null) Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        },
        secondaryLabel = if (secondary != null) ({ Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }) else null,
    )
}

/** Icon in a softly tinted circle, like the phone's settings rows. */
@Composable
fun Mono(icon: ImageVector, color: Color, size: Int = 28) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((size * 0.58f).dp))
    }
}

/** Big round action with a caption under it (Suara, Nominal, Ketik). */
@Composable
fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, filled: Boolean, size: Int = 48) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(if (filled) Accent else Surface2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = if (filled) Color.Black else Ink, modifier = Modifier.size((size * 0.46f).dp)) }
        Text(label, color = if (filled) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Primary round-ish action used for Simpan, Sambungkan, etc. */
@Composable
fun ActionChip(label: String, onClick: () -> Unit, icon: ImageVector? = null, color: Color = Accent, enabled: Boolean = true) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(backgroundColor = color, contentColor = Color.Black, iconColor = Color.Black),
        icon = if (icon != null) ({ Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }) else null,
        label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
    )
}

/** Two big side-by-side buttons. */
@Composable
fun TwoButtons(a: String, aIcon: ImageVector, onA: () -> Unit, b: String, bIcon: ImageVector, onB: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        BigButton(a, aIcon, onA, Accent, Color.Black, Modifier.weight(1f))
        BigButton(b, bIcon, onB, Surface2, Ink, Modifier.weight(1f))
    }
}

@Composable
fun BigButton(label: String, icon: ImageVector, onClick: () -> Unit, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Chip(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = fg, iconColor = fg),
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        label = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
    )
}

/** Opens the system voice / keyboard input and hands back the text. */
@Composable
fun rememberTextInput(label: String, onText: (String) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@rememberLauncherForActivityResult
        val t = RemoteInput.getResultsFromIntent(data)?.getCharSequence("t")?.toString()
        if (!t.isNullOrBlank()) cb(t)
    }
    return remember(label) {
        {
            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("t").setLabel(label).build()))
            launcher.launch(intent)
        }
    }
}

/**
 * Speech recognition forced to Indonesian. The system input (RemoteInput) follows the watch's
 * own voice language, which is often English; this asks the recognizer for id-ID directly and
 * returns every alternative so the caller can pick the one with an amount in it.
 */
@Composable
fun rememberVoiceInput(prompt: String, fallback: () -> Unit, onResults: (List<String>) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResults)
    val fb by rememberUpdatedState(fallback)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val list = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.filter { it.isNotBlank() }
        if (!list.isNullOrEmpty()) cb(list)
    }
    return remember(prompt) {
        {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            try { launcher.launch(i) } catch (_: ActivityNotFoundException) { fb() }
        }
    }
}

/** Line icons, drawn from the same 24px stroke style as the phone app. */
object Ic {
    private fun make(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    val mic = make("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
    val keypad = make("M4 4h4v4H4zM10 4h4v4h-4zM16 4h4v4h-4zM4 10h4v4H4zM10 10h4v4h-4zM16 10h4v4h-4zM10 16h4v4h-4z")
    val keyboard = make("M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10")
    val close = make("M6 6l12 12M18 6 6 18")
    val chart = make("M3 20h18M4 16l5-6 4 3 7-8")
    val list = make("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    val wallet = make("M4 7h15a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a2 2 0 0 1 2-2h11M16 13.5h.01")
    val box = make("M21 8 12 3 3 8v8l9 5 9-5zM3 8l9 5 9-5M12 13v8")
    val swap = make("M7 4 3 8l4 4M3 8h14M17 20l4-4-4-4M21 16H7")
    val check = make("M5 12.5l4.5 4.5L19 7")
    val sync = make("M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4")
    val trash = make("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val tag = make("M3 12V4h8l10 10-8 8zM7.5 7.5h.01")
    val cal = make("M4 6h16v14H4zM4 10h16M8 3v4M16 3v4")
    val note = make("M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4")
    val link = make("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val user = make("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0")
    val back = make("M15 5l-7 7 7 7")
    val del = make("M20 7H9l-5 5 5 5h11zM12 10l4 4M16 10l-4 4")
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(18.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun Gap(h: Int) { Box(Modifier.height(h.dp).width(1.dp)) }
