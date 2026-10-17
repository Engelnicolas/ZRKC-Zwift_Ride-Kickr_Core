package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddleHysteresisTest {
    private fun right(value: Int) = RideState(pressedBits = 0, axes = mapOf(1 to value))

    private fun left(value: Int) = RideState(pressedBits = 0, axes = mapOf(0 to value))

    @Test
    fun `course mesuree de la palette droite`() {
        // Séquence relevée le 17 octobre : +5, +100, +15, +2, 0.
        val tracker = RideButtonTracker()
        val edges = listOf(5, 100, 15, 2, 0).map { tracker.update(right(it)) }
        assertEquals(
            listOf(
                emptyList(),
                listOf(ButtonEdge(Button.PADDLE_RIGHT, down = true)),
                listOf(ButtonEdge(Button.PADDLE_RIGHT, down = false)),
                emptyList(),
                emptyList(),
            ),
            edges,
        )
    }

    @Test
    fun `entre les deux seuils, l'etat ne change pas`() {
        val tracker = RideButtonTracker()
        assertEquals(emptyList<ButtonEdge>(), tracker.update(right(40)), "40 < 50 : pas d'appui")
        tracker.update(right(60))
        assertEquals(emptyList<ButtonEdge>(), tracker.update(right(30)), "30 ≥ 20 : toujours tenu")
        assertEquals(listOf(ButtonEdge(Button.PADDLE_RIGHT, false)), tracker.update(right(19)))
    }

    @Test
    fun `la palette gauche tire vers le negatif`() {
        val tracker = RideButtonTracker()
        assertEquals(emptyList<ButtonEdge>(), tracker.update(left(+100)), "le sens positif n'est pas un appui")
        assertEquals(listOf(ButtonEdge(Button.PADDLE_LEFT, true)), tracker.update(left(-100)))
    }
}
