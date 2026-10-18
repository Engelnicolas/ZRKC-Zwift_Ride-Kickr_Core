package dev.kickzwift.protocol.trainer

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GearTestPlanTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test
    fun `etapes - echauffement puis A a D, chacune en 2,40 puis 3,49`() {
        val steps = GearTestPlan.steps(9.5, 75.0)
        assertEquals(9, steps.size)
        assertEquals(listOf("-", "A", "A", "B", "B", "C", "C", "D", "D"), steps.map { it.code })
        assertEquals(listOf(null, 2.40, 3.49, 2.40, 3.49, 2.40, 3.49, 2.40, 3.49), steps.map { it.ratio })
        assertEquals(GearTestPlan.WARMUP_MS + 8 * GearTestPlan.STEP_MS, steps.sumOf { it.durationMs })
        // Mêmes trames que prototype/kickr_sequence.py (tests de zwift_hub.py).
        assertEquals("04 2a 04 10 c0 bb 01", hex(steps[1].frames.single()))
        assertEquals(2, steps[3].frames.size)
        assertArrayEquals(TrainerCodec.simulation(0.0), steps[3].frames[0])
        assertEquals(1, steps[4].frames.size)
        assertArrayEquals(TrainerCodec.physical(2.40, 9.5, 75.0), steps[5].frames.single())
        assertArrayEquals(TrainerCodec.combined(0.0, 3.49, 9.5, 75.0), steps[8].frames.single())
    }

    @Test
    fun `trames - masses et trame unique`() {
        // Physical : 2 = 24000, 4 = 950, 5 = 7500.
        assertEquals("04 2a 0a 10 c0 bb 01 20 b6 07 28 cc 3a", hex(TrainerCodec.physical(2.40, 9.5, 75.0)))
        val combined = TrainerCodec.combined(0.0, 2.40, 9.5, 75.0)
        val sim = TrainerCodec.simulation(0.0)
        val phy = TrainerCodec.physical(2.40, 9.5, 75.0)
        assertArrayEquals(sim + phy.copyOfRange(1, phy.size), combined)
    }

    @Test
    fun `vrai velo - 50 W en 2,40 et 125 W en 3,49 a 60 rpm`() {
        assertEquals(50.0, GearTestPlan.realBikeW(60.0, 2.40, 84.5), 1.0)
        assertEquals(125.0, GearTestPlan.realBikeW(60.0, 3.49, 84.5), 1.5)
        assertEquals(18.2, GearTestPlan.realBikeKmh(60.0, 2.40), 0.1)
    }

    @Test
    fun `resultat - fin d'etape seulement, sans les arrets`() {
        val step = GearTestPlan.steps(9.5, 75.0)[2]
        val samples =
            listOf(
                GearTestPlan.Sample(2_000, 200, 60, 20.0), // avant SETTLE_MS : ignoré
                GearTestPlan.Sample(12_000, 100, 60, 25.0),
                GearTestPlan.Sample(13_000, 0, 0, 24.0), // pédales arrêtées : ignoré
                GearTestPlan.Sample(14_000, 120, 62, 27.0),
            )
        val r = GearTestPlan.result(step, samples, 84.5)!!
        assertEquals(110, r.powerW)
        assertEquals(61, r.cadenceRpm)
        assertEquals(26.0, r.speedKmh, 1e-9)
        assertEquals(GearTestPlan.realBikeW(61.0, 3.49, 84.5).toInt(), r.realBikeW)
        assertNull(GearTestPlan.result(GearTestPlan.steps(9.5, 75.0)[0], samples, 84.5))
        assertNull(GearTestPlan.result(step, samples.take(1), 84.5))
    }

    @Test
    fun `remise en etat - a plat puis vitesse 12`() {
        val restore = GearTestPlan.RESTORE
        assertArrayEquals(TrainerCodec.simulation(0.0), restore[0])
        assertArrayEquals(TrainerCodec.gearRatio(2.40), restore[1])
    }
}
