package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import kotlin.math.roundToInt

/** Valeurs brutes d'une trame 0x03 ; un champ absent vaut null. */
data class RidingFields(
    val powerW: Int?,
    val cadenceRpm: Int?,
    /** Vitesse virtuelle simulée par le trainer ; unité non établie (voir [TrainerCodec.SPEED_RAW_TO_KMH]). */
    val virtualSpeedRaw: Int?,
    val heartRate: Int?,
    /** Vitesse du volant, indépendante du rapport ; unité inconnue. */
    val flywheelSpeedRaw: Int?,
    /** Valeur fixe (29960 sur le KICKR CORE de test), sens inconnu. */
    val unknown6: Int?,
) {
    /**
     * Conversion vers le type commun. La vitesse suppose des centièmes de m/s :
     * hypothèse cohérente avec les relevés, à confirmer (point ouvert du lot 1).
     */
    fun toRidingData(atMs: Long): RidingData =
        RidingData(
            powerW = powerW ?: 0,
            cadenceRpm = cadenceRpm ?: 0,
            speedKmh = (virtualSpeedRaw ?: 0) * TrainerCodec.SPEED_RAW_TO_KMH,
            heartRate = heartRate?.takeIf { it > 0 },
            atMs = atMs,
        )
}

/** Trame reçue du KICKR sur `…0002` ou `…0004`. */
sealed interface TrainerFrame {
    /** 0x03 : données de pédalage, environ une par seconde. */
    data class Riding(
        val fields: RidingFields,
    ) : TrainerFrame

    /** 0x3c : réponse à la requête 520, rapport appliqué (0 tant qu'aucun n'a été écrit). */
    data class GearRatio(
        val gearRatioX10000: Int,
    ) : TrainerFrame

    /** Écho de la poignée de main ; le KICKR CORE 1.5.36 ajoute `02 02`. */
    data class RideOnAck(
        val extra: ByteArray,
    ) : TrainerFrame {
        override fun equals(other: Any?): Boolean = other is RideOnAck && extra.contentEquals(other.extra)

        override fun hashCode(): Int = extra.contentHashCode()
    }

    /** 0x2a : message de débogage du firmware, à journaliser. */
    data class DebugLog(
        val text: String,
    ) : TrainerFrame

    /** Autre type, sans usage connu. */
    data class Other(
        val type: Int,
    ) : TrainerFrame

    /** Trame rejetée : à journaliser, sans couper la liaison. */
    data class Malformed(
        val reason: String,
    ) : TrainerFrame
}

/**
 * Codec du protocole Zwift « Hub » du KICKR CORE (service `00000001-19ca-…`).
 *
 * Relevé sur un KICKR CORE en firmware 1.5.36 le 17 octobre 2026 ; définition de
 * référence dans `prototype/zwift.proto`. Une trame = un octet d'opcode suivi
 * d'un message protobuf. Toutes les écritures partent en écriture sans réponse.
 */
object TrainerCodec {
    val RIDE_ON: ByteArray = "RideOn".toByteArray(Charsets.US_ASCII)

    const val OP_INFO = 0x00
    const val OP_RIDING_DATA = 0x03
    const val OP_COMMAND = 0x04
    const val OP_DEBUG_LOG = 0x2a
    const val OP_INFO_RESPONSE = 0x3c
    const val QUERY_GEAR_RATIO = 520

    /** Valeurs fixées comme Zwift. */
    const val DEFAULT_CWA = 5100
    const val DEFAULT_CRR = 400

    /** Centièmes de m/s -> km/h. Hypothèse à confirmer : virtual-cog annonce des centièmes de km/h. */
    const val SPEED_RAW_TO_KMH = 0.036

    private const val RATIO_SCALE = 10_000

    /** 0x04 avec le seul rapport virtuel. Vérifié sur le matériel (1,23 et 2,996). */
    fun gearRatio(ratio: Double): ByteArray = HubEncoder.command(physical = HubEncoder.physical(gearRatio = ratio))

    /** 0x04 avec la seule pente (vent 0, CdA et Crr de Zwift). Vérifié sur le matériel (0 % et +2 %). */
    fun simulation(gradePct: Double): ByteArray = HubEncoder.command(simulation = HubEncoder.simulation(gradePct))

    /** 0x04 en mode ERG, champ PowerTarget de virtual-cog. Non vérifié sur le matériel. */
    fun powerTarget(watts: Int): ByteArray {
        require(watts >= 0) { "puissance cible négative" }
        return HubEncoder.command(powerTarget = watts)
    }

    /** Requête d'information ; 520 renvoie le rapport appliqué (réponse 0x3c). */
    fun infoRequest(queryId: Int = QUERY_GEAR_RATIO): ByteArray = HubEncoder.infoRequest(queryId)

    /**
     * Trames à écrire pour atteindre une consigne, dans l'ordre d'envoi.
     *
     * Une consigne de simulation part en deux trames, pente puis rapport et masses,
     * plutôt qu'en une seule : seules les formes séparées ont été vérifiées sur le
     * matériel. Les masses n'ont pas encore été vérifiées.
     */
    fun encode(target: TrainerTarget): List<ByteArray> =
        when (target) {
            is TrainerTarget.Simulation ->
                listOf(
                    simulation(target.gradePct),
                    HubEncoder.command(physical = HubEncoder.physical(target.gearRatio, target.bikeKg, target.riderKg)),
                )
            is TrainerTarget.Erg -> listOf(powerTarget(target.watts))
            TrainerTarget.Neutral -> listOf(simulation(0.0))
        }

    /** Valeur du champ GearRatioX10000 pour un rapport donné : 2,40 -> 24000. */
    fun toGearRatioX10000(ratio: Double): Int {
        require(ratio > 0) { "rapport nul ou négatif" }
        return (ratio * RATIO_SCALE).roundToInt()
    }

    /** Ne lève jamais : une trame invalide donne [TrainerFrame.Malformed]. */
    fun decode(frame: ByteArray): TrainerFrame = HubDecoder.decode(frame)
}
