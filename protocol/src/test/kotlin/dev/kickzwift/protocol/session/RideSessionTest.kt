package dev.kickzwift.protocol.session

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.gear.GearTable
import dev.kickzwift.protocol.grade.GradeProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RideSessionTest {
    private val config = SessionConfig(GearTable.ZWIFT, startGear = 12)

    private fun riding(
        mode: RideMode = RideMode.FreeGrade(),
        at: Long = 0,
    ) = RideSession(config).apply {
        prepare()
        start(mode, at)
    }

    private fun data(
        atMs: Long,
        powerW: Int = 200,
        cadence: Int = 90,
        kmh: Double = 36.0,
    ) = RidingData(powerW, cadence, kmh, null, atMs)

    @Test
    fun `machine a etats - toutes les transitions`() {
        val s = RideSession(config)
        assertEquals(RidePhase.IDLE, s.phase)
        assertNull(s.target())
        assertThrows<IllegalStateException> { s.start(RideMode.FreeGrade(), 0) }
        s.prepare()
        assertEquals(RidePhase.PREPARING, s.phase)
        assertNull(s.target())
        s.start(RideMode.FreeGrade(), 0)
        assertEquals(RidePhase.RIDING, s.phase)
        s.pause(1_000)
        assertEquals(RidePhase.PAUSED, s.phase)
        assertEquals(TrainerTarget.Neutral, s.target())
        s.resume(2_000)
        assertEquals(RidePhase.RIDING, s.phase)
        s.finish(5_000)
        assertEquals(RidePhase.FINISHED, s.phase)
        assertEquals(TrainerTarget.Neutral, s.target())
        assertEquals(4_000, s.stats(9_999).elapsedMs)
        s.close()
        assertEquals(RidePhase.IDLE, s.phase)
        assertNull(s.mode)

        // Abandon de la préparation, et pause depuis la pause : sans effet.
        s.prepare()
        s.close()
        assertEquals(RidePhase.IDLE, s.phase)
        s.pause(0)
        s.resume(0)
        s.finish(0)
        assertEquals(RidePhase.IDLE, s.phase)
    }

    @Test
    fun `pause puis fin depuis la pause`() {
        val s = riding()
        s.onAction(Action.PauseResume, 1_000)
        assertEquals(RidePhase.PAUSED, s.phase)
        assertEquals(PauseReason.USER, s.snapshot(1_000).pauseReason)
        s.finish(3_000)
        assertEquals(RidePhase.FINISHED, s.phase)
        assertEquals(1_000, s.stats(10_000).elapsedMs)
        assertFalse(s.onAction(Action.PauseResume, 4_000))
    }

    @Test
    fun `consigne de simulation - pente, rapport et masses`() {
        val s = riding(RideMode.FreeGrade(startGradePct = 2.0))
        assertEquals(TrainerTarget.Simulation(2.0, 2.40, 75.0, 9.5), s.target())
        assertTrue(s.onAction(Action.Shift(+1), 0))
        assertTrue(s.onAction(Action.Grade(+0.5), 0))
        assertEquals(TrainerTarget.Simulation(2.5, 2.61, 75.0, 9.5), s.target())
    }

    @Test
    fun `pente libre - bornee a -10 et +16 pour cent`() {
        val s = riding(RideMode.FreeGrade(startGradePct = 15.8))
        s.onAction(Action.Grade(+0.5), 0)
        assertEquals(16.0, s.snapshot(0).gradePct)
        assertFalse(s.onAction(Action.Grade(+0.5), 0))
        assertEquals(-10.0, riding(RideMode.FreeGrade(-30.0)).snapshot(0).gradePct)
    }

    @Test
    fun `ERG - pas de 10 W, plafond, vitesses desactivees`() {
        val s = riding(RideMode.Erg(595))
        assertEquals(TrainerTarget.Erg(595), s.target())
        assertTrue(s.onAction(Action.Grade(+0.5), 0))
        assertEquals(TrainerTarget.Erg(600), s.target())
        assertFalse(s.onAction(Action.Grade(+0.5), 0))
        assertTrue(s.onAction(Action.Grade(-0.5), 0))
        assertEquals(590, s.snapshot(0).ergWatts)
        assertFalse(s.onAction(Action.Shift(+1), 0))
        assertEquals(12, s.gear)
        assertEquals(TrainerTarget.Erg(600), riding(RideMode.Erg(900)).target())
    }

    @Test
    fun `au plus 10 changements de vitesse par seconde`() {
        val s = riding()
        val applied = (0 until 15).count { s.onAction(Action.Shift(if (it % 2 == 0) +1 else -1), 100L + it) }
        assertEquals(10, applied)
        assertTrue(s.onAction(Action.Shift(+1), 1_100))
        assertEquals(11, s.stats(0).shifts)
    }

    @Test
    fun `butee - un changement sans effet n'est pas compte`() {
        val s = riding()
        s.onAction(Action.Shift(+100), 0)
        assertEquals(24, s.gear)
        assertFalse(s.onAction(Action.Shift(+1), 2_000))
        assertEquals(1, s.stats(0).shifts)
    }

    @Test
    fun `vitesse changee en pause, appliquee a la reprise`() {
        val s = riding()
        s.pause(0)
        s.onAction(Action.Shift(-3), 10)
        assertEquals(TrainerTarget.Neutral, s.target())
        s.resume(20)
        assertEquals(1.86, (s.target() as TrainerTarget.Simulation).gearRatio)
    }

    @Test
    fun `distance - integrale de la vitesse, en RIDING seulement, trous ignores`() {
        val s = riding()
        s.onRidingData(data(0))
        s.onRidingData(data(1_000)) // 36 km/h = 10 m/s
        s.onRidingData(data(2_000))
        assertEquals(20.0, s.stats(2_000).distanceM, 1e-9)
        s.onRidingData(data(10_000)) // trou de 8 s : non intégré
        assertEquals(20.0, s.stats(10_000).distanceM, 1e-9)
        s.pause(10_000)
        s.onRidingData(data(11_000))
        assertEquals(20.0, s.stats(11_000).distanceM, 1e-9)
        s.resume(12_000)
        s.onRidingData(data(12_500))
        s.onRidingData(data(13_000))
        assertEquals(25.0, s.stats(13_000).distanceM, 1e-9)
    }

    @Test
    fun `puissance - moyenne sur 3 s, moyenne et maximum de la sortie`() {
        val s = riding()
        s.onRidingData(data(0, powerW = 100, cadence = 0))
        s.onRidingData(data(1_000, powerW = 200))
        s.onRidingData(data(2_000, powerW = 300))
        s.onRidingData(data(4_500, powerW = 400))
        val snap = s.snapshot(4_500)
        assertEquals(350, snap.power3sW)
        assertEquals(250, snap.stats.avgPowerW)
        assertEquals(400, snap.stats.maxPowerW)
        assertEquals(90, snap.stats.avgCadenceRpm)
        assertNull(s.snapshot(9_000).power3sW)
        assertNull(riding().snapshot(0).power3sW)
    }

    @Test
    fun `KICKR perdu plus de 30 s - pause automatique`() {
        val s = riding()
        s.onTrainerLink(ready = false, nowMs = 1_000)
        s.tick(30_999)
        assertEquals(RidePhase.RIDING, s.phase)
        assertTrue(s.snapshot(30_999).trainerLost)
        s.tick(31_000)
        assertEquals(RidePhase.PAUSED, s.phase)
        assertEquals(PauseReason.TRAINER_LOST, s.snapshot(31_000).pauseReason)

        val back = riding()
        back.onTrainerLink(false, 0)
        back.onTrainerLink(true, 10_000)
        back.tick(60_000)
        assertEquals(RidePhase.RIDING, back.phase)
    }

    @Test
    fun `parcours - pente du profil, denivele, pause en fin de parcours`() {
        // 100 m à plat puis 100 m à +5 %.
        val profile = GradeProfile.fromProfile(listOf(0.0, 100.0, 200.0), listOf(0.0, 0.0, 5.0))
        val s = riding(RideMode.Route("test", profile))
        assertEquals(0.0, s.snapshot(0).gradePct, 1e-9)
        assertFalse(s.onAction(Action.Grade(+0.5), 0))
        var t = 0L
        s.onRidingData(data(t))
        repeat(12) {
            t += 1_000
            s.onRidingData(data(t))
        }
        assertEquals(120.0, s.stats(t).distanceM, 1e-9)
        assertEquals(5.0, s.snapshot(t).gradePct, 1e-9)
        assertEquals(1.0, s.stats(t).elevationGainM, 1e-9)
        s.tick(t)
        assertEquals(RidePhase.RIDING, s.phase)
        repeat(9) {
            t += 1_000
            s.onRidingData(data(t))
        }
        s.tick(t)
        assertEquals(RidePhase.PAUSED, s.phase)
        assertEquals(PauseReason.ROUTE_END, s.snapshot(t).pauseReason)
        assertEquals(5.0, s.stats(t).elevationGainM, 1e-9)
    }

    @Test
    fun `pente libre - denivele virtuel`() {
        val s = riding(RideMode.FreeGrade(startGradePct = 10.0))
        s.onRidingData(data(0))
        s.onRidingData(data(1_000))
        assertEquals(1.0, s.stats(1_000).elevationGainM, 1e-9)
        val erg = riding(RideMode.Erg(200))
        erg.onRidingData(data(0))
        erg.onRidingData(data(1_000))
        assertEquals(0.0, erg.stats(1_000).elevationGainM)
        assertEquals(0.0, erg.snapshot(1_000).gradePct)
    }

    @Test
    fun `nouvelle sortie - statistiques remises a zero`() {
        val s = riding()
        s.onRidingData(data(0))
        s.onRidingData(data(1_000))
        s.onAction(Action.Shift(+1), 0)
        s.finish(1_000)
        s.close()
        s.prepare()
        s.start(RideMode.FreeGrade(), 5_000)
        assertEquals(RideStats(elapsedMs = 0), s.stats(5_000))
        assertEquals(13, s.gear)
        assertFalse(s.onAction(Action.PlayPause, 0))
    }

    @Test
    fun `configuration invalide refusee`() {
        assertThrows<IllegalArgumentException> { SessionConfig(GearTable.ZWIFT, 12, riderKg = 0.0) }
        assertThrows<IllegalArgumentException> { SessionConfig(GearTable.ZWIFT, 12, ergMaxW = 0) }
        assertThrows<IllegalArgumentException> { SessionConfig(GearTable.ZWIFT, 12, maxShiftsPerSecond = 0) }
    }
}
