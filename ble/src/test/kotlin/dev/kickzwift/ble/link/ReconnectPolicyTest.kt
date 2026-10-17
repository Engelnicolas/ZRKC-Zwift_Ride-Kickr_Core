package dev.kickzwift.ble.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {
    @Test
    fun `delais 1, 2, 4, 8, 16 puis 30 s`() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30).map { it.seconds }, (1..8).map { policy.delayBefore(it) })
        assertEquals(30.seconds, policy.delayBefore(10_000))
    }

    @Test
    fun `sans limite par defaut, bornee sur demande`() {
        assertTrue(ReconnectPolicy().allows(1_000_000))
        val bounded = ReconnectPolicy(maxRetries = 2)
        assertTrue(bounded.allows(2))
        assertFalse(bounded.allows(3))
        assertFalse(ReconnectPolicy(maxRetries = 0).allows(1))
    }

    @Test
    fun `parametres invalides refuses`() {
        assertThrows<IllegalArgumentException> { ReconnectPolicy(delays = emptyList()) }
        assertThrows<IllegalArgumentException> { ReconnectPolicy(maxRetries = -1) }
        assertThrows<IllegalArgumentException> { ReconnectPolicy().delayBefore(0) }
    }
}
