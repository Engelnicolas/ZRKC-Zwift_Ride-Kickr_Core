package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button

/**
 * Correspondance bit / axe → commande, relevée par capture guidée le 17 octobre 2026
 * et rejouée à l'identique (16 sur 16). Les noms viennent de la personne qui tenait
 * les manettes.
 */
object RideLayout {
    val BITS: Map<Int, Button> =
        mapOf(
            0 to Button.LEFT,
            1 to Button.UP,
            2 to Button.RIGHT,
            3 to Button.DOWN,
            4 to Button.A,
            5 to Button.B,
            6 to Button.Y,
            7 to Button.Z,
            8 to Button.SHIFT_UP_LEFT,
            9 to Button.SHIFT_DOWN_LEFT,
            10 to Button.POWER_UP_LEFT,
            12 to Button.SHIFT_UP_RIGHT,
            13 to Button.SHIFT_DOWN_RIGHT,
            14 to Button.POWER_UP_RIGHT,
        )

    /** Une palette = un axe et le sens dans lequel il part quand on tire. */
    data class Paddle(
        val button: Button,
        val axis: Int,
        val direction: Int,
    )

    val PADDLES: List<Paddle> =
        listOf(
            Paddle(Button.PADDLE_LEFT, axis = 0, direction = -1),
            Paddle(Button.PADDLE_RIGHT, axis = 1, direction = +1),
        )

    private val MAPPED_MASK = BITS.keys.fold(0) { acc, bit -> acc or (1 shl bit) }

    fun buttonsFromBits(pressedBits: Int): Set<Button> = BITS.filterKeys { pressedBits and (1 shl it) != 0 }.values.toSet()

    /** Bits appuyés qui ne correspondent à aucune commande connue (11 et 15 vus le 16 octobre). */
    fun unmappedBits(pressedBits: Int): Int = pressedBits and MAPPED_MASK.inv()
}
