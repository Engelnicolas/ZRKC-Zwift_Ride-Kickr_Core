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
import dev.kickzwift.protocol.settings.GearMode
import dev.kickzwift.protocol.trainer.ResendTestPlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val frames = mutableListOf<ByteArray>()

        override suspend fun writeFrame(frame: ByteArray): Result<Unit> {
            frames += frame
            return Result.success(Unit)
        }

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
        var musicStarts = 0
        override val phone =
            object : PhoneActions {
                override fun perform(action: Action) =
                    (action == Action.PlayPause || action == Action.VolumeUp).also { if (it) phoneDone += action }

                override fun startMusic() {
                    musicStarts++
                }
            }
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

    /** Vitesses par le KICKR sauf [power] : la plupart des tests suivent la consigne de simulation. */
    private fun TestScope.rig(
        settings: AppSettings = AppSettings(),
        power: Boolean = false,
    ): Rig {
        val env = Env(this, settings.copy(gearMode = if (power) GearMode.POWER else GearMode.TRAINER))
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
    fun `fin de sortie - bilan, FIT, derniere vitesse memorisee`() =
        runTest {
            val r = rig()
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
    fun `musique - reprise au depart de la sortie, pas a la reprise apres une pause`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            assertEquals(1, r.env.musicStarts)
            r.controller.pause()
            r.controller.resume()
            advanceTimeBy(200)
            assertEquals(1, r.env.musicStarts)
        }

    /** Une mesure par seconde, 60 rpm, puissance qui monte avec le rapport du moment. */
    private fun TestScope.pedal(r: Rig) =
        backgroundScope.launch {
            while (true) {
                r.trainer.data.value = RidingData(100, 60, 20.0, null, currentTime)
                delay(1_000)
            }
        }

    @Test
    fun `vitesses par la puissance - consigne ERG chaque seconde, puissance nulle en pause`() =
        runTest {
            val r = rig(power = true)
            r.ride()
            pedal(r)
            advanceTimeBy(2_500)
            val low = r.trainer.writes.last() as TrainerTarget.Erg
            r.left.press(Button.SHIFT_UP_LEFT)
            r.left.press(Button.SHIFT_UP_RIGHT)
            advanceTimeBy(1_500)
            val high = r.trainer.writes.last() as TrainerTarget.Erg
            assertTrue(high.watts > low.watts, "deux vitesses de plus, à cadence égale : plus dur")
            assertTrue(r.trainer.writes.none { it is TrainerTarget.Simulation })
            r.controller.pause()
            advanceTimeBy(500)
            assertEquals(TrainerTarget.Erg(0), r.trainer.writes.last())
        }

    /** Vitesses des mesures du FIT, en km/h : file_id (18 + 10 octets), définition des mesures (24), puis 15 octets par mesure. */
    private fun fitSpeedsKmh(
        fit: ByteArray,
        count: Int,
    ): List<Double> {
        val b = ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until count).map { i -> (b.getShort(14 + 18 + 10 + 24 + 15 * i + 9).toInt() and 0xFFFF) * 3.6 / 1_000 }
    }

    @Test
    fun `vitesses par la puissance - le FIT garde la vitesse du velo virtuel, pas celle du KICKR`() =
        runTest {
            val r = rig(power = true)
            r.ride()
            pedal(r)
            advanceTimeBy(10_500)
            r.controller.finish()
            advanceTimeBy(200)
            // 60 rpm en vitesse 12 (2,40) : 18,2 km/h au plus, rejoints en 4 s environ ; le KICKR publie 20 km/h.
            val speeds = fitSpeedsKmh(checkNotNull(r.env.fit), 10)
            assertTrue(speeds.all { it < 18.2 }, "$speeds")
            assertTrue(speeds.last() > 15.0, "$speeds")
            assertEquals(speeds.sorted(), speeds, "le vélo virtuel accélère")
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

    private val testMs = ResendTestPlan.steps(9.5, 75.0).sumOf { it.durationMs }

    @Test
    fun `essai du renvoi - six resultats, trames et renvois du plan, puis remise a plat`() =
        runTest {
            val r = rig()
            pedal(r)
            r.controller.startResendTest()
            advanceTimeBy(1_000)
            assertTrue(r.controller.resendTest.value is ResendTestState.Running)
            advanceTimeBy(testMs + 1_000)
            val done = r.controller.resendTest.value as ResendTestState.Done
            assertEquals(listOf("E", "E", "F", "F", "G", "G"), done.results.map { it.step.code })
            assertEquals(100, done.results.first().powerW)
            assertEquals(100, done.results.first().earlyW)
            // Début de chaque étape, puis un renvoi par seconde écoulée, sauf à l'instant final.
            val plan =
                ResendTestPlan.steps(9.5, 75.0).flatMap { step ->
                    val resends = (1 until step.durationMs / ResendTestPlan.KEEPALIVE_MS).flatMap { step.keepalive }
                    step.frames + resends
                } + ResendTestPlan.RESTORE
            assertEquals(plan.map { it.toList() }, r.trainer.frames.map { it.toList() })
            assertTrue(r.trainer.writes.isEmpty(), "rien par TrainerWriter hors sortie")
            assertTrue(r.env.logs.any { it.startsWith("essai du renvoi : E 2.4 (sans renvoi) : début 100 W, fin 100 W à 60 rpm") })
        }

    @Test
    fun `essai du renvoi - Debug stop remet a plat`() =
        runTest {
            val r = rig()
            pedal(r)
            r.controller.startResendTest()
            advanceTimeBy(ResendTestPlan.WARMUP_MS + ResendTestPlan.STEP_MS + 1_000)
            r.controller.stopResendTest()
            advanceTimeBy(200)
            val stopped = r.controller.resendTest.value as ResendTestState.Stopped
            assertEquals(1, stopped.results.size)
            assertEquals(
                ResendTestPlan.RESTORE.map { it.toList() },
                r.trainer.frames
                    .takeLast(2)
                    .map { it.toList() },
            )
        }

    @Test
    fun `essai du renvoi - refuse pendant une sortie, arrete par une sortie qui commence`() =
        runTest {
            val r = rig()
            r.ride()
            advanceTimeBy(200)
            r.controller.startResendTest()
            advanceTimeBy(200)
            assertEquals(ResendTestState.Failed(ResendTestFailure.RIDING), r.controller.resendTest.value)
            r.controller.finish()
            r.controller.close()
            advanceTimeBy(200)

            r.controller.startResendTest()
            advanceTimeBy(2_000)
            r.controller.prepare()
            advanceTimeBy(200)
            assertTrue(r.controller.resendTest.value is ResendTestState.Stopped)
            assertEquals(RidePhase.PREPARING, r.controller.state.value.snapshot.phase)
        }
}
