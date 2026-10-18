package dev.kickzwift.app.ui.ride

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.phone.MediaState
import dev.kickzwift.app.phone.NotificationMirror
import dev.kickzwift.app.phone.PhoneNotice
import dev.kickzwift.app.route.RouteData
import dev.kickzwift.app.session.ActionNotice
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.session.SessionSnapshot
import dev.kickzwift.protocol.settings.OrientationChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État d'un appareil dans le bandeau. */
data class DeviceStatus(
    val device: KnownDevice,
    val state: LinkState?,
)

data class RideUi(
    val snapshot: SessionSnapshot? = null,
    val route: RouteData? = null,
    val devices: List<DeviceStatus> = emptyList(),
    /** Dernière action venue des manettes, montrée 2 s. */
    val notice: ActionNotice? = null,
    val imperial: Boolean = false,
    val haptics: Boolean = true,
    val orientation: OrientationChoice = OrientationChoice.AUTO,
    /** Capteur cardiaque d'abord, sinon fréquence relayée par le trainer. */
    val heartRate: Int? = null,
    val media: MediaState = MediaState(),
    /** Notifications des autres apps, en haut de l'écran le temps de les lire. */
    val phoneNotices: List<PhoneNotice> = emptyList(),
) {
    val lost: List<DeviceStatus> get() = devices.filter { it.state != LinkState.READY }
}

/** Écran Sortie (lot 9). */
class RideViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val notice = MutableStateFlow<ActionNotice?>(null)
    private val phoneNotices = MutableStateFlow<List<PhoneNotice>>(emptyList())

    init {
        viewModelScope.launch {
            c.session.notices.collectLatest {
                notice.value = it
                delay(NOTICE_MS)
                notice.value = null
            }
        }
        viewModelScope.launch {
            NotificationMirror.posted.collect { n ->
                phoneNotices.update { list -> (list.filterNot { it.key == n.key } + n).takeLast(MAX_PHONE_NOTICES) }
                launch {
                    delay(PHONE_NOTICE_MS)
                    phoneNotices.update { list -> list.filterNot { it === n } }
                }
            }
        }
    }

    val state: StateFlow<RideUi> =
        combine(
            combine(c.session.state, c.trusted, c.links.states, notice, c.settings) { session, trusted, states, n, s ->
                RideUi(
                    snapshot = session.snapshot,
                    route = session.route,
                    devices = trusted.map { DeviceStatus(it, states[it.id]) },
                    notice = n,
                    imperial = s.imperial,
                    haptics = s.haptics,
                    orientation = s.orientation,
                    heartRate = session.heartRate,
                )
            },
            c.media.state,
            phoneNotices,
        ) { ui, media, phone -> ui.copy(media = media, phoneNotices = phone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RideUi())

    /** Réglages d'Android où accorder l'accès aux notifications, pour le titre du lecteur. */
    fun mediaAccess(): Intent = c.media.accessSettings()

    fun togglePause() {
        when (c.session.state.value.snapshot.phase) {
            RidePhase.RIDING -> c.session.pause()
            RidePhase.PAUSED -> c.session.resume()
            else -> Unit
        }
    }

    fun finish() = c.session.finish()

    fun act(action: Action) = c.session.act(action)

    /** Déplace la lecture du morceau en cours, depuis la barre d'avancement du lecteur. */
    fun seek(positionMs: Long) = c.media.seekTo(positionMs)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val NOTICE_MS = 2_000L
        private const val PHONE_NOTICE_MS = 8_000L
        private const val MAX_PHONE_NOTICES = 3

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { RideViewModel(c) } }
    }
}
