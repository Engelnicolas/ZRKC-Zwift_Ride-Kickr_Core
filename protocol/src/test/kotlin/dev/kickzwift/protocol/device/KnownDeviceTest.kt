package dev.kickzwift.protocol.device

import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.wire.MalformedFrameException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class KnownDeviceTest {
    // Exemplaires de test (spécification, « Zwift Ride » et journaux/kickr_probe_*.log).
    private val rideLeft = KnownDevice(DeviceId("E2:93:3B:6A:0F:BC"), DeviceKind.RIDE, "Zwift Ride", randomAddress = true)
    private val kickr = KnownDevice(DeviceId("CB:4A:21:C8:69:33"), DeviceKind.KICKR, "KICKR CORE 85FF", randomAddress = true)

    @Test
    fun `classement - Ride par service 0xFC82 ou par nom`() {
        assertEquals(DeviceKind.RIDE, DeviceKind.classify(null, listOf("0000FC82-0000-1000-8000-00805F9B34FB")))
        assertEquals(DeviceKind.RIDE, DeviceKind.classify("Zwift Ride", emptyList()))
        assertEquals(DeviceKind.RIDE, DeviceKind.classify(" zwift sf2 ", emptyList()))
    }

    @Test
    fun `classement - KICKR par nom, service Zwift ou FTMS`() {
        assertEquals(DeviceKind.KICKR, DeviceKind.classify("KICKR CORE 85FF", emptyList()))
        assertEquals(DeviceKind.KICKR, DeviceKind.classify(null, listOf(DeviceKind.ZWIFT_SERVICE)))
        assertEquals(DeviceKind.KICKR, DeviceKind.classify("Home trainer", listOf(DeviceKind.FTMS_SERVICE)))
    }

    @Test
    fun `classement - le Ride l'emporte, le reste est autre`() {
        assertEquals(DeviceKind.RIDE, DeviceKind.classify("Zwift Ride", listOf(DeviceKind.ZWIFT_SERVICE)))
        assertEquals(DeviceKind.OTHER, DeviceKind.classify("Casque", listOf("0000180d-0000-1000-8000-00805f9b34fb")))
        assertEquals(DeviceKind.OTHER, DeviceKind.classify(null, emptyList()))
    }

    @Test
    fun `adresses - forme canonique et type aleatoire statique`() {
        assertTrue(KnownDevice.isValidAddress("E2:93:3B:6A:0F:BC"))
        assertFalse(KnownDevice.isValidAddress("e2:93:3b:6a:0f:bc"))
        assertFalse(KnownDevice.isValidAddress("E2:93:3B:6A:0F"))
        assertTrue(KnownDevice.looksStaticRandom("E2:93:3B:6A:0F:BC"))
        assertTrue(KnownDevice.looksStaticRandom("F2:DD:0E:DE:3D:BC"))
        assertTrue(KnownDevice.looksStaticRandom("CB:4A:21:C8:69:33"))
        assertFalse(KnownDevice.looksStaticRandom("00:1A:7D:DA:71:13"))
        assertFalse(KnownDevice.looksStaticRandom("pas une adresse"))
        assertThrows<IllegalArgumentException> { KnownDevice(DeviceId("XX"), DeviceKind.OTHER, "", false) }
    }

    @Test
    fun `codec - aller-retour`() {
        val devices = listOf(rideLeft, kickr, KnownDevice(DeviceId("00:1A:7D:DA:71:13"), DeviceKind.OTHER, "Éclairage", false))
        assertEquals(devices, KnownDevicesCodec.decode(KnownDevicesCodec.encode(devices)))
        assertEquals(emptyList<KnownDevice>(), KnownDevicesCodec.decode(ByteArray(0)))
    }

    @Test
    fun `codec - entree invalide ecartee, doublon remplace, champ inconnu ignore`() {
        val bytes =
            KnownDevicesCodec.encode(listOf(rideLeft)) +
                // entrée sans adresse valide : champ 1 = "nope"
                byteArrayOf(0x0A, 0x06, 0x0A, 0x04) + "nope".encodeToByteArray() +
                // champ 9 inconnu au niveau de la liste
                byteArrayOf(0x48, 0x01) +
                KnownDevicesCodec.encode(listOf(rideLeft.copy(name = "Renommé")))
        assertEquals(listOf(rideLeft.copy(name = "Renommé")), KnownDevicesCodec.decode(bytes))
    }

    @Test
    fun `codec - type inconnu lu comme autre`() {
        val encoded = KnownDevicesCodec.encode(listOf(kickr))
        // Le code du type (2, KICKR) est le seul octet 0x02 précédé de la clé 0x10.
        val i = (0 until encoded.size - 1).first { encoded[it] == 0x10.toByte() && encoded[it + 1] == 0x02.toByte() }
        encoded[i + 1] = 0x07
        assertEquals(DeviceKind.OTHER, KnownDevicesCodec.decode(encoded).single().kind)
    }

    @Test
    fun `codec - fichier tronque ou aleatoire sans exception non geree`() {
        val encoded = KnownDevicesCodec.encode(listOf(rideLeft, kickr))
        for (cut in 1 until encoded.size) {
            runCatching { KnownDevicesCodec.decode(encoded.copyOf(cut)) }
                .exceptionOrNull()
                ?.let { assertTrue(it is MalformedFrameException, "coupure $cut : $it") }
        }
        val random = Random(4)
        repeat(5_000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            runCatching { KnownDevicesCodec.decode(bytes) }
                .exceptionOrNull()
                ?.let { assertTrue(it is MalformedFrameException, "trame aléatoire : $it") }
        }
    }

    @Test
    fun `journaux - adresses tronquees aux deux derniers octets`() {
        assertEquals("…:0F:BC", rideLeft.id.redacted())
        assertEquals(
            "Connecting to …:69:33 then …:3d:bc",
            Redaction.addresses("Connecting to CB:4A:21:C8:69:33 then f2:dd:0e:de:3d:bc"),
        )
        assertEquals("aucune adresse ici", Redaction.addresses("aucune adresse ici"))
    }
}
