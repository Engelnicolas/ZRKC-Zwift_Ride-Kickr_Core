package dev.kickzwift.ble.link

/**
 * Une connexion GATT à un appareil, à usage unique : [DeviceLink] en crée une neuve à chaque
 * tentative et la ferme ensuite. C'est la règle de l'erreur 133 (fermer, puis ouvrir une nouvelle
 * connexion), appliquée à tous les échecs.
 */
interface GattLink {
    /** Connexion et découverte des services. Lève [LinkException]. */
    suspend fun connect()

    /** Poignée de main propre à l'appareil (« RideOn »), une fois connecté. Lève [LinkException]. */
    suspend fun handshake()

    /** Suspend tant que la liaison tient ; rend la cause de sa perte. */
    suspend fun awaitLoss(): LinkError

    /** Ferme explicitement la liaison GATT. Appelée une fois, même après un échec. */
    suspend fun close()
}

data class LinkError(
    val reason: Reason,
    /** Code GATT d'Android quand il y en a un, 133 par exemple. */
    val status: Int? = null,
) {
    enum class Reason {
        /** Appareil absent ou hors de portée : la tentative a expiré. */
        TIMEOUT,

        /** Erreur GATT d'Android, dont la fréquente 133. */
        GATT_ERROR,

        /** Liaison perdue : portée, appareil éteint. */
        LINK_LOSS,

        /** L'appareil a fermé la liaison. */
        PEER_DISCONNECTED,

        /** Services attendus absents : ce n'est pas l'appareil prévu. */
        NOT_SUPPORTED,

        BLUETOOTH_OFF,
        NO_PERMISSION,

        /** Plus aucune trame alors que la liaison GATT tient encore (lot 5 : 3 s sans 0x15). */
        SILENT,
        UNKNOWN,
    }

    override fun toString(): String = if (status == null) reason.name else "${reason.name} ($status)"

    companion object {
        const val GATT_ERROR_133 = 133
    }
}

class LinkException(
    val error: LinkError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)
