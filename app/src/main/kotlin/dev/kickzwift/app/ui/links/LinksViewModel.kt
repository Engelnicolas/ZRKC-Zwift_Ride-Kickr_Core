package dev.kickzwift.app.ui.links

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

data class TrustedRow(
    val device: KnownDevice,
    /** null quand le service est arrêté. */
    val state: LinkState?,
)

sealed interface ScanStatus {
    data object Idle : ScanStatus

    data object Scanning : ScanStatus

    /** Code d'erreur d'Android, ou null si la permission a été retirée. */
    data class Failed(
        val code: Int?,
    ) : ScanStatus
}

data class LinksUiState(
    val serviceRunning: Boolean = false,
    val scan: ScanStatus = ScanStatus.Idle,
    val trusted: List<TrustedRow> = emptyList(),
    val found: List<ScannedDevice> = emptyList(),
)

/** Écran provisoire du lot 4 : appairage, service et état des liaisons. Remplacé au lot 8. */
class LinksViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val found = MutableStateFlow<Map<DeviceId, ScannedDevice>>(emptyMap())
    private val scan = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    private var scanJob: Job? = null

    val state: StateFlow<LinksUiState> =
        combine(
            container.trustedDevices.devices,
            container.links.states,
            container.links.running,
            found,
            scan,
        ) { trusted, states, running, seen, scanStatus ->
            val trustedIds = trusted.map { it.id }.toSet()
            LinksUiState(
                serviceRunning = running,
                scan = scanStatus,
                trusted = trusted.map { TrustedRow(it, states[it.id]) },
                found =
                    seen.values
                        // Les appareils anonymes noieraient la liste.
                        .filter { it.id !in trustedIds && (it.name != null || it.kind != DeviceKind.OTHER) }
                        .sortedWith(compareBy<ScannedDevice> { it.kind == DeviceKind.OTHER }.thenByDescending { it.rssi }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LinksUiState())

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
                            container.scanner.scan().collect { d -> found.update { it + (d.id to d) } }
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
        viewModelScope.launch { container.trustedDevices.trust(device.toKnown()) }
    }

    fun forget(id: DeviceId) {
        viewModelScope.launch { container.trustedDevices.forget(id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val SCAN_DURATION = 20.seconds

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { LinksViewModel(container) }
            }
    }
}
