package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RideDecoderTest {
    // Trame de repos du pod gauche : bitmap 0xFFFFFFFF, quatre axes à 0.
    private val rest = hex("23 08 ff ff ff ff 0f 1a 04 08 00 10 00 1a 04 08 01 10 00 1a 04 08 02 10 00 1a 04 08 03 10 00")

    @Test
    fun `la trame de repos ne porte aucun appui`() {
        val state = (RideDecoder.decode(rest) as RideFrame.Buttons).state
        assertEquals(0, state.pressedBits)
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0), state.axes)
    }

    @Test
    fun `l'axe est un sint32 zigzag`() {
        // Palette gauche à fond : 199 en varint brut, soit -100.
        val frame = hex("23 08 ff ff ff ff 0f 1a 05 08 00 10 c7 01")
        assertEquals(-100, (RideDecoder.decode(frame) as RideFrame.Buttons).state.axis(0))
        assertEquals(+100, ProtoReader.zigzag(200))
    }

    @Test
    fun `les autres types de trame sont reconnus sans commande`() {
        assertEquals(RideFrame.Heartbeat, RideDecoder.decode(byteArrayOf(0x15)))
        assertEquals(RideFrame.Other(0x19), RideDecoder.decode(hex("19 10 53")))
        assertEquals(RideFrame.Other(0x2a), RideDecoder.decode(hex("2a 08 03")))
    }

    @Test
    fun `une trame tronquee est rejetee`() {
        // Coupure entre deux champs complets (bitmap seul, puis 1, 2, 3 axes) : trame encore valide.
        val boundaries = setOf(7, 13, 19, 25)
        for (len in 0 until rest.size) {
            val decoded = RideDecoder.decode(rest.copyOf(len))
            val expected = if (len in boundaries) RideFrame.Buttons::class.java else RideFrame.Malformed::class.java
            assertInstanceOf(expected, decoded, "longueur $len")
        }
    }

    @Test
    fun `un axe hors bornes est rejete`() {
        // Valeur zigzag 202 = +101.
        assertInstanceOf(RideFrame.Malformed::class.java, RideDecoder.decode(hex("23 08 ff ff ff ff 0f 1a 05 08 00 10 ca 01")))
    }

    @Test
    fun `aucune entree aleatoire ne leve d'exception`() {
        val random = Random(0x23)
        repeat(20_000) {
            val frame = random.nextBytes(random.nextInt(0, 40))
            if (frame.isNotEmpty() && random.nextBoolean()) frame[0] = 0x23
            RideDecoder.decode(frame)
        }
    }

    @Test
    fun `les bits 11 et 15 ne sont attribues a aucune commande`() {
        val bits = (1 shl 11) or (1 shl 15) or (1 shl 8)
        assertEquals(setOf(Button.SHIFT_UP_LEFT), RideLayout.buttonsFromBits(bits))
        assertEquals((1 shl 11) or (1 shl 15), RideLayout.unmappedBits(bits))
    }

    @Test
    fun `appuis simultanes sur les deux manettes`() {
        val tracker = RideButtonTracker()
        // Vitesse haut gauche + vitesse haut droite, puis la gauche relâchée en premier.
        val both = RideState(pressedBits = (1 shl 8) or (1 shl 12), axes = emptyMap())
        val right = RideState(pressedBits = 1 shl 12, axes = emptyMap())
        assertEquals(
            listOf(ButtonEdge(Button.SHIFT_UP_LEFT, true), ButtonEdge(Button.SHIFT_UP_RIGHT, true)),
            tracker.update(both),
        )
        assertEquals(listOf(ButtonEdge(Button.SHIFT_UP_LEFT, false)), tracker.update(right))
        assertTrue(tracker.update(right).isEmpty(), "une trame identique ne produit aucun front")
    }

    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
