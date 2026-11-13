package io.github.fatahilahuda.muarakas.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

enum class VoicePhase { WAITING, LISTENING, THINKING, ERROR }

/** What the voice screen shows; split out so it can be previewed without a microphone. */
data class VoiceUi(val phase: VoicePhase, val heard: String = "", val error: String? = null, val level: Float = 0f)

private fun errorText(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Belum terdengar. Ketuk untuk coba lagi."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Butuh internet untuk mengenali suara."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izinkan mikrofon untuk mencatat dengan suara."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mikrofon sedang dipakai. Coba lagi."
    12, 13 -> "Bahasa Indonesia belum tersedia di jam ini."
    else -> "Suara belum bisa dikenali. Coba lagi."
}

/**
 * Muarakas's own listening screen. Uses the watch's speech service directly (in Indonesian), so
 * no Google dialog appears. If the watch has no speech service, [onFallback] opens the system one.
 */
@Composable
fun VoiceScreen(k: Kas, onDone: (String) -> Unit, onKeyboard: () -> Unit, onFallback: () -> Unit) {
    val ctx = LocalContext.current
    val done by rememberUpdatedState(onDone)
    var ui by remember { mutableStateOf(VoiceUi(VoicePhase.WAITING)) }
    var attempt by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val holder = remember { arrayOfNulls<SpeechRecognizer>(1) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val beep = remember { BeepMute(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) ui = VoiceUi(VoicePhase.ERROR, error = "Izinkan mikrofon untuk mencatat dengan suara.")
    }
    LaunchedEffect(Unit) {
        if (!granted) perm.launch(Manifest.permission.RECORD_AUDIO)
        else if (!SpeechRecognizer.isRecognitionAvailable(ctx)) onFallback()
    }

    DisposableEffect(granted, attempt) {
        var sr: SpeechRecognizer? = null
        if (granted && SpeechRecognizer.isRecognitionAvailable(ctx)) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            holder[0] = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { ui = VoiceUi(VoicePhase.LISTENING); handler.postDelayed({ beep.unmute() }, 450) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { if (ui.phase == VoicePhase.LISTENING) ui = ui.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { ui = ui.copy(phase = VoicePhase.THINKING, level = 0f) }
                override fun onError(error: Int) { ui = VoiceUi(VoicePhase.ERROR, heard = ui.heard, error = errorText(error)) }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!p.isNullOrBlank()) ui = ui.copy(heard = p)
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }.orEmpty()
                    if (list.isEmpty()) { ui = VoiceUi(VoicePhase.ERROR, error = errorText(SpeechRecognizer.ERROR_NO_MATCH)); return }
                    done(list.firstOrNull { Amount.parse(Amount.normalizeSpeech(it)) != null } ?: list[0])
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            ui = VoiceUi(VoicePhase.WAITING)
            // Muarakas chime first, then listen with the system's own "ding" muted for a moment.
            Sfx.play(ctx, "muara")
            val r: SpeechRecognizer = sr!!
            handler.postDelayed({
                beep.mute()
                try { r.startListening(i) } catch (_: Exception) { beep.unmute(); ui = VoiceUi(VoicePhase.ERROR, error = errorText(-1)) }
            }, 380)
        }
        onDispose { handler.removeCallbacksAndMessages(null); beep.unmute(); try { sr?.destroy() } catch (_: Exception) {}; holder[0] = null }
    }

    VoiceView(
        k, ui,
        onMic = {
            when (ui.phase) {
                VoicePhase.LISTENING -> holder[0]?.stopListening()
                VoicePhase.ERROR -> if (!granted) perm.launch(Manifest.permission.RECORD_AUDIO) else attempt++
                else -> {}
            }
        },
        onKeyboard = onKeyboard,
    )
}

/** Mutes the streams the speech service's start "ding" usually plays on, for at most two seconds. */
class BeepMute(ctx: android.content.Context) {
    private val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
    private val muted = mutableListOf<Int>()
    private val h = Handler(Looper.getMainLooper())
    fun mute() {
        if (am == null || muted.isNotEmpty()) return
        for (s in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)) {
            try { if (!am.isStreamMute(s)) { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); muted += s } } catch (_: Exception) {}
        }
        h.postDelayed({ unmute() }, 2000)
    }
    fun unmute() {
        h.removeCallbacksAndMessages(null)
        if (am != null) muted.forEach { s -> try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {} }
        muted.clear()
    }
}

private val GlowColors = listOf(Color(0xFF3BB3AB), Color(0xFF7F96FF), Color(0xFFA496F0), Color(0xFFE0C25A), Color(0xFF4FC1B4), Color(0xFF3BB3AB))

/**
 * Running glow around the round screen's edge, like the phone's. No hard line: it is drawn as
 * thin rings whose opacity falls to zero going inward. A slow multicolour base plus a bright band
 * that runs around the edge; louder voice reaches further in.
 */
@Composable
fun EdgeGlow(active: Boolean, level: Float, saved: Boolean = false) {
    val spin = rememberInfiniteTransition(label = "glow")
    val run by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 1700 else 2800, easing = LinearEasing), RepeatMode.Restart), label = "run")
    val base by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "base")
    val lv by animateFloatAsState(level, tween(140), label = "lv")
    val baseColors = if (saved) listOf(Color(0xFF4CC48E), Color(0xFF3BB3AB), Color(0xFFB6F0D6), Color(0xFF4CC48E)) else GlowColors
    val band = if (saved) listOf(Color(0xFF4CC48E), Color(0xFFB6F0D6), Color(0xFF3BB3AB), Color(0xFFE6FFF3))
        else listOf(Color(0xFF3BB3AB), Color(0xFF7F96FF), Color(0xFFC9B8FF), Color(0xFFFFE8A3))
    Canvas(Modifier.fillMaxSize()) {
        val r0 = size.minDimension / 2
        val depth = (if (active) 30f + lv * 24f else 24f).dp.toPx()
        val steps = 16
        val w = depth / steps
        val baseBrush = Brush.sweepGradient(baseColors, center)
        val runBrush = Brush.sweepGradient(
            0f to Color.Transparent, 0.53f to Color.Transparent, 0.67f to band[0], 0.78f to band[1],
            0.87f to band[2], 0.93f to band[3], 1f to Color.Transparent, center = center,
        )
        for (k in 0 until steps) {
            val f = 1f - k / steps.toFloat()
            val a = f * f
            val r = r0 - w * (k + 0.5f)
            rotate(base) { drawCircle(baseBrush, radius = r, alpha = a * 0.45f, style = Stroke(w + 0.6f)) }
            rotate(run) { drawCircle(runBrush, radius = r, alpha = a * 0.95f, style = Stroke(w + 0.6f)) }
        }
    }
}

/** The listening screen itself: pulsing brand circle, live transcript, live amount + category. */
@Composable
fun VoiceView(k: Kas, ui: VoiceUi, onMic: () -> Unit, onKeyboard: () -> Unit) {
    val level by animateFloatAsState(ui.level, tween(120), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val wave by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val listening = ui.phase == VoicePhase.LISTENING || ui.phase == VoicePhase.WAITING

    val parsed = remember(ui.heard) {
        if (ui.heard.isBlank()) null else {
            val norm = Amount.normalizeSpeech(ui.heard)
            val f = Amount.parse(norm)
            val rest = if (f != null) (norm.substring(0, f.start) + " " + norm.substring(f.start + f.len)).trim() else norm
            val cat = Store.ai()?.rank("out", rest)?.firstOrNull()?.first
            f?.value to cat?.let { k.cat("out", it).n }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (ui.phase != VoicePhase.ERROR) EdgeGlow(active = ui.phase == VoicePhase.LISTENING, level = ui.level)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Text(
                when (ui.phase) {
                    VoicePhase.WAITING -> "Siap mendengarkan…"
                    VoicePhase.LISTENING -> "Mendengarkan…"
                    VoicePhase.THINKING -> "Memproses…"
                    VoicePhase.ERROR -> "Belum berhasil"
                },
                color = if (ui.phase == VoicePhase.ERROR) Warn else Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.size(96.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    if (listening) {
                        drawCircle(Accent.copy(alpha = 0.22f * (1f - wave)), radius = r * (0.62f + 0.38f * wave))
                        drawCircle(Accent.copy(alpha = 0.28f), radius = r * (0.6f + 0.3f * level))
                    }
                }
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(if (ui.phase == VoicePhase.ERROR) Surface2 else Accent).clickable(onClick = onMic),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (ui.phase == VoicePhase.ERROR) Ic.sync else Ic.mic, contentDescription = "Mikrofon",
                        tint = if (ui.phase == VoicePhase.ERROR) Ink else Color.Black, modifier = Modifier.size(26.dp))
                }
            }
            Text(
                ui.error ?: ui.heard.ifBlank { "Sebutkan, misal \"bakso 15 ribu\"" },
                color = if (ui.error != null) Muted else if (ui.heard.isBlank()) Muted else Ink,
                fontSize = if (ui.heard.isBlank() || ui.error != null) 12.sp else 15.sp,
                fontWeight = if (ui.heard.isBlank()) FontWeight.Medium else FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val p = parsed
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                if (p != null && ui.error == null && (p.first != null || p.second != null)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.first != null) Text(Fmt.full(p.first!!), color = Accent, fontSize = 13.sp, fontFamily = Display, fontWeight = FontWeight.Bold)
                        if (p.second != null) Text(p.second!!, color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier.padding(top = 2.dp).size(34.dp).clip(CircleShape).background(Surface1).clickable(onClick = onKeyboard),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.keyboard, contentDescription = "Pakai keyboard", tint = Muted, modifier = Modifier.size(18.dp)) }
        }
    }
}
