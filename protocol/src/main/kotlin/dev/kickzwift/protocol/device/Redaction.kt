package dev.kickzwift.protocol.device

import dev.kickzwift.protocol.DeviceId

// Journaux sans donnée personnelle : adresses Bluetooth tronquées aux deux derniers octets
// (spécification, « Sécurité et permissions »).

/** « E2:93:3B:6A:0F:BC » devient « …:0F:BC ». */
fun DeviceId.redacted(): String = Redaction.addresses(address)

object Redaction {
    private val ADDRESS = Regex("""\b(?:[0-9A-Fa-f]{2}:){4}([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})\b""")

    /** Tronque toutes les adresses d'un texte, par exemple un message de la bibliothèque BLE. */
    fun addresses(text: String): String = ADDRESS.replace(text) { "…:" + it.groupValues[1] }
}
