package dev.kickzwift.protocol

// Types communs, contrat entre lots (spécification, « Modèle de données et interfaces »).

enum class LinkState { NOT_FOUND, SCANNING, CONNECTING, HANDSHAKE, READY, RECONNECTING, LOST }

enum class Gesture { PRESS, LONG_PRESS, DOUBLE_PRESS }

/** Les 16 commandes du Zwift Ride, relevées le 17 octobre 2026. */
enum class Button {
    // manette gauche
    UP,
    DOWN,
    LEFT,
    RIGHT,
    SHIFT_UP_LEFT,
    SHIFT_DOWN_LEFT,
    POWER_UP_LEFT,
    PADDLE_LEFT,

    // manette droite
    A,
    B,
    Y,
    Z,
    SHIFT_UP_RIGHT,
    SHIFT_DOWN_RIGHT,
    POWER_UP_RIGHT,
    PADDLE_RIGHT,
}

/** Identifiant d'un appareil BLE : son adresse, telle que mémorisée au premier appairage. */
@JvmInline
value class DeviceId(
    val address: String,
)

data class ButtonEvent(
    val device: DeviceId,
    val button: Button,
    val gesture: Gesture,
    val atMs: Long,
)

data class RidingData(
    val powerW: Int,
    val cadenceRpm: Int,
    val speedKmh: Double,
    val heartRate: Int?,
    val atMs: Long,
)

sealed interface Action {
    /** +1, -1, +3, -3 */
    data class Shift(
        val delta: Int,
    ) : Action

    /** ±0,5 */
    data class Grade(
        val deltaPct: Double,
    ) : Action

    data object PlayPause : Action

    data object NextTrack : Action

    data object PreviousTrack : Action

    data object VolumeUp : Action

    data object VolumeDown : Action

    data object None : Action
}

sealed interface TrainerTarget {
    data class Simulation(
        val gradePct: Double,
        val gearRatio: Double,
        val riderKg: Double,
        val bikeKg: Double,
    ) : TrainerTarget

    data class Erg(
        val watts: Int,
    ) : TrainerTarget

    /** Pente 0, rapport inchangé. */
    data object Neutral : TrainerTarget
}
