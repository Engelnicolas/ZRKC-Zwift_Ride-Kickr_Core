package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import dev.kickzwift.ble.device.HeartRateEndpoint
import dev.kickzwift.protocol.hr.HeartRateCodec
import java.util.UUID

/**
 * Capteur cardiaque au profil standard (service 0x180D), comme le COROS HRM : mesure 0x2A37
 * en notification, environ une par seconde. Pas de poignée de main, ni de surveillance du
 * silence : un capteur posé sans contact peut se taire sans être perdu ; une vraie perte
 * passe par la déconnexion BLE.
 */
class HeartRateManager(
    context: Context,
    handler: Handler,
    private val endpoint: HeartRateEndpoint,
) : LinkManager(context, handler) {
    private var measurement: BluetoothGattCharacteristic? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        measurement = gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(MEASUREMENT)
        return measurement != null
    }

    override fun initialize() {
        setNotificationCallback(measurement).with { _, data ->
            data.value?.let { endpoint.bpm.value = HeartRateCodec.decode(it) }
        }
        enableNotifications(measurement).enqueue()
    }

    override fun onServicesInvalidated() {
        measurement = null
    }

    override fun released() {
        endpoint.bpm.value = null
    }

    companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    }
}
