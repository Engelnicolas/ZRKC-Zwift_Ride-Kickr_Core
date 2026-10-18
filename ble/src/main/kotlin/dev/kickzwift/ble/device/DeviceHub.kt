package dev.kickzwift.ble.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.kickzwift.ble.link.GattLink
import dev.kickzwift.ble.link.LinkSupervisor
import dev.kickzwift.ble.nordic.FtmsManager
import dev.kickzwift.ble.nordic.HeartRateManager
import dev.kickzwift.ble.nordic.KickrManager
import dev.kickzwift.ble.nordic.LinkManager
import dev.kickzwift.ble.nordic.NordicGattLink
import dev.kickzwift.ble.nordic.RideManager
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.trainer.FtmsGears
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Fabrique les liaisons des appareils de confiance, jointes par leur adresse sans scan, et
 * tient leurs points d'accès : le Ride reçoit un [RideManager], le KICKR un [KickrManager]
 * ou, en repli, un [FtmsManager] ; un capteur cardiaque un [HeartRateManager] ; tout autre
 * appareil un [LinkManager] générique.
 *
 * [ftmsFallback] est relu à chaque tentative : le changer s'applique à la connexion suivante.
 */
class DeviceHub(
    context: Context,
    private val ftmsFallback: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter =
        checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) {
            "Bluetooth absent, alors que le manifeste l'exige"
        }
    private val rides = ConcurrentHashMap<DeviceId, RideEndpoint>()
    private val trainers = ConcurrentHashMap<DeviceId, TrainerEndpoint>()
    private val heartRates = ConcurrentHashMap<DeviceId, HeartRateEndpoint>()
    private val gears = FtmsGears()

    // Fil des rappels BLE, à part du fil principal (spécification, « Architecture »).
    private val handler = Handler(HandlerThread("ble").apply { start() }.looper)

    /** Horloge monotone des gestes et des mesures. */
    val now: () -> Long = SystemClock::elapsedRealtime

    fun newLink(device: KnownDevice): GattLink {
        val manager =
            when (device.kind) {
                DeviceKind.RIDE -> RideManager(context, handler, rideEndpoint(device.id))
                DeviceKind.KICKR ->
                    if (ftmsFallback()) {
                        FtmsManager(context, handler, trainerEndpoint(device.id), gears, now, log)
                    } else {
                        KickrManager(context, handler, trainerEndpoint(device.id), now, log)
                    }
                DeviceKind.HEART_RATE -> HeartRateManager(context, handler, heartRateEndpoint(device.id))
                DeviceKind.OTHER -> LinkManager(context, handler)
            }
        return NordicGattLink(remoteDevice(device), manager)
    }

    fun controller(
        device: KnownDevice,
        links: LinkSupervisor,
        config: StateFlow<ControllerConfig>,
    ): RideController = RideController(device.id, links.stateOf(device.id), rideEndpoint(device.id), config, now)

    fun trainer(
        device: KnownDevice,
        links: LinkSupervisor,
    ): BleTrainer = BleTrainer(device.id, links.stateOf(device.id), trainerEndpoint(device.id))

    fun rideEndpoint(id: DeviceId): RideEndpoint = rides.getOrPut(id) { RideEndpoint() }

    fun trainerEndpoint(id: DeviceId): TrainerEndpoint = trainers.getOrPut(id) { TrainerEndpoint() }

    fun heartRateEndpoint(id: DeviceId): HeartRateEndpoint = heartRates.getOrPut(id) { HeartRateEndpoint() }

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
