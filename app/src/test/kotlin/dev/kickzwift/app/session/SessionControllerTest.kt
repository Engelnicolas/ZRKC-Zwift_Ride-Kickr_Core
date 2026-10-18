package dev.kickzwift.app.session

import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityMode
import dev.kickzwift.app.phone.PhoneActions
import dev.kickzwift.ble.Controller
import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.session.PauseReason
import dev.kickzwift.protocol.session.RideMode
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private class FakeController(
        address: String,
        state: LinkState = LinkState.READY,
    ) : Controller {
        override val id = DeviceId(address)
        override val link = MutableStateFlow(state)
        override val battery: StateFlow<Int?> = MutableStateFlow(80)
        override val firmware: StateFlow<String?> = MutableStateFlow("1.2.0")
        val sink = MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 16)
        override val events: Flow<ButtonEvent> = sink

        fun press(
            button: Button,
            at: Long = 0,
        ) = sink.tryEmit(ButtonEvent(id, button, Gesture.PRESS, at))
    }

    private class FakeTrainer : Trainer {
        override val link = MutableStateFlow(LinkState.READY)
        override val data = MutableStateFlow<RidingData?>(null)
        val writes = mutableListOf<TrainerTarget>()

        override suspend fun apply(target: TrainerTarget): Result<Unit> {
            writes += target
            return Result.success(Unit)
        }

        override suspend fun readGearRatio(): Double? =
            (
                writes.lastOrNull {
                    it is TrainerTarget.Simulation
                } as? TrainerTarget.Simulation
            )?.gearRatio
    }

    private class Env(
        private val scope: TestScope,
        settings: AppSettings = AppSettings(),
    ) : SessionEnvironment {
        override val settings = MutableStateFlow(settings)
        val phoneDone = mutableListOf<Action>()
        override val phone = PhoneActions { a -> (a == Action.PlayPause || a == Action.VolumeUp).also { if (it) phoneDone += a } }
        var savedGear: Int? = null
        var fit: ByteArray? = null
        val activities = mutableListOf<Activity>()
        val logs = mutableListOf<String>()

        override fun now(): Long = scope.currentTime

        override fun wallClock(): Long = 1_792_000_000_000L + scope.currentTime

        override suspend fun lastGear(): Int? = savedGear

        override suspend fun saveGear(gear: Int) {
            savedGear = gear
        }

        override suspend fun saveFit(bytes: ByteArray): File? {
            fit = bytes
            return File("sortie.fit")
        }

        override suspend fun saveActivity(activity: Activity): Long {
            activities += activity
            return activities.size.toLong()
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private class Rig(
        val env: Env,
        val controller: SessionController,
        val left: FakeController,
        val right: FakeController,
        val trainer: FakeTrainer,
    )

    private fun TestScope.rig(settings: AppSettings = AppSettings()): Rig {
        val env = Env(this, settings)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val c = SessionController(env, backgroundScope, dispatcher)
        val left = FakeController("E2:93:3B:6A:0F:BC")
        val right = FakeController("F2:DD:0E:DE:3D:BC")
        val trainer = FakeTrainer()
        backgroundScope.launch { c.run(flowOf(SessionDevices(listOf(left, right), trainer))) }
        runCurrent()
        return Rig(env, c, left, right, trainer)
    }

    private fun Rig.ride(mode: RideMode = RideMode.FreeGrade()) {
        controller.prepare()
        controller.start(mode)
    }

    @Test
    fun `sortie en pente libre - consigne, vitesses depuis les manettes, pause sur Z`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            assertEquals(RidePhase.RIDING, r.controller.state.value.snapshot.phase)
            assertEquals(TrainerTarget.Simulation(0.0, 2.40, 75.0, 9.5), r.trainer.writes.first())

            r.left.press(Button.SHIFT_UP_LEFT)
            r.left.press(Button.SHIFT_UP_RIGHT)
            advanceTimeBy(500)
            assertEquals(14, r.controller.state.value.snapshot.gear)
            assertEquals(2.82, (r.trainer.writes.last() as TrainerTarget.Simulation).gearRatio)

            r.left.press(Button.Z)
            advanceTimeBy(500)
            assertEquals(RidePhase.PAUSED, r.controller.state.value.snapshot.phase)
            assertEquals(TrainerTarget.Neutral, r.trainer.writes.last())

            r.left.press(Button.Z)
            advanceTimeBy(500)
            assertEquals(RidePhase.RIDING, r.controller.state.value.snapshot.phase)
            assertEquals(2.82, (r.trainer.writes.last() as TrainerTarget.Simulation).gearRatio)
        }

    @Test
    fun `actions telephone - executees, sans toucher a la sortie, et annoncees`() =
        runTest {
            val r = rig()
            val notices = mutableListOf<ActionNotice>()
            backgroundScope.launch { r.controller.notices.collect { notices += it } }
            r.ride()
            advanceTimeBy(200)
            r.left.press(Button.A) // lecture/pause
            r.left.press(Button.Y) // volume +
            advanceTimeBy(200)
            assertEquals(listOf(Action.PlayPause, Action.VolumeUp), r.env.phoneDone)
            assertEquals(listOf(Action.PlayPause, Action.VolumeUp), notices.map { it.action })
            assertEquals(12, r.controller.state.value.snapshot.gear)
        }

    @Test
    fun `seule la manette active compte - la premiere prete`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            r.right.press(Button.SHIFT_UP_RIGHT) // doublon du pod droit : ignoré
            advanceTimeBy(200)
            assertEquals(12, r.controller.state.value.snapshot.gear)
            r.left.link.value = LinkState.RECONNECTING
            r.right.press(Button.SHIFT_UP_RIGHT) // le pod droit prend le relais
            advanceTimeBy(200)
            assertEquals(13, r.controller.state.value.snapshot.gear)
        }

    @Test
    fun `mode Tester - gestes montres, pas appliques`() =
        runTest {
            val r = rig()
            val seen = mutableListOf<ButtonEvent>()
            backgroundScope.launch { r.controller.events.collect { seen += it } }
            r.ride()
            advanceTimeBy(200)
            r.controller.testing.value = true
            r.left.press(Button.SHIFT_UP_LEFT)
            advanceTimeBy(200)
            assertEquals(listOf(Button.SHIFT_UP_LEFT), seen.map { it.button })
            assertEquals(12, r.controller.state.value.snapshot.gear)
        }

    @Test
    fun `correspondance modifiee - appliquee sans redemarrer`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            r.env.settings.value = AppSettings(mapping = AppSettings().mapping.with(Button.UP, Gesture.PRESS, Action.Shift(+3)))
            r.left.press(Button.UP)
            advanceTimeBy(200)
            assertEquals(15, r.controller.state.value.snapshot.gear)
        }

    @Test
    fun `KICKR perdu plus de 30 s - pause, puis consigne reecrite a la reconnexion`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            r.trainer.link.value = LinkState.RECONNECTING
            advanceTimeBy(29_000)
            assertEquals(RidePhase.RIDING, r.controller.state.value.snapshot.phase)
            advanceTimeBy(1_500)
            val snap = r.controller.state.value.snapshot
            assertEquals(RidePhase.PAUSED, snap.phase)
            assertEquals(PauseReason.TRAINER_LOST, snap.pauseReason)
            // Retour du KICKR : la consigne du moment (à plat, en pause) est réécrite d'office.
            val before = r.trainer.writes.size
            r.trainer.link.value = LinkState.READY
            advanceTimeBy(500)
            assertTrue(r.trainer.writes.size > before)
            assertEquals(TrainerTarget.Neutral, r.trainer.writes.last())
            r.controller.resume()
            advanceTimeBy(500)
            assertTrue(r.trainer.writes.last() is TrainerTarget.Simulation)
        }

    @Test
    fun `ERG - cible reglee depuis l'ecran, bornee`() =
        runTest {
            val r = rig(AppSettings(ergMaxW = 250))
            r.ride(RideMode.Erg(240))
            advanceTimeBy(200)
            r.controller.act(Action.Grade(+0.5))
            r.controller.act(Action.Grade(+0.5))
            advanceTimeBy(500)
            assertEquals(250, r.controller.state.value.snapshot.ergWatts)
            assertEquals(TrainerTarget.Erg(250), r.trainer.writes.last())
        }

    @Test
    fun `fin de sortie - bilan, FIT si l'option est active, derniere vitesse memorisee`() =
        runTest {
            val r = rig(AppSettings(fitExport = true))
            r.ride()
            advanceTimeBy(200)
            repeat(5) {
                r.trainer.data.value = RidingData(200, 90, 36.0, null, currentTime)
                advanceTimeBy(1_000)
            }
            r.left.press(Button.SHIFT_DOWN_LEFT)
            advanceTimeBy(3_000)
            r.controller.finish()
            advanceTimeBy(200)
            val ui = r.controller.state.value
            assertEquals(RidePhase.FINISHED, ui.snapshot.phase)
            assertEquals(40.0, ui.snapshot.stats.distanceM, 1e-6)
            assertNotNull(r.env.fit)
            assertEquals(File("sortie.fit"), ui.fitFile)
            assertEquals(11, r.env.savedGear)
            assertEquals(TrainerTarget.Neutral, r.trainer.writes.last())

            r.controller.close()
            r.controller.prepare()
            advanceTimeBy(200)
            assertEquals(11, r.controller.state.value.snapshot.gear)
            assertEquals(RidePhase.PREPARING, r.controller.state.value.snapshot.phase)
        }

    @Test
    fun `fin de sortie - activite enregistree avec le bilan, identifiant publie puis oublie`() =
        runTest {
            val r = rig()
            r.ride(RideMode.Erg(180))
            advanceTimeBy(200)
            repeat(4) {
                r.trainer.data.value = RidingData(180 + it * 20, 85, 30.0, null, currentTime)
                advanceTimeBy(1_000)
            }
            r.controller.pause()
            advanceTimeBy(5_000) // la pause ne compte pas dans la durée
            r.controller.finish()
            advanceTimeBy(200)

            val saved = r.env.activities.single()
            assertEquals(1_792_000_000_000L, saved.startedAtMs)
            assertEquals(ActivityMode.ERG, saved.mode)
            assertEquals(null, saved.routeName)
            assertEquals(4_200L, saved.durationMs)
            assertEquals(210, saved.avgPowerW)
            assertEquals(240, saved.maxPowerW)
            assertEquals(85, saved.avgCadenceRpm)
            assertEquals("", saved.comment)
            assertEquals(1L, r.controller.state.value.activityId)

            r.controller.close()
            r.controller.prepare()
            advanceTimeBy(200)
            assertEquals(null, r.controller.state.value.activityId)
        }

    @Test
    fun `journal de pedalage - une ligne par seconde en sortie, rien en pause`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            repeat(4) {
                r.trainer.data.value = RidingData(150 + it, 80, 30.0, null, currentTime)
                advanceTimeBy(500)
            }
            r.controller.pause()
            advanceTimeBy(200)
            r.trainer.data.value = RidingData(0, 0, 0.0, null, currentTime)
            advanceTimeBy(1_000)

            val lines = r.env.logs.filter { it.startsWith("pédalage") }
            assertEquals(
                listOf(
                    "pédalage : 150 W, 80 rpm, 30.0 km/h, vitesse 12, consigne rapport 2.4, pente 0.0 %",
                    "pédalage : 152 W, 80 rpm, 30.0 km/h, vitesse 12, consigne rapport 2.4, pente 0.0 %",
                ),
                lines,
            )
        }

    @Test
    fun `arret d'urgence - remise a plat immediate`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            assertTrue(r.controller.emergencyNeutral())
            assertEquals(TrainerTarget.Neutral, r.trainer.writes.last())
        }
}
