package dev.kickzwift.ble.link

import dev.kickzwift.ble.link.FakeLink.Companion.TIMEOUT
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState.LOST
import dev.kickzwift.protocol.LinkState.READY
import dev.kickzwift.protocol.device.DeviceKind
import dev.kickzwift.protocol.device.KnownDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkSupervisorTest {
    private val ride = KnownDevice(DeviceId("E2:93:3B:6A:0F:BC"), DeviceKind.RIDE, "Zwift Ride", true)
    private val kickr = KnownDevice(DeviceId("CB:4A:21:C8:69:33"), DeviceKind.KICKR, "KICKR CORE 85FF", true)

    private val created = mutableMapOf<DeviceId, MutableList<FakeLink>>()

    private fun supervisor(
        policy: ReconnectPolicy = ReconnectPolicy(),
        make: (KnownDevice) -> FakeLink = { FakeLink() },
    ) = LinkSupervisor(policy, { d -> make(d).also { created.getOrPut(d.id) { mutableListOf() } += it } })

    @Test
    fun `une liaison par appareil de confiance`() =
        runTest {
            val trusted = MutableStateFlow(listOf(ride, kickr))
            val sup = supervisor()
            val job = launch { sup.run(trusted) }
            advanceUntilIdle()
            assertTrue(sup.running.value)
            assertEquals(mapOf(ride.id to READY, kickr.id to READY), sup.states.value)

            // La même liste réémise (nom changé) ne relance aucune liaison.
            trusted.value = listOf(ride.copy(name = "Ride"), kickr)
            advanceUntilIdle()
            assertEquals(1, created.getValue(ride.id).size)
            job.cancelAndJoin()
        }

    @Test
    fun `appareil oublie - liaison fermee et retiree, les autres intactes`() =
        runTest {
            val trusted = MutableStateFlow(listOf(ride, kickr))
            val sup = supervisor()
            val job = launch { sup.run(trusted) }
            advanceUntilIdle()
            trusted.value = listOf(kickr)
            advanceUntilIdle()
            assertEquals(1, created.getValue(ride.id).single().closes)
            assertEquals(0, created.getValue(kickr.id).single().closes)
            assertEquals(mapOf(kickr.id to READY), sup.states.value)

            // Ajouté de nouveau : nouvelle liaison.
            trusted.value = listOf(kickr, ride)
            advanceUntilIdle()
            assertEquals(2, created.getValue(ride.id).size)
            assertEquals(READY, sup.states.value[ride.id])
            job.cancelAndJoin()
        }

    @Test
    fun `arret - toutes les liaisons fermees, etats vides`() =
        runTest {
            val sup = supervisor()
            val job = launch { sup.run(MutableStateFlow(listOf(ride, kickr))) }
            advanceUntilIdle()
            job.cancelAndJoin()
            assertTrue(created.values.flatten().all { it.closes == 1 })
            assertEquals(emptyMap<DeviceId, Any>(), sup.states.value)
            assertFalse(sup.running.value)
        }

    @Test
    fun `appareil abandonne - LOST reste affiche`() =
        runTest {
            val sup = supervisor(ReconnectPolicy(maxRetries = 0)) { FakeLink(connectError = TIMEOUT) }
            val job = launch { sup.run(MutableStateFlow(listOf(ride))) }
            advanceUntilIdle()
            assertEquals(mapOf(ride.id to LOST), sup.states.value)
            job.cancelAndJoin()
        }

    @Test
    fun `un seul run a la fois - le second attend la fin du premier`() =
        runTest {
            val sup = supervisor()
            val first = launch { sup.run(MutableStateFlow(listOf(ride))) }
            advanceUntilIdle()
            val second = launch { sup.run(MutableStateFlow(listOf(kickr))) }
            advanceUntilIdle()
            assertEquals(mapOf(ride.id to READY), sup.states.value)

            first.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(1, created.getValue(ride.id).single().closes)
            assertEquals(mapOf(kickr.id to READY), sup.states.value)
            second.cancelAndJoin()
        }
}
