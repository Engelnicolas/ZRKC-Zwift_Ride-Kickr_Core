package dev.kickzwift.protocol.trainer

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResendTestPlanTest {
    private val steps = ResendTestPlan.steps(9.5, 75.0)

    @Test
    fun `etapes - echauffement a plat puis E a G, chacune en 2,40 puis 3,49`() {
        assertEquals(listOf("-", "E", "E", "F", "F", "G", "G"), steps.map { it.code })
        assertEquals(listOf(null, 2.40, 3.49, 2.40, 3.49, 2.40, 3.49), steps.map { it.ratio })
        assertEquals(
            ResendTestPlan.WARMUP_MS + 3 * (ResendTestPlan.STEP_MS + ResendTestPlan.HOLD_MS),
            steps.sumOf { it.durationMs },
        )
        assertArrayEquals(TrainerCodec.simulation(0.0), steps[0].frames.single())
        steps.drop(1).forEach { s -> assertArrayEquals(TrainerCodec.physical(s.ratio!!, 9.5, 75.0), s.frames.single()) }
    }

    @Test
    fun `renvois - aucun en E, la pente en F, pente puis rapport et masses en G`() {
        assertTrue(steps.take(3).all { it.keepalive.isEmpty() })
        steps.subList(3, 5).forEach { assertArrayEquals(TrainerCodec.simulation(0.0), it.keepalive.single()) }
        steps.subList(5, 7).forEach { s ->
            assertEquals(2, s.keepalive.size)
            assertArrayEquals(TrainerCodec.simulation(0.0), s.keepalive[0])
            assertArrayEquals(TrainerCodec.physical(s.ratio!!, 9.5, 75.0), s.keepalive[1])
        }
    }

    @Test
    fun `resultat - debut et fin d'etape, sans les arrets`() {
        val samples =
            listOf(
                ResendTestPlan.Sample(1_000, 300, 60), // avant EARLY_MS : ignoré
                ResendTestPlan.Sample(3_000, 150, 60),
                ResendTestPlan.Sample(6_000, 130, 60),
                ResendTestPlan.Sample(12_000, 50, 60),
                ResendTestPlan.Sample(13_000, 0, 0), // pédales arrêtées : ignoré
                ResendTestPlan.Sample(14_000, 60, 62),
            )
        val r = ResendTestPlan.result(steps[2], samples, 84.5)!!
        assertEquals(140, r.earlyW)
        assertEquals(55, r.powerW)
        assertEquals(61, r.cadenceRpm)
        assertEquals(ResendTestPlan.realBikeW(61.0, 3.49, 84.5).toInt(), r.realBikeW)
        assertNull(ResendTestPlan.result(steps[0], samples, 84.5), "échauffement")
        assertNull(ResendTestPlan.result(steps[2], samples.take(3), 84.5), "rien après SETTLE_MS")
        assertNull(ResendTestPlan.result(steps[2], samples.drop(3), 84.5)!!.earlyW)
    }

    @Test
    fun `vrai velo et remise en etat`() {
        assertEquals(125.0, ResendTestPlan.realBikeW(60.0, 3.49, 84.5), 1.5)
        assertArrayEquals(TrainerCodec.simulation(0.0), ResendTestPlan.RESTORE[0])
        assertArrayEquals(TrainerCodec.gearRatio(2.40), ResendTestPlan.RESTORE[1])
    }
}
