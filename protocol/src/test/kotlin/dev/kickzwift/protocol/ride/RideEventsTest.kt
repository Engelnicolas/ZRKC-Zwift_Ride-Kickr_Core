package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.gesture.GestureTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideEventsTest {
    private val pod = DeviceId("E2:93:3B:6A:0F:BC")

    private fun bits(vararg b: Int) = RideFrame.Buttons(RideState(b.fold(0) { acc, i -> acc or (1 shl i) }, emptyMap()))

    private fun axis(
        index: Int,
        value: Int,
    ) = RideFrame.Buttons(RideState(0, mapOf(index to value)))

    private class Run(
        val frames: Channel<RideFrame>,
        val events: MutableList<ButtonEvent>,
    )

    private fun TestScope.start(gestures: (Button) -> Set<Gesture> = { setOf(Gesture.PRESS) }): Run {
        val frames = Channel<RideFrame>(Channel.UNLIMITED)
        val events = mutableListOf<ButtonEvent>()
        backgroundScope.launch {
            frames
                .consumeAsFlow()
                .buttonEvents(pod, GestureTiming(), gestures, now = { currentTime })
                .collect { events += it }
        }
        runCurrent()
        return Run(frames, events)
    }

    @Test
    fun `appui simple - emis apres l'anti-rebond, sans attendre le relachement`() =
        runTest {
            val run = start()
            run.frames.send(bits(8)) // Vitesse haut gauche
            runCurrent()
            assertEquals(emptyList<ButtonEvent>(), run.events)
            advanceTimeBy(31)
            assertEquals(listOf(ButtonEvent(pod, Button.SHIFT_UP_LEFT, Gesture.PRESS, 30)), run.events)
            run.frames.send(bits())
            advanceTimeBy(100)
            assertEquals(1, run.events.size)
        }

    @Test
    fun `appui long - emis a l'echeance, sans nouvelle trame`() =
        runTest {
            val run = start { setOf(Gesture.PRESS, Gesture.LONG_PRESS) }
            run.frames.send(bits(7)) // Z
            advanceTimeBy(599)
            assertEquals(emptyList<ButtonEvent>(), run.events)
            advanceTimeBy(2)
            assertEquals(listOf(ButtonEvent(pod, Button.Z, Gesture.LONG_PRESS, 600)), run.events)
        }

    @Test
    fun `double appui - et appui simple a la fin de la fenetre`() =
        runTest {
            val run = start { setOf(Gesture.PRESS, Gesture.DOUBLE_PRESS) }
            run.frames.send(bits(4)) // A
            advanceTimeBy(100)
            run.frames.send(bits())
            advanceTimeBy(100)
            run.frames.send(bits(4))
            advanceTimeBy(100)
            assertEquals(listOf(Gesture.DOUBLE_PRESS), run.events.map { it.gesture })
            run.frames.send(bits())
            advanceTimeBy(1_000)

            run.events.clear()
            run.frames.send(bits(4))
            advanceTimeBy(100)
            run.frames.send(bits())
            advanceTimeBy(299)
            assertEquals(emptyList<ButtonEvent>(), run.events)
            advanceTimeBy(40)
            assertEquals(listOf(Gesture.PRESS), run.events.map { it.gesture })
        }

    @Test
    fun `palette - appui par seuil, autres trames ignorees`() =
        runTest {
            val run = start()
            run.frames.send(RideFrame.Heartbeat)
            run.frames.send(RideFrame.Battery(80))
            run.frames.send(axis(0, -40))
            advanceTimeBy(100)
            assertEquals(emptyList<ButtonEvent>(), run.events)
            run.frames.send(axis(0, -100))
            advanceTimeBy(100)
            assertEquals(listOf(Button.PADDLE_LEFT), run.events.map { it.button })
        }

    @Test
    fun `fin du flux - fin des evenements`() =
        runTest {
            val frames = Channel<RideFrame>(Channel.UNLIMITED)
            frames.send(bits(12))
            frames.close()
            val events = frames.consumeAsFlow().buttonEvents(pod, GestureTiming(), { setOf(Gesture.PRESS) }, { currentTime }).toList()
            assertEquals(emptyList<ButtonEvent>(), events)
        }
}
