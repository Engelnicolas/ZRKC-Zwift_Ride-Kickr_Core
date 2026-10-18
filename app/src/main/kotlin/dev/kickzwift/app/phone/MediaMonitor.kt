package dev.kickzwift.app.phone

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
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
    /** Durée du morceau ; nulle si le lecteur ne la donne pas. */
    val durationMs: Long? = null,
    /** Position dans le morceau, estimée à la lecture de l'état. */
    val positionMs: Long = 0,
    /** Le lecteur accepte qu'on déplace la lecture. */
    val canSeek: Boolean = false,
)

/**
 * Lecture en cours sur le téléphone, relue toutes les [POLL_MS] tant que quelqu'un l'observe :
 * un changement fait aux manettes (lecture, piste, volume) apparaît donc dans le lecteur, et
 * l'avancement du morceau suit la lecture.
 *
 * Le volume et « de la musique joue » viennent du flux musique, sans permission. Le titre et
 * l'état du lecteur exigent l'accès aux notifications, que la personne accorde dans les
 * réglages d'Android ([accessSettings]) ; l'app ne lit aucune notification. Spotify passe
 * avant les autres lecteurs.
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

    /**
     * Reprend la lecture de Spotify en arrière-plan : par sa session s'il en a une, sinon par une
     * touche « lecture » adressée à Spotify seul, qui le démarre s'il est fermé. Sans effet si
     * Spotify n'est pas installé ; la lecture déjà en cours continue.
     */
    fun playSpotify() {
        if (context.packageManager.getLaunchIntentForPackage(AndroidPhoneActions.SPOTIFY) == null) return
        val session = runCatching { sessions(access()).firstOrNull { it.packageName == AndroidPhoneActions.SPOTIFY } }.getOrNull()
        if (session != null) {
            if (session.playbackState?.state != PlaybackState.STATE_PLAYING) session.transportControls.play()
            return
        }
        val at = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(AndroidPhoneActions.SPOTIFY)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(at, at, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)),
            )
        }
    }

    /** Déplace la lecture du lecteur affiché ; sans effet sans accès aux notifications. */
    fun seekTo(positionMs: Long) {
        pick(sessions(access()))?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Page des réglages d'Android où accorder l'accès aux notifications à l'app. */
    fun accessSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun access(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    // Sans accès, getActiveSessions lève SecurityException ; l'accès peut aussi être retiré entre-temps.
    private fun sessions(access: Boolean): List<MediaController> =
        if (access) runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()) else emptyList()

    private fun read(): MediaState {
        val access = access()
        val controller = pick(sessions(access))
        val metadata = controller?.metadata
        val playback = controller?.playbackState
        return MediaState(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            playing = playback?.state?.let { it == PlaybackState.STATE_PLAYING } ?: audio.isMusicActive,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            access = access,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = playback?.let(::position) ?: 0,
            canSeek = playback != null && playback.actions and PlaybackState.ACTION_SEEK_TO != 0L,
        )
    }

    // Le lecteur publie sa position à l'instant de sa dernière mise à jour ; pendant la lecture,
    // elle avance depuis au rythme de la vitesse de lecture.
    private fun position(p: PlaybackState): Long =
        when {
            p.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN -> 0
            p.state != PlaybackState.STATE_PLAYING -> p.position
            else -> p.position + ((SystemClock.elapsedRealtime() - p.lastPositionUpdateTime) * p.playbackSpeed).toLong()
        }

    // Spotify d'abord, puis le lecteur qui joue, sinon le plus récent : Android les rend du plus
    // récent au plus ancien.
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.packageName == AndroidPhoneActions.SPOTIFY }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()

    private companion object {
        const val POLL_MS = 500L
    }
}

/**
 * Présent pour que l'accès aux notifications puisse être accordé, ce qui ouvre la liste des
 * lecteurs actifs à [MediaMonitor] et le mode « ZRKC ride » à [RideQuiet]. Hors sortie, les
 * notifications reçues sont ignorées ; pendant la sortie, celles qui auraient surgi en bandeau
 * (importance haute, hors appels et notifications permanentes) passent à [NotificationMirror].
 */
class MediaAccessService : NotificationListenerService() {
    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val readable = title != null || text != null
        if (readable && NotificationMirror.active && wouldPopUp(sbn, rankingMap)) {
            NotificationMirror.post(PhoneNotice(sbn.key, appLabel(sbn.packageName), title, text))
        }
    }

    /** Ce qu'Android aurait montré en bandeau : importance haute, hors appels, résumés de groupe et permanentes. */
    private fun wouldPopUp(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ): Boolean {
        val n = sbn.notification
        val ranking = Ranking()
        return sbn.packageName != packageName &&
            !sbn.isOngoing &&
            n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            n.category != Notification.CATEGORY_CALL &&
            rankingMap.getRanking(sbn.key, ranking) &&
            ranking.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    // Le nom d'une app n'est lisible que si Android la rend visible à la nôtre ; sinon, rien.
    private fun appLabel(pkg: String): String? =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
