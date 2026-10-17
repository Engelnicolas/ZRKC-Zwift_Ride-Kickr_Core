package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button

/** Front d'une commande : enfoncée ([down] vrai) ou relâchée. */
data class ButtonEdge(
    val button: Button,
    val down: Boolean,
)

/**
 * Transforme le flux d'états du Ride en appuis et relâchements.
 *
 * Chaque trame 0x23 porte l'état complet : on compare donc à l'état précédent,
 * sinon le relâchement d'une commande ferait croire à un nouvel appui des autres.
 * Les palettes deviennent des appuis par seuil avec hystérésis (valeurs de départ
 * de la spécification : appui à 50, relâchement sous 20).
 */
class RideButtonTracker(
    private val pressThreshold: Int = DEFAULT_PRESS,
    private val releaseThreshold: Int = DEFAULT_RELEASE,
) {
    init {
        require(releaseThreshold in 0..pressThreshold) { "seuil de relâchement hors de [0, seuil d'appui]" }
    }

    var held: Set<Button> = emptySet()
        private set

    fun update(state: RideState): List<ButtonEdge> {
        val now = RideLayout.buttonsFromBits(state.pressedBits) + heldPaddles(state)
        // Relâchements d'abord, puis appuis, chacun dans l'ordre de l'énumération.
        val edges =
            (held - now).sorted().map { ButtonEdge(it, down = false) } +
                (now - held).sorted().map { ButtonEdge(it, down = true) }
        held = now
        return edges
    }

    private fun heldPaddles(state: RideState): Set<Button> =
        RideLayout.PADDLES
            .filter { paddle ->
                val pull = state.axis(paddle.axis) * paddle.direction
                val threshold = if (paddle.button in held) releaseThreshold else pressThreshold
                pull >= threshold
            }.map { it.button }
            .toSet()

    companion object {
        const val DEFAULT_PRESS = 50
        const val DEFAULT_RELEASE = 20
    }
}
