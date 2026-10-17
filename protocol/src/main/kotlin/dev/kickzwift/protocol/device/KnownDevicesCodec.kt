package dev.kickzwift.protocol.device

import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.wire.MalformedFrameException
import dev.kickzwift.protocol.wire.ProtoReader
import dev.kickzwift.protocol.wire.ProtoWriter

/**
 * Appareils de confiance au format protobuf, pour le DataStore de l'app :
 *
 *     message KnownDevices { repeated Device device = 1; }
 *     message Device { string address = 1; uint32 kind = 2; string name = 3; bool random_address = 4; }
 *
 * Une entrée dont l'adresse est invalide est écartée ; un fichier corrompu lève
 * [MalformedFrameException], que le DataStore traite en repartant d'une liste vide.
 */
object KnownDevicesCodec {
    private const val LIST_DEVICE = 1
    private const val DEV_ADDRESS = 1
    private const val DEV_KIND = 2
    private const val DEV_NAME = 3
    private const val DEV_RANDOM = 4

    // Codes figés : l'ordre de l'enum peut changer, pas le fichier déjà écrit.
    private val KIND_CODES = mapOf(DeviceKind.OTHER to 0L, DeviceKind.RIDE to 1L, DeviceKind.KICKR to 2L)

    fun encode(devices: List<KnownDevice>): ByteArray {
        val list = ProtoWriter()
        for (d in devices) {
            val entry =
                ProtoWriter()
                    .message(DEV_ADDRESS, d.id.address.encodeToByteArray())
                    .varint(DEV_KIND, KIND_CODES.getValue(d.kind))
                    .message(DEV_NAME, d.name.encodeToByteArray())
                    .varint(DEV_RANDOM, if (d.randomAddress) 1 else 0)
            list.message(LIST_DEVICE, entry.toByteArray())
        }
        return list.toByteArray()
    }

    fun decode(bytes: ByteArray): List<KnownDevice> {
        val r = ProtoReader(bytes)
        val devices = mutableListOf<KnownDevice>()
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            if (field == LIST_DEVICE && wire == ProtoReader.WIRE_LEN) {
                // Une adresse en double : la dernière entrée l'emporte.
                decodeDevice(r.readDelimited())?.let { d ->
                    devices.removeAll { it.id == d.id }
                    devices += d
                }
            } else {
                r.skip(wire)
            }
        }
        return devices
    }

    private fun decodeDevice(r: ProtoReader): KnownDevice? {
        var address = ""
        var kind = DeviceKind.OTHER
        var name = ""
        var random = false
        while (r.hasMore) {
            val (field, wire) = r.readTag()
            when {
                field == DEV_ADDRESS && wire == ProtoReader.WIRE_LEN -> address = r.readDelimited().remainingBytes().decodeToString()
                field == DEV_KIND && wire == ProtoReader.WIRE_VARINT -> kind = kindOf(r.readVarint())
                field == DEV_NAME && wire == ProtoReader.WIRE_LEN -> name = r.readDelimited().remainingBytes().decodeToString()
                field == DEV_RANDOM && wire == ProtoReader.WIRE_VARINT -> random = r.readVarint() != 0L
                else -> r.skip(wire)
            }
        }
        return if (KnownDevice.isValidAddress(address)) KnownDevice(DeviceId(address), kind, name, random) else null
    }

    private fun kindOf(code: Long): DeviceKind = KIND_CODES.entries.firstOrNull { it.value == code }?.key ?: DeviceKind.OTHER
}
