package dev.kickzwift.ble.scan

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.annotation.RequiresPermission
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.device.KnownDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/** Un appareil vu au scan, candidat au premier appairage. */
data class ScannedDevice(
    val id: DeviceId,
    val name: String?,
    val kind: DeviceKind,
    val rssi: Int,
    val randomAddress: Boolean,
) {
    /** L'appareil tel qu'il sera mémorisé s'il devient de confiance. */
    fun toKnown(): KnownDevice = KnownDevice(id, kind, name ?: kind.name, randomAddress)
}

class ScanFailedException(
    val errorCode: Int,
) : Exception("scan refusé par Android, code $errorCode")

/**
 * Recherche des appareils, au premier plan seulement : une fois mémorisés, ils sont joints
 * directement par leur adresse. Aucun filtre : le lot 4 accepte tout appareil BLE.
 */
class DeviceScanner {
    /** Annonces reçues tant que le flux est collecté ; le scan s'arrête à l'annulation. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun scan(): Flow<ScannedDevice> =
        callbackFlow {
            val scanner = BluetoothLeScannerCompat.getScanner()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        result.toScanned()?.let { trySend(it) }
                    }

                    override fun onBatchScanResults(results: List<ScanResult>) {
                        results.forEach { r -> r.toScanned()?.let { trySend(it) } }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        close(ScanFailedException(errorCode))
                    }
                }
            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .setUseHardwareBatchingIfSupported(false)
                    .build()
            scanner.startScan(null, settings, callback)
            awaitClose { scanner.stopScan(callback) }
        }

    private fun ScanResult.toScanned(): ScannedDevice? {
        val address = device.address
        if (!KnownDevice.isValidAddress(address)) return null
        // Le nom vient de l'annonce : BluetoothDevice.getName() exigerait BLUETOOTH_CONNECT.
        val name = scanRecord?.deviceName
        val uuids = scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString() }
        return ScannedDevice(DeviceId(address), name, DeviceKind.classify(name, uuids), rssi, isRandom(device))
    }

    private fun isRandom(device: BluetoothDevice): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            device.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM
        } else {
            KnownDevice.looksStaticRandom(device.address)
        }
}
