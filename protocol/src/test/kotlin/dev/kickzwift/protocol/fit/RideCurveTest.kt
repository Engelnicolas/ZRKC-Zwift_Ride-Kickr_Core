package dev.kickzwift.protocol.fit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideCurveTest {
    private val start = 1_792_000_000_000L

    private fun sample(
        seconds: Long,
        powerW: Int,
        heartRate: Int?,
    ) = FitSample(start + seconds * 1_000, powerW, 80, 30.0, seconds * 8.0, heartRate)

    @Test
    fun `moyennes par tranche de 30 s`() {
        val curve = RideCurve.of(listOf(sample(0, 100, 120), sample(29, 200, 130), sample(30, 150, 140), sample(59, 151, null)))
        assertEquals(listOf(CurvePoint(150, 125), CurvePoint(151, 140)), curve.points)
    }

    @Test
    fun `une pause ne laisse pas de tranche vide`() {
        val curve = RideCurve.of(listOf(sample(0, 100, 120), sample(600, 200, 150)))
        assertEquals(listOf(CurvePoint(100, 120), CurvePoint(200, 150)), curve.points)
    }

    @Test
    fun `sans capteur cardiaque - frequence cardiaque absente`() {
        val curve = RideCurve.of(listOf(sample(0, 100, null), sample(1, 110, 0)))
        assertEquals(listOf(CurvePoint(105, null)), curve.points)
    }

    @Test
    fun `aucune mesure - courbe vide`() {
        assertEquals(RideCurve.EMPTY, RideCurve.of(emptyList()))
    }

    @Test
    fun `octets - aller-retour`() {
        val curve = RideCurve(listOf(CurvePoint(0, null), CurvePoint(277, 158), CurvePoint(65_535, 255)))
        val bytes = curve.toBytes()
        assertEquals(9, bytes.size)
        assertEquals(curve, RideCurve.fromBytes(bytes))
    }

    @Test
    fun `octets - longueur incoherente, courbe vide`() {
        assertEquals(RideCurve.EMPTY, RideCurve.fromBytes(ByteArray(4)))
    }
}
