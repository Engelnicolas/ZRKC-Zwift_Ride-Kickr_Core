package dev.kickzwift.app.data

import dev.kickzwift.protocol.fit.CurvePoint
import dev.kickzwift.protocol.fit.FitSample
import dev.kickzwift.protocol.fit.RideCurve
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurveBackfillTest {
    private val start = 1_792_692_195_391L

    private fun ride(
        firstMs: Long,
        powerW: Int,
    ) = listOf(FitSample(firstMs, powerW, 80, 30.0, 0.0, 140))

    @Test
    fun `le FIT parti avec l'activite donne sa courbe`() {
        val rides = listOf(ride(start - 3_600_000, 100), ride(start + 800, 200))
        assertEquals(listOf(CurvePoint(200, 140)), curveFor(start, rides).points)
    }

    @Test
    fun `aucun FIT ne correspond - courbe vide`() {
        assertEquals(RideCurve.EMPTY, curveFor(start, listOf(ride(start + 61_000, 100))))
        assertEquals(RideCurve.EMPTY, curveFor(start, emptyList()))
    }
}
