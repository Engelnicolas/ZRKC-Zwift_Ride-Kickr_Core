package dev.kickzwift.protocol.device

/**
 * Verrou du Ride : à partir du firmware 1.3, Zwift verrouille les manettes
 * (spécification, « Zwift Ride »). L'app lit la version de chaque manette et alerte.
 */
object RideFirmware {
    private val LOCKED_FROM = listOf(1, 3)
    private val VERSION = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    /** Version lue sur la caractéristique 0x2A26 (« 1.2.0 ») ; null si illisible. */
    fun parse(text: String?): List<Int>? =
        text
            ?.let { VERSION.find(it) }
            ?.groupValues
            ?.drop(1)
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { it.toIntOrNull() }

    /** Vrai si la version est connue et au moins 1.3 ; une version illisible n'alerte pas. */
    fun isLocked(text: String?): Boolean = parse(text)?.let { compare(it, LOCKED_FROM) >= 0 } ?: false

    private fun compare(
        a: List<Int>,
        b: List<Int>,
    ): Int =
        (0 until maxOf(a.size, b.size))
            .map { a.getOrElse(it) { 0 }.compareTo(b.getOrElse(it) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
}
