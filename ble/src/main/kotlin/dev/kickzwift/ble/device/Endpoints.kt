package dev.kickzwift.ble.device

import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.ride.RideFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference

// Un appareil change de connexion GATT à chaque reprise ; un « point d'accès » est ce qui
// lui survit : flux reçus, batterie, firmware, et la connexion utilisable du moment.

/** Ce que publie une manette du Ride, d'une connexion à l'autre. */
class RideEndpoint {
    val frames = MutableSharedFlow<RideFrame>(extraBufferCapacity = FRAME_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val battery = MutableStateFlow<Int?>(null)
    val firmware = MutableStateFlow<String?>(null)

    private companion object {
        const val FRAME_BUFFER = 64
    }
}

/** Une connexion au trainer prête à recevoir des consignes (protocole Zwift ou FTMS). */
interface TrainerConnection {
    /** Lève une exception de la couche BLE si l'écriture échoue. */
    suspend fun apply(target: TrainerTarget)

    /** Rapport appliqué par le trainer, ou null s'il ne le publie pas ou ne répond pas. */
    suspend fun readGearRatio(): Double?
}

/** Ce que publie le trainer, d'une connexion à l'autre. */
class TrainerEndpoint {
    val data = MutableStateFlow<RidingData?>(null)
    private val current = AtomicReference<TrainerConnection?>(null)

    /** Connexion du moment, après sa poignée de main ; null entre deux connexions. */
    val connection: TrainerConnection? get() = current.get()

    fun attach(connection: TrainerConnection) = current.set(connection)

    /** Ne retire que [connection] : une connexion plus récente reste en place. */
    fun detach(connection: TrainerConnection) {
        current.compareAndSet(connection, null)
    }
}
