package dev.kickzwift.protocol.ride

import dev.kickzwift.protocol.Button
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Rejoue les trames réelles exportées par `prototype/export_vectors.py`.
 *
 * Le résultat attendu vient du nom donné à chaque commande pendant la capture
 * guidée, pas d'un décodeur : le test est indépendant de l'implémentation.
 */
class RideVectorsTest {
    private data class Vector(
        val button: Button,
        val pod: String,
        val press: ByteArray,
        val release: ByteArray,
    )

    private val vectors: List<Vector> by lazy {
        val text = checkNotNull(javaClass.getResource("/vectors/ride_commands.json")).readText()
        Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray.map {
            val o = it.jsonObject
            Vector(
                button = Button.valueOf(o["button"]!!.jsonPrimitive.content),
                pod = o["pod"]!!.jsonPrimitive.content,
                press = hex(o["press"]!!.jsonPrimitive.content),
                release = hex(o["release"]!!.jsonPrimitive.content),
            )
        }
    }

    @TestFactory
    fun `chaque trame d'appui produit exactement la commande attendue`() =
        vectors.map { v ->
            DynamicTest.dynamicTest("${v.button} (${v.pod})") {
                val tracker = RideButtonTracker()
                val down = tracker.update(state(v.press))
                assertEquals(listOf(ButtonEdge(v.button, down = true)), down)
                val up = tracker.update(state(v.release))
                assertEquals(listOf(ButtonEdge(v.button, down = false)), up)
            }
        }

    @Test
    fun `les 16 commandes sont couvertes`() {
        assertEquals(Button.entries.toSet(), vectors.map { it.button }.toSet())
    }

    private fun state(frame: ByteArray): RideState {
        val decoded = RideDecoder.decode(frame)
        check(decoded is RideFrame.Buttons) { "trame non reconnue : $decoded" }
        return decoded.state
    }

    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
