package dev.kickzwift.protocol.gear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** Tables de rapports virtuels. */
object GearTable {
    /** Préréglage par défaut, repris de Zwift (Zwift Insider) : 24 rapports de 0,75 à 5,49. */
    val ZWIFT: List<Double> =
        listOf(
            0.75,
            0.87,
            0.99,
            1.11,
            1.23,
            1.38,
            1.53,
            1.68,
            1.86,
            2.04,
            2.22,
            2.40,
            2.61,
            2.82,
            3.03,
            3.24,
            3.49,
            3.74,
            3.99,
            4.24,
            4.54,
            4.84,
            5.14,
            5.49,
        )
}

/**
 * Vitesse engagée, de 1 à `table.size`, bornée aux butées.
 *
 * Seul état mutable du moteur ; l'interface et TrainerWriter le lisent par [gear].
 */
class GearEngine(
    private val table: List<Double>,
    start: Int,
) {
    init {
        require(table.isNotEmpty()) { "table de rapports vide" }
        require(table.zipWithNext().all { (a, b) -> a < b }) { "rapports non strictement croissants" }
        require(start in 1..table.size) { "vitesse de départ $start hors de 1..${table.size}" }
    }

    private val current = MutableStateFlow(start)

    val gear: StateFlow<Int> = current.asStateFlow()

    val ratio: Double get() = table[current.value - 1]

    /** Change de [delta] vitesses, borné aux butées ; renvoie la vitesse appliquée. */
    fun shift(delta: Int): Int = current.updateAndGet { (it + delta).coerceIn(1, table.size) }
}
