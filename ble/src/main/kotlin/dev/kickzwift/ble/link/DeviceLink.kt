package dev.kickzwift.ble.link

import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.redacted
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Machine LinkState d'un appareil : connexion, poignée de main, surveillance, reconnexion.
 *
 * - CONNECTING, HANDSHAKE puis READY à chaque tentative ;
 * - NOT_FOUND entre deux tentatives tant que l'appareil n'a jamais été joint ;
 * - RECONNECTING après une perte, jusqu'au retour à READY ;
 * - LOST quand [policy] n'autorise plus de reprise : [run] rend alors la main.
 *
 * Le compteur de reprises repart de zéro à chaque READY. Chaque tentative ouvre une connexion
 * neuve par [newLink] et la ferme quoi qu'il arrive, annulation comprise : une manette du Ride
 * dont la liaison n'a pas été fermée se croit connectée et cesse d'annoncer.
 */
class DeviceLink(
    val id: DeviceId,
    private val policy: ReconnectPolicy,
    private val newLink: () -> GattLink,
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LinkState.CONNECTING)
    val state: StateFlow<LinkState> = mutableState.asStateFlow()

    private val mutableLastError = MutableStateFlow<LinkError?>(null)

    /** Cause du dernier échec ou de la dernière perte, pour l'affichage et le journal. */
    val lastError: StateFlow<LinkError?> = mutableLastError.asStateFlow()

    /** Tourne jusqu'à annulation, ou jusqu'à LOST si [policy] borne les reprises. */
    suspend fun run() {
        var everReady = false
        var retry = 0
        while (true) {
            val error =
                attempt(reconnecting = everReady) {
                    everReady = true
                    retry = 0
                }
            retry++
            if (!policy.allows(retry)) {
                log("${id.redacted()} : $error, abandon après ${retry - 1} reprises")
                mutableState.value = LinkState.LOST
                return
            }
            val wait = policy.delayBefore(retry)
            log("${id.redacted()} : $error, reprise $retry dans $wait")
            delay(wait)
        }
    }

    private suspend fun attempt(
        reconnecting: Boolean,
        onReady: () -> Unit,
    ): LinkError {
        val link = newLink()
        try {
            mutableState.value = if (reconnecting) LinkState.RECONNECTING else LinkState.CONNECTING
            link.connect()
            mutableState.value = LinkState.HANDSHAKE
            link.handshake()
            mutableState.value = LinkState.READY
            log("${id.redacted()} : prêt")
            onReady()
            val loss = link.awaitLoss()
            // Signalée tout de suite, sans attendre la fermeture de la connexion perdue.
            mutableLastError.value = loss
            mutableState.value = LinkState.RECONNECTING
            return loss
        } catch (e: LinkException) {
            mutableLastError.value = e.error
            mutableState.value = if (reconnecting) LinkState.RECONNECTING else LinkState.NOT_FOUND
            return e.error
        } finally {
            withContext(NonCancellable) { link.close() }
        }
    }
}
