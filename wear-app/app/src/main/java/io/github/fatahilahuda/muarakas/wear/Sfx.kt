package io.github.fatahilahuda.muarakas.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The phone app's two everyday sounds, synthesised the same way (sine tones with exponential
 * decay, a little filtered noise), so watch and phone sound alike without audio files.
 * Quiet on purpose, and silent when the watch is on silent or vibrate.
 */
object Sfx {
    private const val SR = 22050
    private val cache = HashMap<String, ShortArray>()

    private fun tone(b: FloatArray, f: Double, t0: Double, d: Double, vol: Double, f2: Double? = null) {
        val s0 = (t0 * SR).toInt()
        val n = (d * SR).toInt()
        val att = 0.012
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / SR
            val fr = if (f2 != null) f * (f2 / f).pow(t / d) else f
            ph += 2 * PI * fr / SR
            val env = if (t < att) 0.0001 * (vol / 0.0001).pow(t / att) else vol * (0.0001 / vol).pow((t - att) / max(d - att, 0.001))
            b[idx] += (sin(ph) * env).toFloat()
        }
    }

    /** Short burst of high-passed noise: the "tsk" of metal touching. */
    private fun hiss(b: FloatArray, t0: Double, d: Double, vol: Double, rnd: Random) {
        val s0 = (t0 * SR).toInt()
        val n = (d * SR).toInt()
        var prevX = 0.0
        var y = 0.0
        val a = 0.55 // first-order high-pass, cut around 3-4 kHz at this rate
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val x = rnd.nextDouble() * 2 - 1
            y = a * (y + x - prevX)
            prevX = x
            val env = vol * (0.0001 / vol).pow(i.toDouble() / n)
            b[idx] += (y * env).toFloat()
        }
    }

    private fun build(kind: String): ShortArray {
        val b: FloatArray
        when (kind) {
            "muara" -> {
                b = FloatArray((SR * 0.75).toInt())
                tone(b, 900.0, 0.0, 0.09, 0.05, 1500.0)
                tone(b, 1318.5, 0.07, 0.38, 0.034)
                tone(b, 2637.0, 0.07, 0.2, 0.009)
                tone(b, 1975.5, 0.16, 0.5, 0.03)
                tone(b, 3951.0, 0.16, 0.25, 0.007)
            }
            else -> { // coins
                b = FloatArray((SR * 0.7).toInt())
                val rnd = Random(System.nanoTime())
                listOf(0.0, 0.045, 0.085, 0.13, 0.19, 0.255, 0.33, 0.42).forEachIndexed { i, t ->
                    // 22 kHz sampling: keep partials under Nyquist
                    val f = 2300 + rnd.nextDouble() * 1900
                    val v = 0.032 * (1 - i * 0.07)
                    tone(b, f, t, 0.09 + rnd.nextDouble() * 0.06, v)
                    tone(b, min(f * 1.52, 10000.0), t, 0.06, v * 0.45)
                    hiss(b, t, 0.02, 0.03, rnd)
                }
            }
        }
        // Watch speakers are small: a little more level than the phone, still well under full scale.
        return ShortArray(b.size) { i -> (b[i] * 1.6f).coerceIn(-1f, 1f).times(32767).toInt().toShort() }
    }

    fun play(ctx: Context, kind: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            try {
                val pcm = synchronized(cache) { cache.getOrPut(kind) { build(kind) } }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / SR + 80)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }
}
