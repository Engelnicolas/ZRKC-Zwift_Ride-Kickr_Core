package dev.kickzwift.ble

import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Contrat entre lots (spécification, « Architecture »).
// Implémenté au lot 4 (couche BLE) puis aux lots 5 et 6 par RideManager et KickrManager.

/** Une manette : pour le Ride, la liaison au pod gauche, qui agrège les 16 commandes. */
interface Controller {
    val id: DeviceId
    val link: StateFlow<LinkState>
    val battery: StateFlow<Int?>
    val firmware: StateFlow<String?>

    /** Gestes déjà détectés. */
    val events: Flow<ButtonEvent>
}

/** Le KICKR CORE en protocole Zwift. Seul TrainerWriter appelle [apply]. */
interface Trainer {
    val link: StateFlow<LinkState>
    val data: StateFlow<RidingData?>

    suspend fun apply(target: TrainerTarget): Result<Unit>

    /** Requête 520 : rapport réellement appliqué par le trainer. */
    suspend fun readGearRatio(): Double?

    /**
     * Trame brute du protocole Zwift, pour l'essai du renvoi des réglages seulement : hors de
     * [TrainerWriter][dev.kickzwift.ble.trainer.TrainerWriter], sans bornes. Échoue en FTMS.
     */
    suspend fun writeFrame(frame: ByteArray): Result<Unit> =
        Result.failure(UnsupportedOperationException("trames brutes non prises en charge"))
}
