package dev.kickzwift.app.ui.activities

import dev.kickzwift.protocol.fit.CurvePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideCurveChartTest {
    @Test
    fun `echelle - serree sur les deux courbes, bornes multiples de 20`() {
        assertEquals(40..180, chartScale(listOf(CurvePoint(170, 102), CurvePoint(44, 166))))
        assertEquals(100..160, chartScale(listOf(CurvePoint(160, null), CurvePoint(100, null))))
    }

    @Test
    fun `echelle - jamais nulle`() {
        assertEquals(0..20, chartScale(listOf(CurvePoint(0, null))))
        assertEquals(120..140, chartScale(listOf(CurvePoint(120, 120))))
    }
}
