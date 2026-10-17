package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.wire.MalformedFrameException
import dev.kickzwift.protocol.wire.ProtoReader
import dev.kickzwift.protocol.wire.ProtoWriter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Numéros de champ de prototype/zwift.proto, derrière la façade TrainerCodec.

internal object HubEncoder {
    private const val CMD_POWER_TARGET = 3
    private const val CMD_SIMULATION = 4
    private const val CMD_PHYSICAL = 5
    private const val SIM_WIND = 1
    private const val SIM_INCLINE = 2
    private const val SIM_CWA = 3
    private const val SIM_CRR = 4
    private const val PHY_GEAR_RATIO = 2
    private const val PHY_BIKE_WEIGHT = 4
    private const val PHY_RIDER_WEIGHT = 5
    private const val INFO_QUERY_ID = 1
    private const val HUNDRED = 100

    fun command(
        powerTarget: Int? = null,
        simulation: ByteArray? = null,
        physical: ByteArray? = null,
    ): ByteArray {
        val w = ProtoWriter()
        powerTarget?.let { w.varint(CMD_POWER_TARGET, it.toLong()) }
        simulation?.let { w.message(CMD_SIMULATION, it) }
        physical?.let { w.message(CMD_PHYSICAL, it) }
        return frame(TrainerCodec.OP_COMMAND, w)
    }

    fun simulation(gradePct: Double): ByteArray =
        ProtoWriter()
            .sint(SIM_WIND, 0)
            .sint(SIM_INCLINE, (gradePct * HUNDRED).roundToInt())
            .varint(SIM_CWA, TrainerCodec.DEFAULT_CWA.toLong())
            .varint(SIM_CRR, TrainerCodec.DEFAULT_CRR.toLong())
            .toByteArray()

    fun physical(
        gearRatio: Double? = null,
        bikeKg: Double? = null,
        riderKg: Double? = null,
    ): ByteArray {
        val w = ProtoWriter()
        gearRatio?.let { w.varint(PHY_GEAR_RATIO, TrainerCodec.toGearRatioX10000(it).toLong()) }
        bikeKg?.let { w.varint(PHY_BIKE_WEIGHT, (it * HUNDRED).roundToLong()) }
        riderKg?.let { w.varint(PHY_RIDER_WEIGHT, (it * HUNDRED).roundToLong()) }
        return w.toByteArray()
    }

    fun infoRequest(queryId: Int): ByteArray = frame(TrainerCodec.OP_INFO, ProtoWriter().varint(INFO_QUERY_ID, queryId.toLong()))

    private fun frame(
        opcode: Int,
        body: ProtoWriter,
    ): ByteArray = byteArrayOf(opcode.toByte()) + body.toByteArray()
}

internal object HubDecoder {
    private const val BYTE_MASK = 0xFF
    private const val RD_POWER = 1
    private const val RD_CADENCE = 2
    private const val RD_SPEED = 3
    private const val RD_HEART_RATE = 4
    private const val RD_FLYWHEEL = 5
    private const val RD_UNKNOWN6 = 6
    private const val INFO_QUERY_ID = 1
    private const val INFO_BODY = 2
    private const val INFO_GEAR = 1
    private const val INFO_GEAR_RATIO = 8
    private const val DEBUG_BODY = 2
    private const val DEBUG_TEXT = 4

    fun decode(frame: ByteArray): TrainerFrame =
        when {
            frame.isEmpty() -> TrainerFrame.Malformed("trame vide")
            isRideOn(frame) -> TrainerFrame.RideOnAck(frame.copyOfRange(TrainerCodec.RIDE_ON.size, frame.size))
            else ->
                try {
                    decodeTyped(frame[0].toInt() and BYTE_MASK, ProtoReader(frame, pos = 1))
                } catch (e: MalformedFrameException) {
                    TrainerFrame.Malformed(e.message ?: "trame invalide")
                }
        }

    private fun isRideOn(frame: ByteArray): Boolean =
        frame.size >= TrainerCodec.RIDE_ON.size &&
            frame.copyOfRange(0, TrainerCodec.RIDE_ON.size).contentEquals(TrainerCodec.RIDE_ON)

    private fun decodeTyped(
        type: Int,
        reader: ProtoReader,
    ): TrainerFrame =
        when (type) {
            TrainerCodec.OP_RIDING_DATA -> TrainerFrame.Riding(riding(fields(reader).varints))
            TrainerCodec.OP_INFO_RESPONSE -> infoResponse(fields(reader))
            TrainerCodec.OP_DEBUG_LOG -> TrainerFrame.DebugLog(debugText(fields(reader)))
            else -> TrainerFrame.Other(type)
        }

    private fun riding(f: Map<Int, Int>) =
        RidingFields(
            powerW = f[RD_POWER],
            cadenceRpm = f[RD_CADENCE],
            virtualSpeedRaw = f[RD_SPEED],
            heartRate = f[RD_HEART_RATE],
            flywheelSpeedRaw = f[RD_FLYWHEEL],
            unknown6 = f[RD_UNKNOWN6],
        )

    private fun infoResponse(top: Fields): TrainerFrame {
        if (top.varints[INFO_QUERY_ID] != TrainerCodec.QUERY_GEAR_RATIO) return TrainerFrame.Other(TrainerCodec.OP_INFO_RESPONSE)
        val gear =
            top.messages[INFO_BODY]?.let { fields(ProtoReader(it)).messages[INFO_GEAR] }
                ?: throw MalformedFrameException("réponse 520 sans corps")
        return TrainerFrame.GearRatio(fields(ProtoReader(gear)).varints[INFO_GEAR_RATIO] ?: 0)
    }

    private fun debugText(top: Fields): String =
        top.messages[DEBUG_BODY]
            ?.let { fields(ProtoReader(it)).messages[DEBUG_TEXT] }
            ?.toString(Charsets.UTF_8)
            ?.trimEnd('\u0000')
            .orEmpty()

    /** Champs d'un message : entiers des varints, octets des champs délimités (premier de chaque numéro). */
    private class Fields(
        val varints: Map<Int, Int>,
        val messages: Map<Int, ByteArray>,
    )

    private fun fields(reader: ProtoReader): Fields {
        val varints = mutableMapOf<Int, Int>()
        val messages = mutableMapOf<Int, ByteArray>()
        while (reader.hasMore) {
            val (field, wire) = reader.readTag()
            when (wire) {
                ProtoReader.WIRE_VARINT -> varints[field] = reader.readVarint().toInt()
                ProtoReader.WIRE_LEN -> reader.readDelimited().remainingBytes().let { messages.putIfAbsent(field, it) }
                else -> reader.skip(wire)
            }
        }
        return Fields(varints, messages)
    }
}
