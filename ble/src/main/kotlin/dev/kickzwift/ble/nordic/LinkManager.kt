package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.util.Log
import dev.kickzwift.protocol.device.Redaction
import no.nordicsemi.android.ble.BleManager

/**
 * Base commune des managers Nordic. Telle quelle, elle se connecte à n'importe quel appareil BLE
 * sans poignée de main : c'est le lot 4. RideManager et KickrManager (lots 5 et 6) en dérivent
 * pour exiger leurs services et faire leur poignée de main.
 */
open class LinkManager(
    context: Context,
) : BleManager(context) {
    /** Poignée de main propre à l'appareil, une fois les services découverts. */
    open suspend fun handshake() = Unit

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
