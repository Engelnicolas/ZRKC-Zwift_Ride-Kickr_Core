package dev.kickzwift.protocol.settings

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.device.RideFirmware
import dev.kickzwift.protocol.gesture.GestureTiming
import dev.kickzwift.protocol.grade.GradeLimits
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.trainer.FtmsCodec
import dev.kickzwift.protocol.trainer.FtmsGears
import dev.kickzwift.protocol.wire.MalformedFrameException
import dev.kickzwift.protocol.wire.ProtoWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class SettingsCodecTest {
    private val custom =
        AppSettings(
            mapping =
                ButtonMapping.DEFAULT
                    .with(Button.Z, Gesture.LONG_PRESS, Action.Shift(+3))
                    .with(Button.UP, Gesture.DOUBLE_PRESS, Action.Grade(-0.5))
                    .with(Button.A, Gesture.PRESS, Action.None),
            gears = listOf(1.0, 1.5, 2.25),
            startGear = 2,
            riderKg = 68.4,
            bikeKg = 8.25,
            writeIntervalMs = 200,
            timing = GestureTiming(debounceMs = 20, longPressMs = 800, doubleWindowMs = 250),
            paddlePress = 60,
            paddleRelease = 10,
            gradeLimits = GradeLimits(-8.0, 12.5),
            ergMaxW = 450,
            theme = ThemeChoice.LIGHT,
            orientation = OrientationChoice.LANDSCAPE,
            haptics = false,
            imperial = true,
            ftmsFallback = true,
        )

    @Test
    fun `aller-retour - defauts et reglages modifies`() {
        assertEquals(AppSettings(), SettingsCodec.decode(SettingsCodec.encode(AppSettings())))
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
    }

    @Test
    fun `fichier vide - valeurs par defaut, correspondance par defaut`() {
        assertEquals(AppSettings(), SettingsCodec.decode(ByteArray(0)))
    }

    @Test
    fun `correspondance videe - reste vide`() {
        val empty = AppSettings(mapping = ButtonMapping.EMPTY)
        assertEquals(ButtonMapping.EMPTY, SettingsCodec.decode(SettingsCodec.encode(empty)).mapping)
    }

    @Test
    fun `valeurs invalides - remplacees champ par champ`() {
        val w =
            ProtoWriter()
                .varint(3, 20_000)
                .varint(3, 10_000) // rapports décroissants
                .varint(4, 99) // vitesse hors table
                .varint(5, 0) // masse nulle
                .varint(7, 5) // intervalle trop court
                .varint(8, 900)
                .varint(9, 600) // anti-rebond > appui long
                .varint(11, 30)
                .varint(12, 40) // relâchement > appui
                .sint(13, 500)
                .sint(14, 100) // bornes inversées
                .varint(15, 0)
                .message(16, "VIOLET".encodeToByteArray())
                .message(17, "DIAGONALE".encodeToByteArray())
        val s = SettingsCodec.decode(w.toByteArray())
        val d = AppSettings()
        assertEquals(d.gears, s.gears)
        assertEquals(d.startGear, s.startGear)
        assertEquals(d.riderKg, s.riderKg)
        assertEquals(d.writeIntervalMs, s.writeIntervalMs)
        assertEquals(d.timing, s.timing)
        assertEquals(30, s.paddlePress)
        assertEquals(20, s.paddleRelease)
        assertEquals(d.gradeLimits, s.gradeLimits)
        assertEquals(d.ergMaxW, s.ergMaxW)
        assertEquals(d.theme, s.theme)
        assertEquals(d.orientation, s.orientation)
    }

    @Test
    fun `liaisons invalides - ecartees`() {
        fun binding(
            button: String,
            gesture: String,
            action: String,
            param: Int,
        ) = ProtoWriter()
            .message(1, button.encodeToByteArray())
            .message(2, gesture.encodeToByteArray())
            .message(3, action.encodeToByteArray())
            .sint(4, param)
            .toByteArray()
        val w =
            ProtoWriter()
                .varint(2, 1)
                .message(1, binding("Z", "PRESS", "pause_resume", 0))
                .message(1, binding("Q", "PRESS", "shift", 1)) // commande inconnue
                .message(1, binding("A", "TRIPLE", "shift", 1)) // geste inconnu
                .message(1, binding("A", "PRESS", "teleport", 0)) // action inconnue
                .message(1, binding("B", "PRESS", "shift", 7)) // hors des choix
        val m = SettingsCodec.decode(w.toByteArray()).mapping
        assertEquals(listOf(Triple(Button.Z, Gesture.PRESS, Action.PauseResume as Action)), m.entries())
    }

    @Test
    fun `toutes les actions des reglages - aller-retour`() {
        for (a in ButtonMapping.CHOICES) {
            val (kind, param) = SettingsCodec.ActionCodes.encode(a)
            assertEquals(a, SettingsCodec.ActionCodes.decode(kind, param))
        }
    }

    @Test
    fun `memoire - aller-retour, cinq parcours, noms de fichier surs`() {
        var m = RideMemory(lastGear = 14)
        repeat(7) { m = m.withRoute(RecentRoute("r$it.gpx", "Parcours $it")) }
        m = m.withRoute(RecentRoute("r3.gpx", "Parcours 3"))
        assertEquals(listOf("r3.gpx", "r6.gpx", "r5.gpx", "r4.gpx", "r2.gpx"), m.recentRoutes.map { it.file })
        assertEquals(m, RideMemoryCodec.decode(RideMemoryCodec.encode(m)))
        assertEquals(RideMemory(), RideMemoryCodec.decode(ByteArray(0)))

        val hostile =
            RideMemoryCodec.encode(
                RideMemory(recentRoutes = listOf(RecentRoute("../../etc/passwd", "x"), RecentRoute("ok.gpx", "ok"))),
            )
        assertEquals(listOf("ok.gpx"), RideMemoryCodec.decode(hostile).recentRoutes.map { it.file })
        assertFalse(RouteFiles.isSafeName(".."))
        assertFalse(RouteFiles.isSafeName("a/b.gpx"))
        assertTrue(RouteFiles.isSafeName("col-du-galibier_2.gpx"))
    }

    @Test
    fun `fichiers tronques ou aleatoires - sans exception non geree`() {
        val bytes = SettingsCodec.encode(custom)
        for (cut in 1 until bytes.size) {
            runCatching {
                SettingsCodec.decode(
                    bytes.copyOf(cut),
                )
            }.exceptionOrNull()?.let { assertTrue(it is MalformedFrameException, "$it") }
        }
        val random = Random(7)
        repeat(5_000) {
            val b = random.nextBytes(random.nextInt(0, 80))
            runCatching { SettingsCodec.decode(b) }.exceptionOrNull()?.let { assertTrue(it is MalformedFrameException, "$it") }
            runCatching { RideMemoryCodec.decode(b) }.exceptionOrNull()?.let { assertTrue(it is MalformedFrameException, "$it") }
        }
    }

    @Test
    fun `reglages invalides refuses a la construction`() {
        assertThrows<IllegalArgumentException> { AppSettings(gears = emptyList()) }
        assertThrows<IllegalArgumentException> { AppSettings(startGear = 25) }
        assertThrows<IllegalArgumentException> { AppSettings(riderKg = 0.0) }
        assertThrows<IllegalArgumentException> { AppSettings(writeIntervalMs = 10) }
        assertThrows<IllegalArgumentException> { AppSettings(paddlePress = 10, paddleRelease = 20) }
        assertThrows<IllegalArgumentException> { AppSettings(ergMaxW = 0) }
        assertEquals(450, custom.sessionConfig().ergMaxW)
    }

    @Test
    fun `firmware du Ride - verrou a partir de 1_3`() {
        assertFalse(RideFirmware.isLocked("1.2.0"))
        assertFalse(RideFirmware.isLocked("1.2"))
        assertFalse(RideFirmware.isLocked("0.9.9"))
        assertTrue(RideFirmware.isLocked("1.3"))
        assertTrue(RideFirmware.isLocked("1.3.0"))
        assertTrue(RideFirmware.isLocked("v1.10.2"))
        assertTrue(RideFirmware.isLocked("2.0.0"))
        assertFalse(RideFirmware.isLocked(null))
        assertFalse(RideFirmware.isLocked("inconnu"))
        assertEquals(listOf(1, 2, 0), RideFirmware.parse("1.2.0"))
        assertNull(RideFirmware.parse("x"))
    }

    @Test
    fun `repli FTMS - vitesses simulees par decalage de pente`() {
        val gears = FtmsGears()
        assertEquals(0.0, gears.offsetPct(2.40), 1e-9)
        assertEquals(-3.4375, gears.offsetPct(0.75), 1e-9)
        assertEquals(6.4375, gears.offsetPct(5.49), 1e-9)
        assertArrayEquals(FtmsCodec.simulation(3.0), gears.encode(TrainerTarget.Simulation(3.0, 2.40, 75.0, 9.5)))
        assertArrayEquals(FtmsCodec.simulation(16.0), gears.encode(TrainerTarget.Simulation(15.0, 5.49, 75.0, 9.5)))
        assertArrayEquals(FtmsCodec.targetPower(200), gears.encode(TrainerTarget.Erg(200)))
        assertArrayEquals(FtmsCodec.simulation(0.0), gears.encode(TrainerTarget.Neutral))
        assertThrows<IllegalArgumentException> { FtmsGears(referenceRatio = 0.0) }
    }
}
