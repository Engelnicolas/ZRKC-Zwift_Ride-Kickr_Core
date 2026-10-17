package dev.kickzwift.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dev.kickzwift.protocol.settings.AppSettings
import dev.kickzwift.protocol.settings.RideMemory
import dev.kickzwift.protocol.settings.RideMemoryCodec
import dev.kickzwift.protocol.settings.SettingsCodec
import dev.kickzwift.protocol.wire.MalformedFrameException
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore typé sur un codec protobuf de `:protocol` : un fichier illisible repart des
 * valeurs par défaut au lieu de bloquer l'app.
 */
internal fun <T> protoStore(
    context: Context,
    file: String,
    default: T,
    decode: (ByteArray) -> T,
    encode: (T) -> ByteArray,
): DataStore<T> =
    DataStoreFactory.create(
        serializer =
            object : Serializer<T> {
                override val defaultValue: T = default

                override suspend fun readFrom(input: InputStream): T =
                    try {
                        decode(input.readBytes())
                    } catch (e: MalformedFrameException) {
                        throw CorruptionException("$file illisible", e)
                    }

                override suspend fun writeTo(
                    t: T,
                    output: OutputStream,
                ) {
                    output.write(encode(t))
                }
            },
        corruptionHandler = ReplaceFileCorruptionHandler { default },
        produceFile = { context.applicationContext.dataStoreFile(file) },
    )

/** Réglages (settings.pb), modifiables à chaud : la sortie en cours les relit. */
class SettingsStore(
    context: Context,
) {
    private val store = protoStore(context, "settings.pb", AppSettings(), SettingsCodec::decode, SettingsCodec::encode)

    val settings: Flow<AppSettings> = store.data

    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.updateData(change)
    }
}

/** Dernière vitesse et cinq derniers parcours (ride_state.pb). */
class MemoryStore(
    context: Context,
) {
    private val store = protoStore(context, "ride_state.pb", RideMemory(), RideMemoryCodec::decode, RideMemoryCodec::encode)

    val memory: Flow<RideMemory> = store.data

    suspend fun update(change: (RideMemory) -> RideMemory) {
        store.updateData(change)
    }
}
