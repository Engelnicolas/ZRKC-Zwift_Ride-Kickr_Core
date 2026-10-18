package dev.kickzwift.protocol.mapping

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.DeviceId
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.gesture.GestureDetector
import dev.kickzwift.protocol.ride.RideButtonTracker
import dev.kickzwift.protocol.ride.RideDecoder
import dev.kickzwift.protocol.ride.RideFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class ButtonMappingTest {
    private val default = ButtonMapping.DEFAULT

    /** Action attendue de chaque commande, appui simple, avec la correspondance par défaut. */
    private val expected =
        mapOf(
            Button.SHIFT_UP_LEFT to Action.Shift(+1),
            Button.SHIFT_UP_RIGHT to Action.Shift(+1),
            Button.SHIFT_DOWN_LEFT to Action.Shift(-1),
            Button.SHIFT_DOWN_RIGHT to Action.Shift(-1),
            Button.UP to Action.Grade(+0.5),
            Button.DOWN to Action.Grade(-0.5),
            Button.RIGHT to Action.NextTrack,
            Button.LEFT to Action.PreviousTrack,
            Button.A to Action.PlayPause,
            Button.Y to Action.VolumeUp,
            Button.B to Action.VolumeDown,
            Button.Z to Action.PauseResume,
            Button.POWER_UP_RIGHT to Action.OpenSpotify,
        )

    @Test
    fun `correspondance par defaut - vitesses sur Vitesse haut et bas, palettes libres`() {
        for (b in Button.entries) {
            assertEquals(expected[b] ?: Action.None, default.actionFor(b, Gesture.PRESS), "$b")
            assertEquals(Action.None, default.actionFor(b, Gesture.LONG_PRESS), "$b")
            assertEquals(Action.None, default.actionFor(b, Gesture.DOUBLE_PRESS), "$b")
        }
        assertEquals(Action.None, default.actionFor(Button.PADDLE_LEFT, Gesture.PRESS))
        assertEquals(Action.None, default.actionFor(Button.PADDLE_RIGHT, Gesture.PRESS))
    }

    @Test
    fun `gestes surveilles - seulement ceux qui portent une action`() {
        assertEquals(setOf(Gesture.PRESS), default.gesturesFor(Button.SHIFT_UP_LEFT))
        assertEquals(setOf(Gesture.PRESS), default.gesturesFor(Button.Z))
        assertEquals(emptySet<Gesture>(), default.gesturesFor(Button.POWER_UP_LEFT))
        assertEquals(emptySet<Gesture>(), default.gesturesFor(Button.PADDLE_RIGHT))
    }

    @Test
    fun `modification sans toucher a la correspondance d'origine`() {
        val custom = default.with(Button.SHIFT_UP_RIGHT, Gesture.LONG_PRESS, Action.Shift(+3))
        assertEquals(setOf(Gesture.PRESS, Gesture.LONG_PRESS), custom.gesturesFor(Button.SHIFT_UP_RIGHT))
        assertEquals(setOf(Gesture.PRESS), default.gesturesFor(Button.SHIFT_UP_RIGHT), "DEFAULT inchangé")
        assertNotEquals(default, custom)

        val cleared = custom.with(Button.SHIFT_UP_RIGHT, Gesture.LONG_PRESS, Action.None)
        assertEquals(default, cleared, "« aucune » efface l'affectation")
        assertEquals(ButtonMapping.EMPTY, ButtonMapping.EMPTY.with(Button.POWER_UP_LEFT, Gesture.PRESS, Action.None))
    }

    @Test
    fun `actions hors des choix des reglages refusees`() {
        assertThrows<IllegalArgumentException> { default.with(Button.POWER_UP_LEFT, Gesture.PRESS, Action.Shift(+2)) }
        assertThrows<IllegalArgumentException> { default.with(Button.POWER_UP_LEFT, Gesture.PRESS, Action.Grade(1.0)) }
    }

    @Test
    fun `liste des affectations ordonnee`() {
        val entries = default.entries()
        assertEquals(expected.size, entries.size)
        assertEquals(entries.sortedBy { it.first.ordinal }, entries)
    }

    /**
     * Chaîne complète de :protocol sur les trames réelles du Ride : décodage,
     * fronts, gestes, actions. Appui à t = 0, relâchement à t = 200 ms.
     */
    @TestFactory
    fun `trames reelles jusqu'a l'action`(): List<DynamicTest> {
        val text = checkNotNull(javaClass.getResource("/vectors/ride_commands.json")).readText()
        return Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray.map {
            val v = it.jsonObject
            val button = Button.valueOf(v.getValue("button").jsonPrimitive.content)
            DynamicTest.dynamicTest("$button (${v.getValue("pod").jsonPrimitive.content})") {
                val actions = pipeline(default, unhex(v.str("press")), unhex(v.str("release")), releaseAt = 200)
                assertEquals(listOfNotNull(expected[button]), actions)
            }
        }
    }

    @Test
    fun `appui long configure - plusieurs vitesses d'un coup`() {
        val custom = default.with(Button.SHIFT_UP_RIGHT, Gesture.LONG_PRESS, Action.Shift(+3))
        val press = unhex("23 08 ff df ff ff 0f 1a 04 08 00 10 00 1a 04 08 01 10 00") // bit 12, pod droit
        val rest = unhex("23 08 ff ff ff ff 0f 1a 04 08 00 10 00 1a 04 08 01 10 00")
        assertEquals(listOf(Action.Shift(+3)), pipeline(custom, press, rest, releaseAt = 900))
        assertEquals(listOf(Action.Shift(+1)), pipeline(custom, press, rest, releaseAt = 200))
    }

    private fun pipeline(
        mapping: ButtonMapping,
        press: ByteArray,
        release: ByteArray,
        releaseAt: Long,
    ): List<Action> {
        val tracker = RideButtonTracker()
        val detector = GestureDetector(DeviceId("test"), gestures = mapping::gesturesFor)
        val events =
            listOf(0L to press, releaseAt to release).flatMap { (t, frame) ->
                val state = (RideDecoder.decode(frame) as RideFrame.Buttons).state
                tracker.update(state).flatMap { detector.onEdge(it, t) }
            } + detector.advanceTo(releaseAt + 2_000)
        return events.map(mapping::actionFor).filter { it != Action.None }
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun unhex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
