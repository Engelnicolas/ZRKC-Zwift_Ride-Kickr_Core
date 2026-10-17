package dev.kickzwift.protocol.trainer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/** Trames construites octet par octet d'après la norme FTMS, indépendamment du codec. */
class FtmsCodecTest {
    @Test
    fun `commandes simples du point de controle`() {
        assertEquals("00", FtmsCodec.requestControl().hex())
        assertEquals("01", FtmsCodec.reset().hex())
        assertEquals("07", FtmsCodec.startOrResume().hex())
        assertEquals("08 01", FtmsCodec.stop().hex())
        assertEquals("08 02", FtmsCodec.pause().hex())
    }

    @Test
    fun `puissance cible ERG en sint16 little-endian`() {
        assertEquals("05 c8 00", FtmsCodec.targetPower(200).hex())
        assertEquals("05 e8 03", FtmsCodec.targetPower(1_000).hex())
        assertThrows<IllegalArgumentException> { FtmsCodec.targetPower(-1) }
    }

    @Test
    fun `parametres de simulation`() {
        // vent 0 ; pente 2 % = 200 = c8 00 ; Crr 0,004 = 40 = 28 ; cw 0,51 = 51 = 33
        assertEquals("11 00 00 c8 00 28 33", FtmsCodec.simulation(2.0).hex())
        // pente -1,5 % = -150 = 0xff6a
        assertEquals("11 00 00 6a ff 28 33", FtmsCodec.simulation(-1.5).hex())
        // vent de face 2,5 m/s = 2500 = c4 09
        assertEquals("11 c4 09 00 00 28 33", FtmsCodec.simulation(0.0, windMps = 2.5).hex())
        assertThrows<IllegalArgumentException> { FtmsCodec.simulation(400.0) }
        assertThrows<IllegalArgumentException> { FtmsCodec.simulation(0.0, crr = 0.03) }
    }

    @Test
    fun `Indoor Bike Data - tous les champs`() {
        val frame =
            unhex(
                "fe 1f" + // drapeaux : bits 1 à 12, bit 0 à 0 donc vitesse présente
                    " 54 0b" + // vitesse 2900 -> 29,00 km/h
                    " c4 09" + // vitesse moyenne 2500 -> 25,00 km/h
                    " b4 00" + // cadence 180 -> 90 rpm
                    " aa 00" + // cadence moyenne 170 -> 85 rpm
                    " 39 30 00" + // distance 12345 m (uint24)
                    " 19 00" + // résistance 25
                    " fa 00" + // puissance 250 W
                    " e6 00" + // puissance moyenne 230 W
                    " 78 00 58 02 0a" + // énergie : 120 kcal, 600 kcal/h, 10 kcal/min
                    " 91" + // FC 145
                    " 55" + // MET 8,5
                    " 10 0e" + // 3600 s écoulées
                    " 00 00", // 0 s restantes
            )
        val expected =
            IndoorBikeData(
                speedKmh = 29.0,
                averageSpeedKmh = 25.0,
                cadenceRpm = 90.0,
                averageCadenceRpm = 85.0,
                totalDistanceM = 12_345,
                resistanceLevel = 25,
                powerW = 250,
                averagePowerW = 230,
                totalEnergyKcal = 120,
                energyPerHourKcal = 600,
                energyPerMinuteKcal = 10,
                heartRate = 145,
                metabolicEquivalent = 8.5,
                elapsedTimeS = 3_600,
                remainingTimeS = 0,
            )
        assertEquals(FtmsFrame.IndoorBike(expected), FtmsCodec.decodeIndoorBikeData(frame))
    }

    @Test
    fun `Indoor Bike Data - bit 0 a 1, pas de vitesse`() {
        val decoded = FtmsCodec.decodeIndoorBikeData(unhex("41 00 fa 00")) as FtmsFrame.IndoorBike
        assertNull(decoded.data.speedKmh)
        assertEquals(250, decoded.data.powerW)
        val negative = FtmsCodec.decodeIndoorBikeData(unhex("40 00 00 00 fb ff")) as FtmsFrame.IndoorBike
        assertEquals(-5, negative.data.powerW, "puissance en sint16")
    }

    @Test
    fun `conversion vers RidingData`() {
        val decoded = FtmsCodec.decodeIndoorBikeData(unhex("44 00 54 0b b5 00 fa 00")) as FtmsFrame.IndoorBike
        val data = decoded.data.toRidingData(atMs = 5)
        assertEquals(29.0, data.speedKmh, 1e-9)
        assertEquals(91, data.cadenceRpm, "181 demi-tours -> 90,5 -> 91")
        assertEquals(250, data.powerW)
        assertNull(data.heartRate)
    }

    @Test
    fun `reponses du point de controle`() {
        val ok = FtmsCodec.decodeControlResponse(unhex("80 00 01")) as FtmsFrame.ControlResponse
        assertEquals(FtmsCodec.OP_REQUEST_CONTROL, ok.requestOpcode)
        assertTrue(ok.succeeded)
        val refused = FtmsCodec.decodeControlResponse(unhex("80 11 05")) as FtmsFrame.ControlResponse
        assertEquals(FtmsCodec.RESULT_CONTROL_NOT_PERMITTED, refused.result)
        assertFalse(refused.succeeded)
        assertInstanceOf(FtmsFrame.Malformed::class.java, FtmsCodec.decodeControlResponse(unhex("80 00")))
        assertInstanceOf(FtmsFrame.Malformed::class.java, FtmsCodec.decodeControlResponse(unhex("11 00 01")))
    }

    @Test
    fun `trames tronquees et aleatoires sans exception`() {
        val full = unhex("fe 1f 54 0b c4 09 b4 00 aa 00 39 30 00 19 00 fa 00 e6 00 78 00 58 02 0a 91 55 10 0e 00 00")
        for (len in 0 until full.size) {
            assertInstanceOf(FtmsFrame.Malformed::class.java, FtmsCodec.decodeIndoorBikeData(full.copyOf(len)), "longueur $len")
        }
        val random = Random(0x2AD2)
        repeat(20_000) {
            FtmsCodec.decodeIndoorBikeData(random.nextBytes(random.nextInt(0, 40)))
            FtmsCodec.decodeControlResponse(random.nextBytes(random.nextInt(0, 6)))
        }
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private fun unhex(s: String) =
        s
            .trim()
            .split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
