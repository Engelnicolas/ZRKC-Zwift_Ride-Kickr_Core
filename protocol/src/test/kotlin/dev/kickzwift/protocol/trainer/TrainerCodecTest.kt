package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.TrainerTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class TrainerCodecTest {
    @Test
    fun `message de debogage du firmware`() {
        // Trame réelle : « RIDE_ON(0) »
        val frame = unhex("2a 08 03 12 0d 22 0b 52 49 44 45 5f 4f 4e 28 30 29 00")
        assertEquals(TrainerFrame.DebugLog("RIDE_ON(0)"), TrainerCodec.decode(frame))
    }

    @Test
    fun `conversion vers RidingData`() {
        // Trame de référence de Makinolo : 190 W, 80 rpm, 829 -> 29,8 km/h si centièmes de m/s.
        val riding = TrainerCodec.decode(unhex("03 08 be 01 10 50 18 bd 06 20 00 28 e2 ba 01 30 8b eb 01"))
        val data = (riding as TrainerFrame.Riding).fields.toRidingData(atMs = 1_000)
        assertEquals(190, data.powerW)
        assertEquals(80, data.cadenceRpm)
        assertEquals(29.844, data.speedKmh, 1e-9)
        assertNull(data.heartRate, "une FC à 0 signifie : pas de capteur")
    }

    @Test
    fun `consigne Neutral - pente 0 seule`() {
        assertEquals(listOf(TrainerCodec.simulation(0.0).toList()), TrainerCodec.encode(TrainerTarget.Neutral).map { it.toList() })
    }

    @Test
    fun `consigne de simulation - pente puis rapport et masses`() {
        val frames = TrainerCodec.encode(TrainerTarget.Simulation(gradePct = 2.0, gearRatio = 2.4, riderKg = 75.0, bikeKg = 9.5))
        assertEquals(2, frames.size)
        assertEquals(TrainerCodec.simulation(2.0).toList(), frames[0].toList())
        // 0x04 { 5: { 2: 24000, 4: 950, 5: 7500 } }
        assertEquals("04 2a 0a 10 c0 bb 01 20 b6 07 28 cc 3a", frames[1].hex())
    }

    @Test
    fun `consigne ERG - champ PowerTarget`() {
        assertEquals("04 18 c8 01", TrainerCodec.encode(TrainerTarget.Erg(200)).single().hex())
        assertThrows<IllegalArgumentException> { TrainerCodec.powerTarget(-1) }
    }

    @Test
    fun `pente negative en zigzag`() {
        // -1 % -> InclineX100 = -100 -> zigzag 199 = c7 01
        assertEquals("04 22 0b 08 00 10 c7 01 18 ec 27 20 90 03", TrainerCodec.simulation(-1.0).hex())
    }

    @Test
    fun `trames tronquees ou inconnues`() {
        val riding = unhex("03 08 00 10 3e 18 8b 01 20 00 28 f7 89 01 30 88 ea 01")
        for (len in 2 until riding.size) {
            val decoded = TrainerCodec.decode(riding.copyOf(len))
            if (decoded !is TrainerFrame.Riding) assertInstanceOf(TrainerFrame.Malformed::class.java, decoded, "longueur $len")
        }
        assertEquals(TrainerFrame.Malformed("trame vide"), TrainerCodec.decode(byteArrayOf()))
        assertEquals(TrainerFrame.Other(0x19), TrainerCodec.decode(byteArrayOf(0x19, 0x10, 0x53)))
        assertInstanceOf(TrainerFrame.Malformed::class.java, TrainerCodec.decode(unhex("3c 08 88 04")))
    }

    @Test
    fun `aucune entree aleatoire ne leve d'exception`() {
        val random = Random(0x03)
        val opcodes = byteArrayOf(0x03, 0x3c, 0x2a)
        repeat(20_000) {
            val frame = random.nextBytes(random.nextInt(0, 40))
            if (frame.isNotEmpty() && random.nextBoolean()) frame[0] = opcodes[random.nextInt(opcodes.size)]
            TrainerCodec.decode(frame)
        }
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private fun unhex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
