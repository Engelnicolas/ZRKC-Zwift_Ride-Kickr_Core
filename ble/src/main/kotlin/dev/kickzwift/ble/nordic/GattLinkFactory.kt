package dev.kickzwift.ble.nordic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dev.kickzwift.ble.link.GattLink
import dev.kickzwift.protocol.device.KnownDevice

/**
 * Fabrique les liaisons vers les appareils de confiance, jointes par leur adresse sans scan.
 * Lot 4 : tout appareil reçoit un [LinkManager] générique. Les lots 5 et 6 y choisiront
 * RideManager ou KickrManager selon [KnownDevice.kind].
 */
class GattLinkFactory(
    context: Context,
) {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter =
        checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) {
            "Bluetooth absent, alors que le manifeste l'exige"
        }

    fun create(device: KnownDevice): GattLink = NordicGattLink(remoteDevice(device), LinkManager(context))

    // Sans le type d'adresse, Android tente une adresse publique et ne joint jamais
    // une adresse aléatoire qu'il n'a pas vue au scan depuis le démarrage du Bluetooth.
    private fun remoteDevice(device: KnownDevice): BluetoothDevice =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val type = if (device.randomAddress) BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC
            adapter.getRemoteLeDevice(device.id.address, type)
        } else {
            adapter.getRemoteDevice(device.id.address)
        }
}
