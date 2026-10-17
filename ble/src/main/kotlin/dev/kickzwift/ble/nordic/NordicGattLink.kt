package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothDevice
import dev.kickzwift.ble.link.GattLink
import dev.kickzwift.ble.link.LinkError
import dev.kickzwift.ble.link.LinkError.Reason
import dev.kickzwift.ble.link.LinkException
import dev.kickzwift.ble.link.awaitSilence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend

/** [GattLink] sur la bibliothèque Nordic : un [LinkManager] neuf par tentative. */
class NordicGattLink(
    private val device: BluetoothDevice,
    private val manager: LinkManager,
) : GattLink {
    // Observé dès la création pour ne manquer aucune déconnexion.
    private val connection: Flow<ConnectionState> = manager.stateAsFlow()

    override suspend fun connect() =
        translated {
            // Pas de reprise interne à Nordic : DeviceLink décide des reprises et de leurs délais.
            manager
                .connect(device)
                .useAutoConnect(false)
                .timeout(CONNECT_TIMEOUT_MS)
                .suspend()
        }

    override suspend fun handshake() = translated { manager.handshake() }

    override suspend fun awaitLoss(): LinkError {
        val disconnected = connection.filterIsInstance<ConnectionState.Disconnected>().map { lossOf(it) }
        val silent =
            manager.silenceTimeout?.let { timeout ->
                flow {
                    awaitSilence(manager.beats, timeout)
                    emit(LinkError(Reason.SILENT))
                }
            }
        return (if (silent == null) disconnected else merge(disconnected, silent)).first()
    }

    private fun lossOf(lost: ConnectionState.Disconnected): LinkError =
        LinkError(
            when (lost.reason) {
                ConnectionState.Disconnected.Reason.LINK_LOSS,
                ConnectionState.Disconnected.Reason.TIMEOUT,
                -> Reason.LINK_LOSS
                ConnectionState.Disconnected.Reason.TERMINATE_PEER_USER -> Reason.PEER_DISCONNECTED
                ConnectionState.Disconnected.Reason.NOT_SUPPORTED -> Reason.NOT_SUPPORTED
                else -> Reason.UNKNOWN
            },
        )

    override suspend fun close() {
        // Déconnexion demandée et attendue, puis libération de BluetoothGatt.
        if (manager.isConnected) {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { runCatching { manager.disconnect().suspend() } }
        }
        manager.close()
        manager.released()
    }

    private suspend fun translated(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: RequestFailedException) {
            fail(errorFor(e.status), e)
        } catch (e: DeviceDisconnectedException) {
            fail(LinkError(Reason.LINK_LOSS), e)
        } catch (e: BluetoothDisabledException) {
            fail(LinkError(Reason.BLUETOOTH_OFF), e)
        } catch (e: InvalidRequestException) {
            fail(LinkError(Reason.UNKNOWN), e)
        } catch (e: SecurityException) {
            // Permission BLUETOOTH_CONNECT retirée en cours de route.
            fail(LinkError(Reason.NO_PERMISSION), e)
        }
    }

    private fun fail(
        error: LinkError,
        cause: Throwable,
    ): Nothing = throw LinkException(error, cause)

    internal companion object {
        // Android abandonne lui-même une connexion directe vers 30 s.
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L

        fun errorFor(status: Int): LinkError =
            when (status) {
                FailCallback.REASON_TIMEOUT -> LinkError(Reason.TIMEOUT)
                FailCallback.REASON_DEVICE_NOT_SUPPORTED -> LinkError(Reason.NOT_SUPPORTED)
                FailCallback.REASON_BLUETOOTH_DISABLED -> LinkError(Reason.BLUETOOTH_OFF)
                FailCallback.REASON_DEVICE_DISCONNECTED -> LinkError(Reason.LINK_LOSS)
                else -> LinkError(Reason.GATT_ERROR, status)
            }
    }
}
