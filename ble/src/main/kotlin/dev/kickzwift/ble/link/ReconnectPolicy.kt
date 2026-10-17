package dev.kickzwift.ble.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Délais de reconnexion (spécification, « Comportements temps réel ») : 1, 2, 4, 8, 16 puis 30 s. */
class ReconnectPolicy(
    private val delays: List<Duration> = DEFAULT_DELAYS,
    /** Reprises permises après un échec ; null = sans limite, la règle pendant une sortie. */
    val maxRetries: Int? = null,
) {
    init {
        require(delays.isNotEmpty()) { "au moins un délai" }
        require(maxRetries == null || maxRetries >= 0) { "maxRetries négatif" }
    }

    /** Délai avant la reprise numéro [retry], 1 pour la première ; le dernier délai se répète. */
    fun delayBefore(retry: Int): Duration {
        require(retry >= 1) { "reprises numérotées à partir de 1" }
        return delays[minOf(retry, delays.size) - 1]
    }

    fun allows(retry: Int): Boolean = maxRetries == null || retry <= maxRetries

    companion object {
        val DEFAULT_DELAYS: List<Duration> = listOf(1, 2, 4, 8, 16, 30).map { it.seconds }
    }
}
