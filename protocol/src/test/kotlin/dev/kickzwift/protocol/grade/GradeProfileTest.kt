package dev.kickzwift.protocol.grade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GradeProfileTest {
    private val eps = 1e-9

    /** 1 km à 2 %, puis 1 km à 8 % : distances et altitudes exactes. */
    private val twoRamps = GradeProfile.fromProfile(listOf(0.0, 1_000.0, 2_000.0), listOf(100.0, 120.0, 200.0))

    @Test
    fun `haversine - un degre de latitude`() {
        // 2πR / 360 avec R = 6 371 km
        val d = GradeProfile.haversineM(TrackPoint(45.0, 6.0, 0.0), TrackPoint(46.0, 6.0, 0.0))
        assertEquals(111_194.93, d, 0.01)
    }

    @Test
    fun `pente constante sur une trace reelle en coordonnees`() {
        // Le long d'un méridien, +5 m par point espacé de 0,001° (~111 m) : ~4,5 %
        val points = (0..20).map { TrackPoint(45.0 + it * 0.001, 6.0, 500.0 + it * 5.0) }
        val profile = GradeProfile.fromTrack(points)
        val expected = 5.0 / GradeProfile.haversineM(points[0], points[1]) * 100
        assertEquals(expected, profile.gradeAt(500.0), 1e-6)
        assertEquals(100.0, profile.elevationGainM, eps)
    }

    @Test
    fun `pente moyenne sur les 50 m devant la position`() {
        assertEquals(2.0, twoRamps.gradeAt(900.0), eps)
        assertEquals(2.0, twoRamps.gradeAt(950.0), eps, "fenêtre 950-1000 entièrement à 2 %")
        assertEquals(5.0, twoRamps.gradeAt(975.0), eps, "moitié à 2 %, moitié à 8 %")
        assertEquals(8.0, twoRamps.gradeAt(1_000.0), eps)
    }

    @Test
    fun `fin de parcours - fenetre ramenee aux derniers metres`() {
        assertEquals(8.0, twoRamps.gradeAt(1_990.0), eps)
        assertEquals(8.0, twoRamps.gradeAt(2_500.0), eps)
        assertFalse(twoRamps.isFinished(1_999.0))
        assertTrue(twoRamps.isFinished(2_000.0))
    }

    @Test
    fun `pente bornee a -10 et +16 pourcents`() {
        val steep = GradeProfile.fromProfile(listOf(0.0, 100.0, 200.0), listOf(0.0, 25.0, 5.0))
        assertEquals(25.0, steep.gradeAt(10.0), eps)
        assertEquals(16.0, steep.targetGrade(10.0), eps)
        assertEquals(-20.0, steep.gradeAt(120.0), eps)
        assertEquals(-10.0, steep.targetGrade(120.0), eps)
        assertEquals(12.0, steep.targetGrade(10.0, GradeLimits(-5.0, 12.0)), eps)
    }

    @Test
    fun `altitude interpolee et bornee au parcours`() {
        assertEquals(110.0, twoRamps.elevationAt(500.0), eps)
        assertEquals(100.0, twoRamps.elevationAt(-50.0), eps)
        assertEquals(200.0, twoRamps.elevationAt(9_999.0), eps)
    }

    @Test
    fun `apercu - denivele positif et pente maximale`() {
        val rolling = GradeProfile.fromProfile(listOf(0.0, 500.0, 1_000.0, 1_500.0), listOf(0.0, 30.0, 10.0, 40.0))
        assertEquals(60.0, rolling.elevationGainM, eps)
        assertEquals(6.0, rolling.maxGradePct(), eps)
    }

    @Test
    fun `points confondus ecartes, traces invalides refusees`() {
        val p = TrackPoint(45.0, 6.0, 100.0)
        val q = TrackPoint(45.001, 6.0, 105.0)
        assertEquals(GradeProfile.fromTrack(listOf(p, q)).totalDistanceM, GradeProfile.fromTrack(listOf(p, p, q, q)).totalDistanceM, eps)
        assertThrows<IllegalArgumentException> { GradeProfile.fromTrack(listOf(p, p)) }
        assertThrows<IllegalArgumentException> { GradeProfile.fromTrack(emptyList()) }
        assertThrows<IllegalArgumentException> { GradeProfile.fromProfile(listOf(0.0, 10.0, 10.0), listOf(0.0, 1.0, 2.0)) }
        assertThrows<IllegalArgumentException> { GradeLimits(5.0, -5.0) }
    }
}
