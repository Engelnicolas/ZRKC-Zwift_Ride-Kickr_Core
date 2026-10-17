package dev.kickzwift.protocol.device

import dev.kickzwift.protocol.DeviceId

/** Type d'appareil, déduit de son annonce BLE au premier appairage. */
enum class DeviceKind {
    RIDE,
    KICKR,
    OTHER,
    ;

    companion object {
        /** Service 16 bits 0xFC82 (RideToWoosh), annoncé par chaque manette du Ride. */
        const val RIDE_SERVICE = "0000fc82-0000-1000-8000-00805f9b34fb"

        /** Service Zwift du KICKR (prototype/zwift.proto). */
        const val ZWIFT_SERVICE = "00000001-19ca-4651-86e5-fa29dcdd09d1"

        const val FTMS_SERVICE = "00001826-0000-1000-8000-00805f9b34fb"

        private val RIDE_NAMES = setOf("zwift ride", "zwift sf2")

        /** Le Ride d'abord ; pour le KICKR, même règle que prototype/kickr_probe.py. */
        fun classify(
            name: String?,
            serviceUuids: Collection<String>,
        ): DeviceKind {
            val n = name?.trim()?.lowercase().orEmpty()
            val uuids = serviceUuids.map { it.lowercase() }
            return when {
                RIDE_SERVICE in uuids || n in RIDE_NAMES -> RIDE
                "kickr" in n || ZWIFT_SERVICE in uuids || FTMS_SERVICE in uuids -> KICKR
                else -> OTHER
            }
        }
    }
}

/**
 * Appareil de confiance : mémorisé au premier appairage, seul autorisé à la connexion
 * (spécification, « Sécurité et permissions »).
 */
data class KnownDevice(
    val id: DeviceId,
    val kind: DeviceKind,
    val name: String,
    /**
     * Adresse aléatoire statique, comme celles du Ride et du KICKR, plutôt que publique.
     * Android en a besoin pour se connecter à une adresse sans l'avoir vue au scan.
     */
    val randomAddress: Boolean,
) {
    init {
        require(isValidAddress(id.address)) { "adresse BLE invalide" }
    }

    companion object {
        private val ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        private const val STATIC_RANDOM_MARK = 0xC0

        /** Forme canonique d'Android : six octets hexadécimaux en majuscules. */
        fun isValidAddress(address: String): Boolean = ADDRESS.matches(address)

        /**
         * Une adresse aléatoire statique a ses deux bits de poids fort à 1 (E2:…, F2:…, CB:…).
         * Sert quand Android ne fournit pas le type d'adresse (avant l'API 35).
         */
        fun looksStaticRandom(address: String): Boolean =
            isValidAddress(address) && address.substring(0, 2).toInt(HEX) and STATIC_RANDOM_MARK == STATIC_RANDOM_MARK

        private const val HEX = 16
    }
}
