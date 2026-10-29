package app.deckline.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.media3.common.C

/**
 * Owns the phone's equalizer and bass boost for our player, and remembers their settings.
 * The saved settings are the source of truth; they are re-applied whenever Android
 * resets the effects (new audio session, new song, or after another app took control).
 */
object AudioFx {
    const val PRESET_CUSTOM = -1
    const val PRESET_FLAT = -2
    private const val PRIORITY = 1000

    var eq: Equalizer? = null
        private set
    private var bass: BassBoost? = null
    private var prefs: SharedPreferences? = null

    /** Called when the effects are re-created or their status changes, so the screen can refresh. */
    var onChanged: (() -> Unit)? = null

    fun attach(context: Context, audioSessionId: Int) {
        release()
        prefs = context.applicationContext.getSharedPreferences("deckline", Context.MODE_PRIVATE)
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) { onChanged?.invoke(); return }
        eq = try { Equalizer(PRIORITY, audioSessionId) } catch (_: Throwable) { null }
        bass = try {
            val b = BassBoost(PRIORITY, audioSessionId)
            if (b.strengthSupported) b else { b.release(); null }
        } catch (_: Throwable) { null }
        eq?.setControlStatusListener { _, granted ->
            if (granted) reapply()
            onChanged?.invoke()
        }
        reapply()
        onChanged?.invoke()
    }

    /** Pushes the saved settings into the effects again. */
    fun reapply() {
        val p = prefs ?: return
        val on = p.getBoolean("eq_on", false)
        eq?.let { e ->
            for (b in 0 until bandCount) {
                val saved = p.getInt("band_$b", Int.MIN_VALUE)
                if (saved != Int.MIN_VALUE) safe { e.setBandLevel(b.toShort(), saved.toShort()) }
            }
            safe { e.setEnabled(on) }
        }
        bass?.let { b ->
            val strength = p.getInt("bass", 0)
            safe { b.setStrength(strength.toShort()) }
            safe { b.setEnabled(on && strength > 0) }
        }
    }

    val available: Boolean get() = eq != null
    val enabled: Boolean get() = prefs?.getBoolean("eq_on", false) == true
    val hasControl: Boolean get() = try { eq?.hasControl() ?: true } catch (_: Throwable) { true }
    val bandCount: Int get() = try { eq?.numberOfBands?.toInt() ?: 0 } catch (_: Throwable) { 0 }
    val bassSupported: Boolean get() = bass != null
    val bassStrength: Int get() = prefs?.getInt("bass", 0) ?: 0

    var preset: Int
        get() = prefs?.getInt("preset", PRESET_FLAT) ?: PRESET_FLAT
        private set(value) { prefs?.edit()?.putInt("preset", value)?.apply() }

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean("eq_on", on)?.apply()
        reapply()
    }

    fun bandLevel(band: Int): Int {
        val saved = prefs?.getInt("band_$band", Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (saved != Int.MIN_VALUE) return saved
        return try { eq?.getBandLevel(band.toShort())?.toInt() ?: 0 } catch (_: Throwable) { 0 }
    }

    fun setBand(band: Int, level: Int) {
        prefs?.edit()?.putInt("band_$band", level)?.apply()
        safe { eq?.setBandLevel(band.toShort(), level.toShort()) }
        preset = PRESET_CUSTOM
    }

    fun usePreset(index: Int) {
        val e = eq ?: return
        if (!safe { e.usePreset(index.toShort()) }) return
        val ed = prefs?.edit() ?: return
        for (b in 0 until bandCount) {
            val lvl = try { e.getBandLevel(b.toShort()).toInt() } catch (_: Throwable) { 0 }
            ed.putInt("band_$b", lvl)
        }
        ed.apply()
        preset = index
    }

    fun flat() {
        val ed = prefs?.edit() ?: return
        for (b in 0 until bandCount) ed.putInt("band_$b", 0)
        ed.apply()
        preset = PRESET_FLAT
        reapply()
    }

    fun setBass(strength: Int) {
        prefs?.edit()?.putInt("bass", strength)?.apply()
        bass?.let { b ->
            safe { b.setStrength(strength.toShort()) }
            safe { b.setEnabled(enabled && strength > 0) }
        }
    }

    fun release() {
        safe { eq?.release() }
        safe { bass?.release() }
        eq = null
        bass = null
    }

    private inline fun safe(block: () -> Unit): Boolean =
        try { block(); true } catch (_: Throwable) { false }
}
