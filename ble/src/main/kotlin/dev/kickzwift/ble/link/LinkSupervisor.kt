package dev.kickzwift.ble.link

import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.KnownDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Une [DeviceLink] par appareil de confiance, et seulement pour eux : un appareil ajouté est
 * joint, un appareil oublié est déconnecté. Chaque liaison tourne dans sa propre coroutine,
 * annulée (donc fermée) à la déconnexion.
 */
class LinkSupervisor(
    private val policy: ReconnectPolicy,
    private val newLink: (KnownDevice) -> GattLink,
    private val log: (String) -> Unit = {},
) {
    private val mutableStates = MutableStateFlow<Map<DeviceId, LinkState>>(emptyMap())

    /** État de chaque appareil suivi ; vide quand [run] ne tourne pas. */
    val states: StateFlow<Map<DeviceId, LinkState>> = mutableStates.asStateFlow()

    private val perDevice = ConcurrentHashMap<DeviceId, MutableStateFlow<LinkState>>()

    /**
     * État d'un appareil, stable d'un [run] à l'autre : NOT_FOUND tant qu'il n'est pas suivi.
     * C'est le `link` des interfaces Controller et Trainer.
     */
    fun stateOf(id: DeviceId): StateFlow<LinkState> = flowOf(id).asStateFlow()

    private fun flowOf(id: DeviceId) = perDevice.getOrPut(id) { MutableStateFlow(LinkState.NOT_FOUND) }

    private fun publish(
        id: DeviceId,
        state: LinkState?,
    ) {
        mutableStates.update { if (state == null) it - id else it + (id to state) }
        flowOf(id).value = state ?: LinkState.NOT_FOUND
    }

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    // Un service recréé aussitôt détruit attend que l'ancien ait fermé ses liaisons.
    private val single = Mutex()

    /**
     * Suit la liste des appareils de confiance jusqu'à annulation, qui ferme toutes les liaisons.
     * Un seul appel tourne à la fois ; un second attend la fin du premier.
     */
    suspend fun run(devices: Flow<List<KnownDevice>>) =
        single.withLock {
            mutableRunning.value = true
            try {
                follow(devices)
            } finally {
                mutableStates.value.keys.forEach { flowOf(it).value = LinkState.NOT_FOUND }
                mutableStates.value = emptyMap()
                mutableRunning.value = false
            }
        }

    private suspend fun follow(devices: Flow<List<KnownDevice>>) {
        coroutineScope {
            val followed = mutableMapOf<DeviceId, Job>()
            devices.collect { list ->
                val wanted = list.associateBy { it.id }
                for (id in followed.keys - wanted.keys) {
                    followed.remove(id)?.cancelAndJoin()
                    publish(id, null)
                }
                for ((id, device) in wanted) {
                    if (id !in followed) followed[id] = launch { followOne(device) }
                }
            }
        }
    }

    private suspend fun followOne(device: KnownDevice) {
        val link = DeviceLink(device.id, policy, { newLink(device) }, log)
        coroutineScope {
            val mirror = launch { link.state.collect { s -> publish(device.id, s) } }
            try {
                link.run()
            } finally {
                mirror.cancel()
            }
        }
        // run() ne rend la main qu'en LOST : l'état reste affiché jusqu'à l'oubli de l'appareil.
        publish(device.id, link.state.value)
    }
}
