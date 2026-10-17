package dev.kickzwift.ble.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Faux appareil : chaque instance est une tentative, au résultat écrit d'avance. */
class FakeLink(
    private val connectError: LinkError? = null,
    private val handshakeError: LinkError? = null,
    private val connectTime: Duration = Duration.ZERO,
) : GattLink {
    val loss = CompletableDeferred<LinkError>()
    var connected = false
        private set
    var closes = 0
        private set

    override suspend fun connect() {
        delay(connectTime)
        connectError?.let { throw LinkException(it) }
        connected = true
    }

    override suspend fun handshake() {
        handshakeError?.let { throw LinkException(it) }
    }

    override suspend fun awaitLoss(): LinkError = loss.await()

    override suspend fun close() {
        // Une fermeture qui suspend, comme la vraie : elle doit aboutir même sous annulation.
        delay(10.milliseconds)
        closes++
    }

    companion object {
        val TIMEOUT = LinkError(LinkError.Reason.TIMEOUT)
        val GATT_133 = LinkError(LinkError.Reason.GATT_ERROR, LinkError.GATT_ERROR_133)
        val LINK_LOSS = LinkError(LinkError.Reason.LINK_LOSS)
    }
}
