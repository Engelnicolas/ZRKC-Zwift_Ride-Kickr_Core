package dev.kickzwift.ble.device

import dev.kickzwift.ble.Controller
import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.gesture.GestureTiming
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.ride.RideButtonTracker
import dev.kickzwift.protocol.ride.buttonEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest

/** Réglages des gestes : délais, seuils des palettes, et correspondance qui dit quels gestes attendre. */
data class ControllerConfig(
    val timing: GestureTiming = GestureTiming(),
    val paddlePress: Int = RideButtonTracker.DEFAULT_PRESS,
    val paddleRelease: Int = RideButtonTracker.DEFAULT_RELEASE,
    val mapping: ButtonMapping = ButtonMapping.DEFAULT,
)

/**
 * Une manette du Ride. Les gestes ne dépendent que de la correspondance du moment : la
 * changer s'applique aussitôt ; changer les délais ou les seuils relance la détection.
 */
class RideController(
    override val id: DeviceId,
    override val link: StateFlow<LinkState>,
    private val endpoint: RideEndpoint,
    private val config: StateFlow<ControllerConfig>,
    private val now: () -> Long,
) : Controller {
    override val battery: StateFlow<Int?> = endpoint.battery.asStateFlow()
    override val firmware: StateFlow<String?> = endpoint.firmware.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val events: Flow<ButtonEvent> =
        config
            .distinctUntilChangedBy { Triple(it.timing, it.paddlePress, it.paddleRelease) }
            .flatMapLatest { c ->
                endpoint.frames.buttonEvents(id, c.timing, ::gesturesFor, now, c.paddlePress, c.paddleRelease)
            }

    private fun gesturesFor(button: Button): Set<Gesture> = config.value.mapping.gesturesFor(button)
}

/** Le trainer, quel que soit le protocole de la connexion du moment. */
class BleTrainer(
    val id: DeviceId,
    override val link: StateFlow<LinkState>,
    private val endpoint: TrainerEndpoint,
) : Trainer {
    override val data: StateFlow<RidingData?> = endpoint.data.asStateFlow()

    override suspend fun apply(target: TrainerTarget): Result<Unit> {
        val c = endpoint.connection ?: return Result.failure(IllegalStateException("trainer non prêt"))
        return attempt { c.apply(target) }
    }

    override suspend fun readGearRatio(): Double? = endpoint.connection?.let { c -> attempt { c.readGearRatio() }.getOrNull() }

    override suspend fun writeFrame(frame: ByteArray): Result<Unit> {
        val c = endpoint.connection ?: return Result.failure(IllegalStateException("trainer non prêt"))
        return attempt { c.writeRaw(frame) }
    }

    // Toute erreur de la pile BLE devient un échec, sauf l'annulation qui doit remonter.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
