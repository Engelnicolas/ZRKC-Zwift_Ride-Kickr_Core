package dev.kickzwift.ble.link

import dev.kickzwift.ble.link.FakeLink.Companion.GATT_133
import dev.kickzwift.ble.link.FakeLink.Companion.LINK_LOSS
import dev.kickzwift.ble.link.FakeLink.Companion.TIMEOUT
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.LinkState.CONNECTING
import dev.kickzwift.protocol.LinkState.HANDSHAKE
import dev.kickzwift.protocol.LinkState.LOST
import dev.kickzwift.protocol.LinkState.NOT_FOUND
import dev.kickzwift.protocol.LinkState.READY
import dev.kickzwift.protocol.LinkState.RECONNECTING
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkTest {
    private val id = DeviceId("E2:93:3B:6A:0F:BC")

    /** Fabrique de tentatives : rejoue [script] puis [then] indéfiniment ; note l'heure de chaque tentative. */
    private class Attempts(
        script: List<() -> FakeLink>,
        private val then: () -> FakeLink = { FakeLink() },
    ) {
        private val pending = ArrayDeque(script)
        val links = mutableListOf<FakeLink>()
        val times = mutableListOf<Long>()

        fun next(now: Long): FakeLink =
            (pending.removeFirstOrNull() ?: then)().also {
                links += it
                times += now
            }
    }

    private fun TestScope.linkOf(
        attempts: Attempts,
        policy: ReconnectPolicy = ReconnectPolicy(),
    ) = DeviceLink(id, policy, { attempts.next(currentTime) })

    private fun TestScope.history(link: DeviceLink): List<LinkState> {
        val seen = mutableListOf<LinkState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.state.collect { seen += it } }
        return seen
    }

    @Test
    fun `nominal - connexion, poignee de main, pret`() =
        runTest {
            val attempts = Attempts(emptyList())
            val link = linkOf(attempts)
            val seen = history(link)
            val job = launch { link.run() }
            runCurrent()
            assertEquals(listOf(CONNECTING, HANDSHAKE, READY), seen)
            assertTrue(attempts.links.single().connected)
            job.cancelAndJoin()
        }

    @Test
    fun `appareil absent - NOT_FOUND et delais 1, 2, 4, 8, 16 puis 30 s`() =
        runTest {
            val attempts = Attempts(emptyList(), then = { FakeLink(connectError = TIMEOUT) })
            val link = linkOf(attempts)
            val job = launch { link.run() }
            advanceTimeBy(100.seconds)
            // Écart entre tentatives = délai prévu + 10 ms de fermeture du faux appareil.
            val gaps = attempts.times.zipWithNext { a, b -> b - a }
            assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it * 1_000L + 10 }, gaps)
            assertEquals(NOT_FOUND, link.state.value)
            assertEquals(TIMEOUT, link.lastError.value)
            job.cancelAndJoin()
        }

    @Test
    fun `perte apres READY - RECONNECTING, reprise apres 1 s, compteur remis a zero`() =
        runTest {
            val first = FakeLink()
            val second = FakeLink()
            val attempts =
                Attempts(
                    listOf(
                        { first },
                        { FakeLink(connectError = TIMEOUT) },
                        { FakeLink(connectError = TIMEOUT) },
                        { second },
                    ),
                )
            val link = linkOf(attempts)
            val job = launch { link.run() }
            runCurrent()
            assertEquals(READY, link.state.value)

            first.loss.complete(LINK_LOSS)
            runCurrent()
            assertEquals(RECONNECTING, link.state.value)
            assertEquals(LINK_LOSS, link.lastError.value)
            advanceTimeBy(10.seconds)
            assertEquals(READY, link.state.value)
            // Reprises après 1 s, 2 s puis 4 s (plus 10 ms de fermeture chacune).
            assertEquals(listOf(1_010L, 2_010L, 4_010L), attempts.times.zipWithNext { a, b -> b - a })

            // Nouvelle perte : on repart du premier délai.
            second.loss.complete(LINK_LOSS)
            runCurrent()
            val lostAt = currentTime
            advanceTimeBy(5.seconds)
            assertEquals(1_010L, attempts.times.last() - lostAt)
            assertEquals(READY, link.state.value)
            job.cancelAndJoin()
        }

    @Test
    fun `erreur 133 - connexion fermee puis une neuve ouverte`() =
        runTest {
            val attempts = Attempts(listOf({ FakeLink(connectError = GATT_133) }))
            val link = linkOf(attempts)
            val job = launch { link.run() }
            advanceTimeBy(100.milliseconds)
            assertEquals(NOT_FOUND, link.state.value)
            assertEquals(GATT_133, link.lastError.value)
            assertEquals(1, attempts.links.single().closes)
            advanceTimeBy(2.seconds)
            assertEquals(2, attempts.links.size)
            assertNotSame(attempts.links[0], attempts.links[1])
            assertEquals(READY, link.state.value)
            job.cancelAndJoin()
        }

    @Test
    fun `poignee de main ratee - fermee et reprise`() =
        runTest {
            val refused = LinkError(LinkError.Reason.PEER_DISCONNECTED)
            val attempts = Attempts(listOf({ FakeLink(handshakeError = refused) }))
            val link = linkOf(attempts)
            val seen = history(link)
            val job = launch { link.run() }
            advanceTimeBy(2.seconds)
            assertEquals(listOf(CONNECTING, HANDSHAKE, NOT_FOUND, CONNECTING, HANDSHAKE, READY), seen)
            assertEquals(1, attempts.links.first().closes)
            job.cancelAndJoin()
        }

    @Test
    fun `annulation - chaque connexion est fermee, meme en cours de tentative`() =
        runTest {
            val attempts = Attempts(emptyList(), then = { FakeLink(connectTime = 20.seconds) })
            val link = linkOf(attempts)
            val job = launch { link.run() }
            advanceTimeBy(5.seconds)
            job.cancelAndJoin()
            assertEquals(1, attempts.links.single().closes)

            val ready = Attempts(emptyList())
            val readyLink = linkOf(ready)
            val readyJob = launch { readyLink.run() }
            runCurrent()
            readyJob.cancelAndJoin()
            assertEquals(1, ready.links.single().closes)
        }

    @Test
    fun `reprises bornees - LOST puis fin de run`() =
        runTest {
            val attempts = Attempts(emptyList(), then = { FakeLink(connectError = TIMEOUT) })
            val link = linkOf(attempts, ReconnectPolicy(maxRetries = 2))
            link.run()
            assertEquals(LOST, link.state.value)
            assertEquals(3, attempts.links.size)
            assertTrue(attempts.links.all { it.closes == 1 })
        }
}
