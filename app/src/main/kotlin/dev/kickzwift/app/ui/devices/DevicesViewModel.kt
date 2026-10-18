package dev.kickzwift.app.ui.devices

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.ble.scan.ScanFailedException
import dev.kickzwift.ble.scan.ScannedDevice
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.device.RideFirmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

sealed interface ScanStatus {
    data object Idle : ScanStatus

    data object Scanning : ScanStatus

    /** Code d'erreur d'Android, ou null si la permission a été retirée. */
    data class Failed(
        val code: Int?,
    ) : ScanStatus
}

/** Une carte d'appareil de l'écran Connexions. */
data class DeviceCard(
    val device: KnownDevice,
    /** null quand le service est arrêté. */
    val state: LinkState?,
    val battery: Int?,
    val firmware: String?,
) {
    /** Firmware du Ride en 1.3 ou plus : verrou Zwift. */
    val locked: Boolean get() = device.kind == DeviceKind.RIDE && RideFirmware.isLocked(firmware)
}

data class DevicesUi(
    val serviceRunning: Boolean = false,
    val scan: ScanStatus = ScanStatus.Idle,
    val cards: List<DeviceCard> = emptyList(),
    val found: List<ScannedDevice> = emptyList(),
) {
    val canPrepare: Boolean get() = readyToPrepare(cards.map { it.device.kind to it.state })
}

/** « Préparer la sortie » : le KICKR et au moins une manette prêts. */
fun readyToPrepare(devices: List<Pair<DeviceKind, LinkState?>>): Boolean =
    devices.any { (kind, state) -> kind == DeviceKind.KICKR && state == LinkState.READY } &&
        devices.any { (kind, state) -> kind == DeviceKind.RIDE && state == LinkState.READY }

/** Écran Connexions (lot 8). */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val found = MutableStateFlow<Map<DeviceId, ScannedDevice>>(emptyMap())
    private val scan = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    private var scanJob: Job? = null

    private val cards: Flow<List<DeviceCard>> =
        c.trusted.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { card(it) }) { it.toList() }
            }
        }

    val state: StateFlow<DevicesUi> =
        combine(cards, c.links.running, found, scan) { cards, running, seen, scanStatus ->
            val trustedIds = cards.map { it.device.id }.toSet()
            DevicesUi(
                serviceRunning = running,
                scan = scanStatus,
                cards = cards.map { if (running) it else it.copy(state = null) },
                found =
                    seen.values
                        // Les appareils anonymes noieraient la liste.
                        .filter { it.id !in trustedIds && (it.name != null || it.kind != DeviceKind.OTHER) }
                        .sortedWith(compareBy<ScannedDevice> { it.kind == DeviceKind.OTHER }.thenByDescending { it.rssi }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DevicesUi())

    private fun card(d: KnownDevice): Flow<DeviceCard> {
        val link = c.links.stateOf(d.id)
        return if (d.kind == DeviceKind.RIDE) {
            val e = c.hub.rideEndpoint(d.id)
            combine(link, e.battery, e.firmware) { s, b, f -> DeviceCard(d, s, b, f) }
        } else {
            combine(link, flowOf(Unit)) { s, _ -> DeviceCard(d, s, null, null) }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (scanJob?.isActive == true) return
        found.value = emptyMap()
        scanJob =
            viewModelScope.launch {
                scan.value = ScanStatus.Scanning
                scan.value =
                    try {
                        withTimeoutOrNull(SCAN_DURATION) {
                            c.scanner.scan().collect { d -> found.update { it + (d.id to d) } }
                        }
                        ScanStatus.Idle
                    } catch (e: ScanFailedException) {
                        ScanStatus.Failed(e.errorCode)
                    } catch (_: SecurityException) {
                        ScanStatus.Failed(null)
                    }
            }
    }

    fun stopScan() {
        scanJob?.cancel()
        scan.value = ScanStatus.Idle
    }

    /** Premier appairage : l'appareil devient de confiance et le service le joindra. */
    fun trust(device: ScannedDevice) {
        viewModelScope.launch { c.trustedDevices.trust(device.toKnown()) }
    }

    fun forget(id: DeviceId) {
        viewModelScope.launch { c.trustedDevices.forget(id) }
    }

    fun prepare() = c.session.prepare()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val SCAN_DURATION = 20.seconds

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { DevicesViewModel(c) } }
    }
}
