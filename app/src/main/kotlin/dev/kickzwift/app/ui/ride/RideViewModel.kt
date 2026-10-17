package dev.kickzwift.app.ui.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
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
) {
    val lost: List<DeviceStatus> get() = devices.filter { it.state != LinkState.READY }
}

/** Écran Sortie (lot 9). */
class RideViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val notice = MutableStateFlow<ActionNotice?>(null)

    init {
        viewModelScope.launch {
            c.session.notices.collectLatest {
                notice.value = it
                delay(NOTICE_MS)
                notice.value = null
            }
        }
    }

    val state: StateFlow<RideUi> =
        combine(c.session.state, c.trusted, c.links.states, notice, c.settings) { session, trusted, states, n, s ->
            RideUi(
                snapshot = session.snapshot,
                route = session.route,
                devices = trusted.map { DeviceStatus(it, states[it.id]) },
                notice = n,
                imperial = s.imperial,
                haptics = s.haptics,
                orientation = s.orientation,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RideUi())

    fun togglePause() {
        when (c.session.state.value.snapshot.phase) {
            RidePhase.RIDING -> c.session.pause()
            RidePhase.PAUSED -> c.session.resume()
            else -> Unit
        }
    }

    fun finish() = c.session.finish()

    fun act(action: Action) = c.session.act(action)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val NOTICE_MS = 2_000L

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { RideViewModel(c) } }
    }
}
