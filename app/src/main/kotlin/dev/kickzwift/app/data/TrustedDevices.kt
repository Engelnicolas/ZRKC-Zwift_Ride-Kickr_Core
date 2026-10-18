package dev.kickzwift.app.data

import android.content.Context
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.device.KnownDevicesCodec
import kotlinx.coroutines.flow.Flow

/**
 * Appareils de confiance (spécification, « Sécurité ») : l'adresse de chaque
 * appareil est mémorisée au premier appairage, et l'app ne se connecte qu'à ces adresses.
 */
class TrustedDevices(
    context: Context,
) {
    // Fichier illisible : on repart sans appareil, l'utilisateur les rappairera.
    private val store = protoStore(context, "known_devices.pb", emptyList(), KnownDevicesCodec::decode, KnownDevicesCodec::encode)

    val devices: Flow<List<KnownDevice>> = store.data

    /** Mémorise l'appareil, ou met à jour son nom s'il l'était déjà. */
    suspend fun trust(device: KnownDevice) {
        store.updateData { list -> list.filterNot { it.id == device.id } + device }
    }

    suspend fun forget(id: DeviceId) {
        store.updateData { list -> list.filterNot { it.id == id } }
    }
}
