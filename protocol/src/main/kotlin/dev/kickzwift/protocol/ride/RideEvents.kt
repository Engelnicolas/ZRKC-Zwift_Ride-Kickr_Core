package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.gesture.GestureDetector
import dev.kickzwift.protocol.gesture.GestureTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trames d'une manette → gestes : [RideButtonTracker] tire les fronts de chaque trame 0x23,
 * [GestureDetector] en fait des appuis, appuis longs et doubles appuis.
 *
 * Les échéances du détecteur (appui long, fin de la fenêtre du double appui) tombent à
 * l'heure même sans nouvelle trame. [gestures] est relu à chaque décision : une
 * correspondance modifiée s'applique aussitôt. [now] donne l'heure en millisecondes,
 * monotone.
 */
@OptIn(ExperimentalCoroutinesApi::class) // produce : stable dans les faits, marqué expérimental.
fun Flow<RideFrame>.buttonEvents(
    device: DeviceId,
    timing: GestureTiming,
    gestures: (Button) -> Set<Gesture>,
    now: () -> Long,
    pressThreshold: Int = RideButtonTracker.DEFAULT_PRESS,
    releaseThreshold: Int = RideButtonTracker.DEFAULT_RELEASE,
): Flow<ButtonEvent> =
    channelFlow {
        val upstream = this@buttonEvents
        val tracker = RideButtonTracker(pressThreshold, releaseThreshold)
        val detector = GestureDetector(device, timing, gestures)
        val states = produce { upstream.collect { if (it is RideFrame.Buttons) send(it.state) } }
        while (true) {
            val wait = detector.nextDeadline()?.let { maxOf(0L, it - now()) }
            val received = if (wait == null) states.receiveCatching() else withTimeoutOrNull(wait) { states.receiveCatching() }
            when {
                received == null -> detector.advanceTo(now()).forEach { send(it) }
                received.isClosed -> break
                else -> {
                    val at = now()
                    for (edge in tracker.update(received.getOrThrow())) {
                        detector.onEdge(edge, at).forEach { send(it) }
                    }
                }
            }
        }
    }
