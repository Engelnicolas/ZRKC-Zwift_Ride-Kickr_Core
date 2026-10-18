package dev.kickzwift.protocol.mapping

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.Gesture

/**
 * Correspondance (commande, geste) -> action, modifiable dans les réglages.
 *
 * Immuable : chaque modification renvoie une nouvelle correspondance, que la session
 * applique sans redémarrer la sortie. Elle fournit aussi au GestureDetector la liste
 * des gestes à surveiller par bouton : un geste sans action n'est pas attendu, ce qui
 * évite de retarder l'appui simple pour rien.
 */
class ButtonMapping private constructor(
    private val table: Map<Button, Map<Gesture, Action>>,
) {
    fun actionFor(
        button: Button,
        gesture: Gesture,
    ): Action = table[button]?.get(gesture) ?: Action.None

    fun actionFor(event: ButtonEvent): Action = actionFor(event.button, event.gesture)

    /** Gestes qui déclenchent une action sur ce bouton ; à passer au GestureDetector. */
    fun gesturesFor(button: Button): Set<Gesture> = table[button]?.keys.orEmpty()

    /** Nouvelle correspondance où [gesture] sur [button] déclenche [action] ([Action.None] l'efface). */
    fun with(
        button: Button,
        gesture: Gesture,
        action: Action,
    ): ButtonMapping {
        validate(action)
        val row = table[button].orEmpty().toMutableMap()
        if (action == Action.None) row.remove(gesture) else row[gesture] = action
        val next = table.toMutableMap()
        if (row.isEmpty()) next.remove(button) else next[button] = row
        return ButtonMapping(next)
    }

    /** Toutes les affectations, commande par commande, dans l'ordre de l'énumération. */
    fun entries(): List<Triple<Button, Gesture, Action>> =
        table.entries
            .sortedBy { it.key.ordinal }
            .flatMap { (b, row) -> row.entries.sortedBy { it.key.ordinal }.map { (g, a) -> Triple(b, g, a) } }

    override fun equals(other: Any?): Boolean = other is ButtonMapping && other.table == table

    override fun hashCode(): Int = table.hashCode()

    companion object {
        /** Actions proposées dans les réglages (spécification, « Interface »). */
        val CHOICES: List<Action> =
            listOf(
                Action.Shift(+1),
                Action.Shift(-1),
                Action.Shift(+3),
                Action.Shift(-3),
                Action.Grade(+0.5),
                Action.Grade(-0.5),
                Action.PlayPause,
                Action.NextTrack,
                Action.PreviousTrack,
                Action.VolumeUp,
                Action.VolumeDown,
                Action.PauseResume,
                Action.None,
            )

        val EMPTY = ButtonMapping(emptyMap())

        /**
         * Correspondance par défaut : Vitesse haut et Vitesse bas des deux manettes pour
         * les vitesses (les palettes n'y sont pas affectées), flèches pour la pente et
         * les pistes, boutons d'action pour la musique et le volume, Z pour la pause de
         * la sortie. Appui simple seulement, pour que chaque appui parte dès sa confirmation.
         */
        val DEFAULT: ButtonMapping =
            listOf(
                Button.SHIFT_UP_LEFT to Action.Shift(+1),
                Button.SHIFT_UP_RIGHT to Action.Shift(+1),
                Button.SHIFT_DOWN_LEFT to Action.Shift(-1),
                Button.SHIFT_DOWN_RIGHT to Action.Shift(-1),
                Button.UP to Action.Grade(+0.5),
                Button.DOWN to Action.Grade(-0.5),
                Button.RIGHT to Action.NextTrack,
                Button.LEFT to Action.PreviousTrack,
                Button.A to Action.PlayPause,
                Button.Y to Action.VolumeUp,
                Button.B to Action.VolumeDown,
                Button.Z to Action.PauseResume,
            ).fold(EMPTY) { m, (button, action) -> m.with(button, Gesture.PRESS, action) }

        private fun validate(action: Action) {
            require(action in CHOICES) { "action hors des choix des réglages : $action" }
        }
    }
}
