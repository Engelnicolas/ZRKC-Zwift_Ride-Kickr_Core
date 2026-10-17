package dev.kickzwift.protocol.fit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitEncoderTest {
    private val start = 1_792_000_000_000L // 2026-10-15

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun encode(n: Int): ByteArray {
        val samples = (0 until n).map { FitSample(start + it * 1_000L, 200 + it, 90, 36.0, it * 10.0, if (it % 2 == 0) 140 else null) }
        return FitEncoder.encode(samples, FitSummary(start, start + n * 1_000L, n * 1_000L, n * 10.0, 210, 260, 90))
    }

    @Test
    fun `CRC du protocole FIT - valeur de controle de CRC-16 ARC`() {
        assertEquals(0xBB3D, FitEncoder.crc("123456789".toByteArray()))
        assertEquals(0, FitEncoder.crc(ByteArray(0)))
    }

    @Test
    fun `en-tete - taille, version, longueur des donnees, signature et CRC`() {
        val file = encode(60)
        val h = le(file)
        assertEquals(14, file[0].toInt())
        assertEquals(0x20, file[1].toInt())
        assertEquals(file.size - 14 - 2, h.getInt(4))
        assertEquals(".FIT", String(file, 8, 4, Charsets.US_ASCII))
        assertEquals(FitEncoder.crc(file, 0, 12), h.getShort(12).toInt() and 0xFFFF)
        // Le CRC final rend nul le CRC du fichier entier.
        assertEquals(0, FitEncoder.crc(file))
    }

    @Test
    fun `messages - une mesure par echantillon, horodatage FIT`() {
        val small = encode(1)
        val big = encode(11)
        // Une mesure : en-tête 1 + horodatage 4 + distance 4 + vitesse 2 + puissance 2 + cadence 1 + FC 1.
        assertEquals(10 * 15, big.size - small.size)
        // Premier message de données de file_id : type 4 (activité), horodatage FIT.
        val firstData = 14 + 6 + 4 * 3
        assertEquals(0, small[firstData].toInt())
        assertEquals(4, small[firstData + 1].toInt())
        assertEquals(start / 1000 - 631_065_600L, le(small).getInt(firstData + 6).toLong() and 0xFFFFFFFFL)
    }
}
