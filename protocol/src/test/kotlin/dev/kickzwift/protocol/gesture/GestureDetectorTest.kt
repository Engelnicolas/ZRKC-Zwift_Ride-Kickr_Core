package dev.kickzwift.protocol.gesture

import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.Gesture.DOUBLE_PRESS
import dev.kickzwift.protocol.Gesture.LONG_PRESS
import dev.kickzwift.protocol.Gesture.PRESS
import dev.kickzwift.protocol.ride.ButtonEdge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class GestureDetectorTest {
    private val pod = DeviceId("E2:93:3B:6A:0F:BC")
    private val up = Button.SHIFT_UP_LEFT
    private val down = Button.SHIFT_DOWN_LEFT

    /** Rejoue des fronts (instant, bouton, appuyé) puis avance jusqu'à [until] ; renvoie (bouton, geste, instant). */
    private fun run(
        detector: GestureDetector,
        until: Long,
        vararg edges: Triple<Long, Button, Boolean>,
    ): List<Triple<Button, Gesture, Long>> {
        val events = mutableListOf<ButtonEvent>()
        for ((t, b, d) in edges) events += detector.onEdge(ButtonEdge(b, d), t)
        events += detector.advanceTo(until)
        events.forEach { assertEquals(pod, it.device) }
        return events.map { Triple(it.button, it.gesture, it.atMs) }
    }

    private fun press(
        t: Long,
        b: Button = up,
    ) = Triple(t, b, true)

    private fun release(
        t: Long,
        b: Button = up,
    ) = Triple(t, b, false)

    private fun detector(vararg g: Gesture) = GestureDetector(pod, gestures = { g.toSet() })

    @Test
    fun `appui simple emis des la confirmation, sans attendre le relachement`() {
        val d = detector(PRESS)
        assertEquals(listOf(Triple(up, PRESS, 30L)), run(d, 1_000, press(0), release(200)))
    }

    @Test
    fun `une bascule plus courte que l'anti-rebond est ignoree`() {
        val d = detector(PRESS)
        assertEquals(emptyList<Any>(), run(d, 1_000, press(0), release(29)))
    }

    @Test
    fun `un rebond au relachement ne cree pas de second appui`() {
        val d = detector(PRESS, DOUBLE_PRESS)
        // relâché 10 ms puis réappuyé : c'est le même appui, donc un appui simple
        val events = run(d, 2_000, press(0), release(100), press(110), release(200))
        assertEquals(listOf(Triple(up, PRESS, 500L)), events)
    }

    @Test
    fun `appui long emis a l'echeance, bouton toujours tenu`() {
        val d = detector(PRESS, LONG_PRESS)
        assertEquals(listOf(Triple(up, LONG_PRESS, 600L)), run(d, 650, press(0)))
        assertEquals(emptyList<Any>(), run(d, 2_000, release(900)), "le relâchement ne produit rien")
    }

    @Test
    fun `avec appui long configure, l'appui court part au relachement`() {
        val d = detector(PRESS, LONG_PRESS)
        assertEquals(listOf(Triple(up, PRESS, 230L)), run(d, 1_000, press(0), release(200)))
    }

    @Test
    fun `double appui dans la fenetre`() {
        val d = detector(PRESS, DOUBLE_PRESS)
        val events = run(d, 2_000, press(0), release(100), press(350), release(450))
        assertEquals(listOf(Triple(up, DOUBLE_PRESS, 380L)), events)
    }

    @Test
    fun `appui simple retarde jusqu'a la fin de la fenetre si le double est configure`() {
        val d = detector(PRESS, DOUBLE_PRESS)
        assertEquals(listOf(Triple(up, PRESS, 400L)), run(d, 1_000, press(0), release(100)))
    }

    @Test
    fun `second appui hors fenetre - deux appuis simples`() {
        val d = detector(PRESS, DOUBLE_PRESS)
        val events = run(d, 2_000, press(0), release(100), press(450), release(550))
        assertEquals(listOf(Triple(up, PRESS, 400L), Triple(up, PRESS, 850L)), events)
    }

    @Test
    fun `second appui trop court - appui simple date du moment ou il devient certain`() {
        val d = detector(PRESS, DOUBLE_PRESS)
        // fenêtre jusqu'à 400 ; second appui à 390 annulé à 410 par l'anti-rebond
        val events = run(d, 2_000, press(0), release(100), press(390), release(410))
        assertEquals(listOf(Triple(up, PRESS, 410L)), events)
    }

    @Test
    fun `gestes non configures jamais emis`() {
        val d = detector(LONG_PRESS)
        assertEquals(emptyList<Any>(), run(d, 1_000, press(0), release(200)), "appui court sans PRESS configuré")
        assertEquals(listOf(Triple(up, LONG_PRESS, 1_600L)), run(d, 3_000, press(1_000), release(2_000)))
    }

    @Test
    fun `boutons independants`() {
        val d = detector(PRESS)
        val events = run(d, 1_000, press(0, up), press(10, down), release(100, up), release(120, down))
        assertEquals(listOf(Triple(up, PRESS, 30L), Triple(down, PRESS, 40L)), events)
    }

    @Test
    fun `prochaine echeance`() {
        val d = detector(PRESS, LONG_PRESS)
        assertNull(d.nextDeadline())
        d.onEdge(ButtonEdge(up, true), 1_000)
        assertEquals(1_030L, d.nextDeadline())
        d.advanceTo(1_030)
        assertEquals(1_600L, d.nextDeadline())
    }

    @Test
    fun `fronts aleatoires - horodatages croissants, au plus un geste par appui confirme`() {
        val random = Random(42)
        val all = Gesture.entries.toSet()
        repeat(200) {
            val d = GestureDetector(pod, gestures = { all })
            var t = 0L
            val held = mutableSetOf<Button>()
            val events = mutableListOf<ButtonEvent>()
            repeat(60) {
                t += random.nextLong(0, 400)
                val b = listOf(up, down).random(random)
                val pressNow = b !in held
                if (pressNow) held += b else held -= b
                events += d.onEdge(ButtonEdge(b, pressNow), t)
            }
            events += d.advanceTo(t + 10_000)
            assertTrue(events.zipWithNext().all { (a, b) -> a.atMs <= b.atMs }, "horodatages croissants")
            assertNull(d.nextDeadline(), "plus rien en attente")
        }
    }
}
