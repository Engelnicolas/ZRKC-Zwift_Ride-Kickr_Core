package dev.kickzwift.protocol.gesture

import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.ride.ButtonEdge

/** Délais de départ de la spécification (« Comportements temps réel »), réglables. */
data class GestureTiming(
    /** Une bascule plus courte est ignorée. */
    val debounceMs: Long = 30,
    /** Appui long émis à l'échéance, sans attendre le relâchement. */
    val longPressMs: Long = 600,
    /** Délai maximal entre le relâchement du premier appui et le début du second. */
    val doubleWindowMs: Long = 300,
) {
    init {
        require(debounceMs >= 0 && longPressMs > debounceMs && doubleWindowMs > 0) { "délais incohérents" }
    }
}

/**
 * Transforme les fronts d'une manette en gestes : appui, appui long, double appui.
 *
 * Machine à états pure : le temps est fourni de l'extérieur ([onEdge], [advanceTo]) et
 * [nextDeadline] indique quand rappeler [advanceTo]. Rien n'est émis hors de ces appels.
 *
 * Règles, pour chaque bouton selon les gestes qui y sont configurés ([gestures]) :
 * - toute bascule plus courte que l'anti-rebond est ignorée, à l'appui comme au relâchement ;
 * - ni appui long ni double appui configuré : l'appui est émis dès qu'il est confirmé ;
 * - appui long configuré : émis à l'échéance si le bouton est toujours tenu ; un appui
 *   plus court est émis au relâchement ;
 * - double appui configuré : un second appui commencé dans la fenêtre donne un double
 *   appui ; sinon l'appui simple est émis à l'expiration de la fenêtre. C'est le seul
 *   cas où l'appui simple est retardé.
 *
 * Un geste n'est émis que s'il figure dans [gestures] pour ce bouton. L'horodatage d'un
 * événement est l'instant où le geste devient certain.
 */
class GestureDetector(
    private val device: DeviceId,
    private val timing: GestureTiming = GestureTiming(),
    private val gestures: (Button) -> Set<Gesture> = { setOf(Gesture.PRESS) },
) {
    private sealed interface State {
        data object Idle : State

        /** Appui pas encore confirmé par l'anti-rebond. */
        data class PressPending(
            val downAt: Long,
            val second: Boolean,
            val firstUpAt: Long,
        ) : State

        /** Bouton tenu ; [done] : son geste est déjà émis, le relâchement ne produira rien. */
        data class Held(
            val downAt: Long,
            val done: Boolean,
        ) : State

        /** Relâchement pas encore confirmé ; un nouvel appui dans l'anti-rebond reprend [held]. */
        data class ReleasePending(
            val held: Held,
            val upAt: Long,
        ) : State

        /** Premier appui court terminé, en attente d'un éventuel second appui. */
        data class WaitingSecond(
            val firstUpAt: Long,
        ) : State
    }

    private val states = mutableMapOf<Button, State>()

    /** Dernier instant traité : les horodatages émis ne reculent jamais. */
    private var clock = Long.MIN_VALUE

    /** Traite un front observé à [atMs], après les échéances qui le précèdent. */
    fun onEdge(
        edge: ButtonEdge,
        atMs: Long,
    ): List<ButtonEvent> {
        val out = advanceTo(atMs)
        val b = edge.button
        states[b] = if (edge.down) onDown(state(b), clock) else onUp(state(b), clock)
        return out
    }

    /** Émet tout ce qui arrive à échéance jusqu'à [nowMs] inclus, dans l'ordre chronologique. */
    fun advanceTo(nowMs: Long): List<ButtonEvent> {
        val out = mutableListOf<ButtonEvent>()
        while (true) {
            val (button, due) =
                states.keys
                    .mapNotNull { b -> deadline(b, state(b))?.let { b to it } }
                    .filter { it.second <= nowMs }
                    .minByOrNull { it.second } ?: break
            states[button] = expire(button, state(button), maxOf(due, clock), out)
        }
        clock = maxOf(clock, nowMs)
        return out
    }

    /** Prochain instant où [advanceTo] produira peut-être un événement, ou null. */
    fun nextDeadline(): Long? = states.keys.mapNotNull { deadline(it, state(it)) }.minOrNull()

    private fun state(b: Button): State = states[b] ?: State.Idle

    private fun has(
        b: Button,
        g: Gesture,
    ) = g in gestures(b)

    private fun onDown(
        s: State,
        at: Long,
    ): State =
        when (s) {
            is State.ReleasePending -> s.held // rebond au relâchement : toujours tenu
            is State.WaitingSecond -> State.PressPending(at, second = true, firstUpAt = s.firstUpAt)
            is State.Idle -> State.PressPending(at, second = false, firstUpAt = 0)
            else -> s // front « appuyé » répété : rien de neuf
        }

    private fun onUp(
        s: State,
        at: Long,
    ): State =
        when (s) {
            // appui trop court : ignoré ; s'il devait être le second, on revient à l'attente
            is State.PressPending -> if (s.second) State.WaitingSecond(s.firstUpAt) else State.Idle
            is State.Held -> State.ReleasePending(s, at)
            else -> s
        }

    private fun deadline(
        b: Button,
        s: State,
    ): Long? =
        when (s) {
            is State.PressPending -> s.downAt + timing.debounceMs
            is State.Held -> if (!s.done && has(b, Gesture.LONG_PRESS)) s.downAt + timing.longPressMs else null
            is State.ReleasePending -> s.upAt + timing.debounceMs
            is State.WaitingSecond -> s.firstUpAt + timing.doubleWindowMs
            State.Idle -> null
        }

    private fun expire(
        b: Button,
        s: State,
        at: Long,
        out: MutableList<ButtonEvent>,
    ): State =
        when (s) {
            is State.PressPending -> confirmPress(b, s, at, out)
            is State.Held -> {
                emit(b, Gesture.LONG_PRESS, at, out)
                s.copy(done = true)
            }
            is State.ReleasePending ->
                when {
                    s.held.done -> State.Idle
                    has(b, Gesture.DOUBLE_PRESS) -> State.WaitingSecond(s.upAt)
                    else -> {
                        emit(b, Gesture.PRESS, at, out)
                        State.Idle
                    }
                }
            is State.WaitingSecond -> {
                emit(b, Gesture.PRESS, at, out)
                State.Idle
            }
            State.Idle -> s
        }

    private fun confirmPress(
        b: Button,
        s: State.PressPending,
        at: Long,
        out: MutableList<ButtonEvent>,
    ): State {
        if (s.second) {
            emit(b, Gesture.DOUBLE_PRESS, at, out)
            return State.Held(s.downAt, done = true)
        }
        val waits = has(b, Gesture.LONG_PRESS) || has(b, Gesture.DOUBLE_PRESS)
        if (!waits) emit(b, Gesture.PRESS, at, out)
        return State.Held(s.downAt, done = !waits)
    }

    private fun emit(
        b: Button,
        g: Gesture,
        at: Long,
        out: MutableList<ButtonEvent>,
    ) {
        if (has(b, g)) out += ButtonEvent(device, b, g, at)
    }
}
