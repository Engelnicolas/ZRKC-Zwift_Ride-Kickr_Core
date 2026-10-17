package dev.kickzwift.protocol.trainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Rejoue `vectors/kickr.json`, exporté par `prototype/export_kickr_vectors.py`.
 *
 * Les vecteurs « materiel » ont été échangés avec un KICKR CORE 1.5.36 et leur
 * effet vérifié (rapport relu par la requête 520, pente ressentie) ; le codec
 * Kotlin doit produire et lire exactement les mêmes octets.
 */
class TrainerVectorsTest {
    private val root: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResource("/vectors/kickr.json")).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @TestFactory
    fun `encodage des rapports`() =
        root.getValue("gearRatio").jsonArray.map {
            val v = it.jsonObject
            val ratio = v.getValue("ratio").jsonPrimitive.double
            DynamicTest.dynamicTest("rapport $ratio (${v.str("source")})") {
                assertEquals(v.str("frame"), TrainerCodec.gearRatio(ratio).hex())
                assertEquals(v.getValue("gearRatioX10000").jsonPrimitive.int, TrainerCodec.toGearRatioX10000(ratio))
            }
        }

    @TestFactory
    fun `encodage des pentes`() =
        root
            .getValue("simulation")
            .jsonObject
            .getValue("grades")
            .jsonArray
            .map {
                val v = it.jsonObject
                val pct = v.getValue("gradePct").jsonPrimitive.double
                DynamicTest.dynamicTest("pente $pct % (${v.str("source")})") {
                    assertEquals(v.str("frame"), TrainerCodec.simulation(pct).hex())
                }
            }

    @Test
    fun `requete 520`() {
        assertEquals(root.getValue("infoRequest520").jsonObject.str("frame"), TrainerCodec.infoRequest().hex())
    }

    @Test
    fun `reponse a la poignee de main`() {
        val response = unhex(root.getValue("handshake").jsonObject.str("response"))
        assertEquals(TrainerFrame.RideOnAck(byteArrayOf(2, 2)), TrainerCodec.decode(response))
    }

    @TestFactory
    fun `reponses 520 reelles`() =
        root.getValue("infoResponses").jsonArray.map {
            val v = it.jsonObject
            val expected = v.getValue("gearRatioX10000").jsonPrimitive.int
            DynamicTest.dynamicTest("GearRatioX10000 = $expected") {
                assertEquals(TrainerFrame.GearRatio(expected), TrainerCodec.decode(unhex(v.str("frame"))))
            }
        }

    @TestFactory
    fun `trames de pedalage`() =
        root.getValue("ridingData").jsonArray.mapIndexed { i, entry ->
            val v = entry.jsonObject
            val f = v.getValue("fields").jsonObject.mapValues { (_, n) -> n.jsonPrimitive.int }
            DynamicTest.dynamicTest("trame $i (${v.str("source")})") {
                val decoded = TrainerCodec.decode(unhex(v.str("frame")))
                assertEquals(
                    TrainerFrame.Riding(
                        RidingFields(f["1"], f["2"], f["3"], f["4"], f["5"], f["6"]),
                    ),
                    decoded,
                )
            }
        }

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private fun unhex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
