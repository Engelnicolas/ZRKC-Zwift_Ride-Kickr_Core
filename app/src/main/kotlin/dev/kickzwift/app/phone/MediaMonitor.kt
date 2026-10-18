package dev.kickzwift.app.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Ce que montre le lecteur de l'écran Sortie. */
data class MediaState(
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    /** Accès aux notifications accordé : sans lui, ni titre ni état précis du lecteur. */
    val access: Boolean = false,
)

/**
 * Lecture en cours sur le téléphone, relue toutes les [POLL_MS] tant que quelqu'un l'observe :
 * un changement fait aux manettes (lecture, piste, volume) apparaît donc dans le lecteur.
 *
 * Le volume et « de la musique joue » viennent du flux musique, sans permission. Le titre et
 * l'état du lecteur exigent l'accès aux notifications, que la personne accorde dans les
 * réglages d'Android ([accessSettings]) ; l'app ne lit aucune notification.
 */
class MediaMonitor(
    context: Context,
) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaAccessService::class.java)

    val state: Flow<MediaState> =
        flow {
            while (true) {
                emit(read())
                delay(POLL_MS)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Page des réglages d'Android où accorder l'accès aux notifications à l'app. */
    fun accessSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun read(): MediaState {
        val access = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        // Sans accès, getActiveSessions lève SecurityException ; l'accès peut aussi être retiré entre-temps.
        val controller = if (access) runCatching { pick(sessions.getActiveSessions(listener)) }.getOrNull() else null
        val metadata = controller?.metadata
        return MediaState(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            playing = controller?.playbackState?.state?.let { it == PlaybackState.STATE_PLAYING } ?: audio.isMusicActive,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            access = access,
        )
    }

    // Le lecteur qui joue, sinon le plus récent : Android les rend du plus récent au plus ancien.
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()

    private companion object {
        const val POLL_MS = 500L
    }
}

/**
 * Présent pour que l'accès aux notifications puisse être accordé, ce qui ouvre la liste des
 * lecteurs actifs à [MediaMonitor]. Les notifications reçues sont ignorées.
 */
class MediaAccessService : NotificationListenerService()
