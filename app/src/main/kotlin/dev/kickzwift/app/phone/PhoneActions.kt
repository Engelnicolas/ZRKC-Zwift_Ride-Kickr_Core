package dev.kickzwift.app.phone

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dev.kickzwift.protocol.Action

/** Actions sur le téléphone lui-même, déclenchées depuis les manettes (lot 7). */
fun interface PhoneActions {
    /** Rend vrai si [action] était une action téléphone, et l'a exécutée. */
    fun perform(action: Action): Boolean
}

/**
 * Musique par touches média sur le lecteur actif, volume par le flux musique : ni service
 * d'accessibilité ni permission en plus (plan de développement, phase 7).
 */
class AndroidPhoneActions(
    context: Context,
) : PhoneActions {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun perform(action: Action): Boolean {
        when (action) {
            Action.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Action.NextTrack -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            Action.PreviousTrack -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Action.VolumeUp -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            Action.VolumeDown -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            else -> return false
        }
        return true
    }

    private fun mediaKey(code: Int) {
        val at = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, code, 0))
    }
}
