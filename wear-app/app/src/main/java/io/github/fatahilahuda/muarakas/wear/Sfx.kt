package io.github.fatahilahuda.muarakas.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * The same sounds as the phone (res/raw, made by tools/gen-sounds.py), chosen on the phone in
 * Pengaturan → Suara and synced through this person's member row. Silent when the watch is on
 * silent or vibrate, or when the choice is "Mati".
 */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private var pending = 0

    private val RES = mapOf(
        "open_muara" to R.raw.open_muara, "open_gelembung" to R.raw.open_gelembung, "open_klik" to R.raw.open_klik,
        "save_koin" to R.raw.save_koin, "save_celengan" to R.raw.save_celengan, "save_kasir" to R.raw.save_kasir,
        "save_lonceng" to R.raw.save_lonceng, "save_klik" to R.raw.save_klik,
    )

    @Synchronized
    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p ->
            p.setOnLoadCompleteListener { sp, id, status ->
                if (status == 0) {
                    loaded += id
                    if (pending == id) { pending = 0; sp.play(id, VOL, VOL, 1, 0, 1f) }
                }
            }
            pool = p
        }

    private const val VOL = 0.75f

    private fun key(kind: String): String? {
        val k = Store.kas.value
        val choice = if (kind == "open") k?.soundOpen ?: "muara" else k?.soundSave ?: "koin"
        if (choice == "off") return null
        return "${kind}_$choice".takeIf { it in RES }
    }

    /** Load the chosen sounds ahead of time so the first one plays without delay. */
    fun preload(ctx: Context) {
        listOfNotNull(key("open"), key("save")).forEach { load(ctx, it) }
    }

    private fun load(ctx: Context, key: String): Int =
        ids.getOrPut(key) { pool().load(ctx.applicationContext, RES.getValue(key), 1) }

    /** kind: "open" (catat cepat opens / mic starts) or "save". */
    fun play(ctx: Context, kind: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val key = key(kind) ?: return
        val id = load(ctx, key)
        if (id in loaded) pool().play(id, VOL, VOL, 1, 0, 1f) else pending = id
    }
}
