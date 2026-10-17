package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.Handler
import android.util.Log
import dev.kickzwift.protocol.device.Redaction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import no.nordicsemi.android.ble.BleManager
import kotlin.time.Duration

/**
 * Base commune des managers Nordic. Telle quelle, elle se connecte à n'importe quel appareil BLE
 * sans poignée de main. RideManager, KickrManager et FtmsManager en dérivent pour exiger leurs
 * services et faire leur poignée de main. Les rappels arrivent sur [handler], le fil BLE de
 * l'app, jamais sur le fil principal : une remise à plat d'urgence reste possible même s'il
 * est bloqué.
 */
open class LinkManager(
    context: Context,
    handler: Handler,
) : BleManager(context, handler) {
    /** Poignée de main propre à l'appareil, une fois les services découverts. */
    open suspend fun handshake() = Unit

    /** Silence au-delà duquel la liaison est tenue pour perdue ; null : pas de surveillance. */
    open val silenceTimeout: Duration? = null

    private val alive = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Signes de vie de l'appareil, que surveille [silenceTimeout]. */
    val beats: Flow<Unit> = alive.asSharedFlow()

    protected fun markAlive() {
        alive.tryEmit(Unit)
    }

    /** Appelée une fois la connexion fermée : l'appareil ne doit plus être utilisé. */
    open fun released() = Unit

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean = true

    override fun getMinLogPriority(): Int = Log.INFO

    // Journal de la bibliothèque, adresses tronquées.
    override fun log(
        priority: Int,
        message: String,
    ) {
        Log.println(priority, TAG, Redaction.addresses(message))
    }

    private companion object {
        const val TAG = "KickZwift/BLE"
    }
}
