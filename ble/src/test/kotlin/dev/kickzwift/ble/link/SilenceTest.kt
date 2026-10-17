package dev.kickzwift.ble.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SilenceTest {
    @Test
    fun `battements reguliers - pas de silence, puis 3 s sans battement`() =
        runTest {
            val beats = Channel<Unit>(Channel.UNLIMITED)
            var silentAt: Long? = null
            launch {
                awaitSilence(beats.consumeAsFlow(), 3.seconds)
                silentAt = currentTime
            }
            repeat(10) {
                advanceTimeBy(990)
                beats.send(Unit)
            }
            advanceTimeBy(2_900)
            assertNull(silentAt)
            advanceTimeBy(200)
            assertEquals(9_900L + 3_000, silentAt)
        }

    @Test
    fun `flux termine - rend la main`() =
        runTest {
            awaitSilence(flowOf(Unit, Unit), 3.seconds)
            assertEquals(0L, currentTime)
        }
}
