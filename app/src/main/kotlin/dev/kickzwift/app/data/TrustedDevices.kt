package dev.kickzwift.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.device.KnownDevice
import dev.kickzwift.protocol.device.KnownDevicesCodec
import dev.kickzwift.protocol.wire.MalformedFrameException
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * Appareils de confiance (spécification, « Sécurité et permissions ») : l'adresse de chaque
 * appareil est mémorisée au premier appairage, et l'app ne se connecte qu'à ces adresses.
 */
class TrustedDevices(
    context: Context,
) {
    private val store: DataStore<List<KnownDevice>> =
        DataStoreFactory.create(
            serializer = KnownDevicesSerializer,
            // Fichier illisible : on repart sans appareil, l'utilisateur les rappairera.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
            produceFile = { context.applicationContext.dataStoreFile(FILE) },
        )

    val devices: Flow<List<KnownDevice>> = store.data

    /** Mémorise l'appareil, ou met à jour son nom s'il l'était déjà. */
    suspend fun trust(device: KnownDevice) {
        store.updateData { list -> list.filterNot { it.id == device.id } + device }
    }

    suspend fun forget(id: DeviceId) {
        store.updateData { list -> list.filterNot { it.id == id } }
    }

    private companion object {
        const val FILE = "known_devices.pb"
    }
}

internal object KnownDevicesSerializer : Serializer<List<KnownDevice>> {
    override val defaultValue: List<KnownDevice> = emptyList()

    override suspend fun readFrom(input: InputStream): List<KnownDevice> =
        try {
            KnownDevicesCodec.decode(input.readBytes())
        } catch (e: MalformedFrameException) {
            throw CorruptionException("known_devices.pb illisible", e)
        }

    override suspend fun writeTo(
        t: List<KnownDevice>,
        output: OutputStream,
    ) {
        output.write(KnownDevicesCodec.encode(t))
    }
}
