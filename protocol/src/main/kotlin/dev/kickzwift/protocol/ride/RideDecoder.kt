package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.wire.MalformedFrameException
import dev.kickzwift.protocol.wire.ProtoReader

/** État complet des commandes porté par une trame 0x23. */
data class RideState(
    /** Bits appuyés, logique déjà redressée : 1 = appuyé. */
    val pressedBits: Int,
    /** Valeur de chaque axe, de -100 à +100 ; un axe au repos peut être absent. */
    val axes: Map<Int, Int>,
) {
    fun axis(index: Int): Int = axes[index] ?: 0

    companion object {
        val REST = RideState(pressedBits = 0, axes = emptyMap())
    }
}

/** Trame reçue sur la caractéristique de notification `…0002` du Ride. */
sealed interface RideFrame {
    /** 0x23 : boutons et axes. */
    data class Buttons(
        val state: RideState,
    ) : RideFrame

    /** 0x15 : battement, toutes les 0,99 s. */
    data object Heartbeat : RideFrame

    /**
     * 0x19 : niveau de batterie, toutes les 5 s. Champ 2 en pourcentage : `19 10 53`
     * vaut 83, la valeur lue au même moment sur la caractéristique 0x2A19 (16 octobre 2026).
     */
    data class Battery(
        val percent: Int,
    ) : RideFrame

    /** Autre type connu ou non (0x19, 0x2a…), sans commande. */
    data class Other(
        val type: Int,
    ) : RideFrame

    /** Trame rejetée : à journaliser, sans couper la liaison. */
    data class Malformed(
        val reason: String,
    ) : RideFrame
}

/**
 * Décode les trames du Zwift Ride (service 0xFC82).
 *
 * Message 0x23 : champ 1 = bitmap 32 bits en logique inversée (repos 0xFFFFFFFF) ;
 * champ 3 répété = un axe par sous-message {1 : index, 2 : valeur sint32 zigzag}.
 */
object RideDecoder {
    const val TYPE_BUTTONS = 0x23
    const val TYPE_HEARTBEAT = 0x15
    const val TYPE_BATTERY = 0x19

    private const val FIELD_BITMAP = 1
    private const val FIELD_AXIS = 3
    private const val FIELD_AXIS_INDEX = 1
    private const val FIELD_AXIS_VALUE = 2
    private const val BITMAP_MASK = 0xFFFF_FFFFL
    private const val AXIS_LIMIT = 100
    private const val FIELD_BATTERY = 2
    private const val PERCENT_MAX = 100

    fun decode(frame: ByteArray): RideFrame {
        if (frame.isEmpty()) return RideFrame.Malformed("trame vide")
        return when (val type = frame[0].toInt() and 0xFF) {
            TYPE_BUTTONS -> decodeButtons(frame)
            TYPE_HEARTBEAT -> RideFrame.Heartbeat
            TYPE_BATTERY -> decodeBattery(frame)
            else -> RideFrame.Other(type)
        }
    }

    private fun decodeBattery(frame: ByteArray): RideFrame =
        try {
            val reader = ProtoReader(frame, pos = 1)
            var percent: Long? = null
            while (reader.hasMore) {
                val (field, wire) = reader.readTag()
                if (field == FIELD_BATTERY && wire == ProtoReader.WIRE_VARINT) percent = reader.readVarint() else reader.skip(wire)
            }
            when (val p = percent) {
                null -> RideFrame.Malformed("batterie absente")
                !in 0..PERCENT_MAX -> RideFrame.Malformed("batterie hors bornes : $p")
                else -> RideFrame.Battery(p.toInt())
            }
        } catch (e: MalformedFrameException) {
            RideFrame.Malformed(e.message ?: "trame 0x19 invalide")
        }

    private fun decodeButtons(frame: ByteArray): RideFrame =
        try {
            RideFrame.Buttons(readState(ProtoReader(frame, pos = 1)))
        } catch (e: MalformedFrameException) {
            RideFrame.Malformed(e.message ?: "trame 0x23 invalide")
        }

    private fun readState(reader: ProtoReader): RideState {
        var bitmap: Long? = null
        val axes = mutableMapOf<Int, Int>()
        while (reader.hasMore) {
            val (field, wire) = reader.readTag()
            when {
                field == FIELD_BITMAP && wire == ProtoReader.WIRE_VARINT -> bitmap = reader.readVarint()
                field == FIELD_AXIS && wire == ProtoReader.WIRE_LEN -> readAxis(reader.readDelimited(), axes)
                else -> reader.skip(wire)
            }
        }
        val raw = bitmap ?: throw MalformedFrameException("bitmap absent")
        return RideState(pressedBits = (raw.inv() and BITMAP_MASK).toInt(), axes = axes)
    }

    private fun readAxis(
        reader: ProtoReader,
        into: MutableMap<Int, Int>,
    ) {
        var index = 0
        var value = 0
        while (reader.hasMore) {
            val (field, wire) = reader.readTag()
            when {
                field == FIELD_AXIS_INDEX && wire == ProtoReader.WIRE_VARINT -> index = reader.readVarint().toInt()
                field == FIELD_AXIS_VALUE && wire == ProtoReader.WIRE_VARINT -> value = ProtoReader.zigzag(reader.readVarint())
                else -> reader.skip(wire)
            }
        }
        if (value !in -AXIS_LIMIT..AXIS_LIMIT) throw MalformedFrameException("axe $index hors bornes : $value")
        into[index] = value
    }
}
