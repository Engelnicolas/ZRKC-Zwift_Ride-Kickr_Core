package dev.kickzwift.app.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kxml2.io.KXmlParser
import kotlin.random.Random

class GpxParserTest {
    private val parser = GpxParser({ KXmlParser() })

    private fun parse(xml: String) = parser.parse(xml.byteInputStream())

    @Test
    fun `trace - nom, points et altitudes`() {
        val t =
            parse(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                  <metadata><name>Col de test</name></metadata>
                  <trk><name>Ignoré : le nom des métadonnées vient d'abord</name><trkseg>
                    <trkpt lat="45.0" lon="6.0"><ele>1000</ele></trkpt>
                    <trkpt lat="45.001" lon="6.0"><ele>1010.5</ele><time>2026-10-17T10:00:00Z</time></trkpt>
                    <trkpt lat="45.002" lon="6.0"></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        assertEquals("Col de test", t.name)
        assertEquals(listOf(1000.0, 1010.5, 1010.5), t.points.map { it.elevationM })
        assertEquals(45.001, t.points[1].latDeg)
    }

    @Test
    fun `route seule et prefixe d'espace de noms`() {
        val t =
            parse(
                """
                <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1">
                  <g:rte><g:name>Boucle</g:name>
                    <g:rtept lat="48.0" lon="2.0"><g:ele>50</g:ele></g:rtept>
                    <g:rtept lat="48.01" lon="2.0"><g:ele>60</g:ele></g:rtept>
                  </g:rte>
                </g:gpx>
                """.trimIndent(),
            )
        assertEquals("Boucle", t.name)
        assertEquals(2, t.points.size)
    }

    @Test
    fun `points hors bornes ecartes, altitude bornee`() {
        val t =
            parse(
                """
                <gpx><trk><trkseg>
                  <trkpt lat="95" lon="6"><ele>0</ele></trkpt>
                  <trkpt lat="45" lon="abc"><ele>0</ele></trkpt>
                  <trkpt lat="45" lon="6"><ele>99999</ele></trkpt>
                  <trkpt lat="45.1" lon="6"><ele>NaN</ele></trkpt>
                </trkseg></trk></gpx>
                """.trimIndent(),
            )
        assertEquals(2, t.points.size)
        assertEquals(listOf(9_000.0, 9_000.0), t.points.map { it.elevationM })
    }

    @Test
    fun `fichiers refuses - moins de deux points, XML invalide, trop de points`() {
        assertThrows<GpxException> { parse("<gpx><trk><trkseg><trkpt lat='1' lon='1'/></trkseg></trk></gpx>") }
        assertThrows<GpxException> { parse("<gpx><trk>") }
        assertThrows<GpxException> { parse("pas du XML") }
        val many = (0 until 20).joinToString("") { "<trkpt lat='45.$it' lon='6'/>" }
        assertThrows<GpxException> {
            GpxParser({ KXmlParser() }, maxPoints = 10).parse("<gpx><trk><trkseg>$many</trkseg></trk></gpx>".byteInputStream())
        }
    }

    @Test
    fun `entite externe - jamais resolue`() {
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <gpx><metadata><name>&secret;</name></metadata><trk><trkseg>
              <trkpt lat="1" lon="1"/><trkpt lat="1.1" lon="1"/>
            </trkseg></trk></gpx>
            """.trimIndent()
        val result = runCatching { parse(xml) }
        result.getOrNull()?.let { assertTrue(it.name == null || "root" !in it.name!!) }
        result.exceptionOrNull()?.let { assertTrue(it is GpxException, "$it") }
    }

    @Test
    fun `octets aleatoires - GpxException seulement`() {
        val random = Random(11)
        repeat(2_000) {
            val bytes = random.nextBytes(random.nextInt(0, 120))
            runCatching { parser.parse(bytes.inputStream()) }.exceptionOrNull()?.let { assertTrue(it is GpxException, "$it") }
        }
    }
}
