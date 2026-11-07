package dev.kickzwift.protocol.fit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitDecoderTest {
    private val start = 1_792_000_000_000L
    private val samples =
        (0 until 120).map { FitSample(start + it * 1_000L, 200 + it, 90, 36.0, it * 10.0, if (it % 2 == 0) 140 else null) }

    private fun file(minBytes: Int = 0) =
        FitEncoder.encode(samples, FitSummary(start, start + 120_000, 120_000, 1_200.0, 210, 319, 90), minBytes)

    @Test
    fun `relit les mesures ecrites par FitEncoder, remplissage compris`() {
        val read = FitDecoder.samples(file(FitEncoder.MIN_IMPORT_BYTES))
        assertEquals(samples.size, read.size)
        assertEquals(samples.map { it.epochMs }, read.map { it.epochMs })
        assertEquals(samples.map { it.powerW }, read.map { it.powerW })
        assertEquals(samples.map { it.cadenceRpm }, read.map { it.cadenceRpm })
        assertEquals(samples.map { it.heartRate }, read.map { it.heartRate })
        assertEquals(36.0, read[5].speedKmh, 0.01)
        assertEquals(50.0, read[5].distanceM, 0.01)
    }

    @Test
    fun `octet modifie, CRC faux - aucune mesure`() {
        val bytes = file().also { it[40] = (it[40] + 1).toByte() }
        assertTrue(FitDecoder.samples(bytes).isEmpty())
    }

    @Test
    fun `fichier tronque ou vide - aucune mesure`() {
        assertTrue(FitDecoder.samples(ByteArray(0)).isEmpty())
        assertTrue(FitDecoder.samples(file().copyOf(100)).isEmpty())
    }

    @Test
    fun `longueur annoncee trop grande, CRC recalcule - aucune mesure`() {
        val bytes = file()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(4, bytes.size * 2)
        val crc = FitEncoder.crc(bytes, 0, bytes.size - 2)
        b.putShort(bytes.size - 2, crc.toShort())
        assertTrue(FitDecoder.samples(bytes).isEmpty())
    }
}
