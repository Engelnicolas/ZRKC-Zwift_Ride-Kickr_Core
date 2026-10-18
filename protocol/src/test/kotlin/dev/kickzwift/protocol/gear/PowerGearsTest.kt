package dev.kickzwift.protocol.gear

import dev.kickzwift.protocol.RidingData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PowerGearsTest {
    @Test
    fun `vitesse d'un vrai velo - cadence, rapport, roue de 700c`() {
        assertEquals(18.2, PowerGears.speedKmh(60.0, 2.40), 0.1)
        assertEquals(46.4, PowerGears.speedKmh(67.0, 5.49), 0.1)
        assertEquals(0.0, PowerGears.speedKmh(-5.0, 2.40), 0.0)
    }

    @Test
    fun `puissance - plat, cote, descente bornee a zero`() {
        // À plat, 84,5 kg : 50 W en vitesse 12 à 60 rpm, environ 125 W en vitesse 17.
        assertEquals(50, PowerGears.targetWatts(60.0, 2.40, 0.0, 84.5, 600), "vitesse 12")
        assertEquals(125.0, PowerGears.roadWatts(PowerGears.speedKmh(60.0, 3.49), 0.0, 84.5), 1.5)
        // +6,5 % à 5,5 km/h : la pente domine, comme relevé sur le KICKR (90 à 120 W mesurés).
        assertEquals(88.3, PowerGears.roadWatts(5.5, 6.5, 84.5), 0.5)
        assertEquals(0, PowerGears.targetWatts(60.0, 2.40, -8.0, 84.5, 600), "descente")
        assertEquals(600, PowerGears.targetWatts(110.0, 5.49, 10.0, 84.5, 600), "plafond")
        assertEquals(0, PowerGears.targetWatts(0.0, 5.49, 10.0, 84.5, 600), "pédales arrêtées")
    }

    private fun data(
        cadence: Int,
        flywheel: Int?,
    ) = RidingData(0, cadence, 0.0, null, 0, flywheel)

    @Test
    fun `cadence par le volant - sans delai, calee sur la cadence publiee`() {
        val c = FlywheelCadence()
        // Le volant accélère avant que la cadence publiée ne suive.
        assertEquals(23_000 / c.perRpm, c.update(data(30, 23_000)), 1e-9)
        assertEquals(FlywheelCadence.DEFAULT_PER_RPM, c.perRpm, 0.0, "sous 40 rpm : pas de calage")
        repeat(200) { c.update(data(80, 32_000)) }
        assertEquals(400.0, c.perRpm, 0.5)
        assertEquals(80.0, c.update(data(80, 32_000)), 0.1)
    }

    @Test
    fun `cadence - mediane des trois dernieres, la mesure isolee ecartee`() {
        val c = FlywheelCadence()
        val per = c.perRpm
        repeat(3) { c.update(data(30, (70 * per).toInt())) }
        assertEquals(70.0, c.update(data(30, (64 * per).toInt())), 0.1, "creux isolé du volant : ignoré")
        assertEquals(70.0, c.update(data(30, (71 * per).toInt())), 0.1)
        assertEquals(71.0, c.update(data(30, (72 * per).toInt())), 0.1, "vraie hausse : suivie après une mesure")
        assertEquals(0.0, c.update(data(0, (72 * per).toInt())), 0.0, "pédales arrêtées : aussitôt zéro")
        assertEquals(50.0, c.update(data(30, (50 * per).toInt())), 0.1, "reprise : la médiane repart de zéro")
    }

    @Test
    fun `cadence - publiee qui double, volant inchange, le calage ne suit pas`() {
        val c = FlywheelCadence()
        // Sortie du 18 octobre, 21:27 : 61 rpm au volant 17 900, puis 117 rpm publiés pendant 11 s.
        repeat(200) { c.update(data(61, 17_900)) }
        val calibrated = c.perRpm
        assertEquals(17_900 / 61.0, calibrated, 0.5)
        repeat(6) { assertEquals(61.0, c.update(data(117, 17_900)), 0.5, "cadence toujours tirée du volant") }
        assertEquals(calibrated, c.perRpm, 0.0, "mesures aberrantes ignorées pour le calage")
        repeat(5) { c.update(data(61, 17_900)) }
        assertEquals(calibrated, c.perRpm, 0.1, "retour à la normale")
    }

    @Test
    fun `cadence - autre plateau, le calage reprend apres une serie de rejets`() {
        val c = FlywheelCadence()
        repeat(FlywheelCadence.OUTLIER_LIMIT) { c.update(data(80, 32_000)) }
        assertEquals(FlywheelCadence.DEFAULT_PER_RPM, c.perRpm, 0.0, "400 au lieu de 305 : rejeté d'abord")
        repeat(200) { c.update(data(80, 32_000)) }
        assertEquals(400.0, c.perRpm, 0.5, "puis le calage rejoint le vrai rapport")
    }

    @Test
    fun `cadence - pedales arretees, ou sans volant en FTMS`() {
        val c = FlywheelCadence()
        assertEquals(0.0, c.update(data(0, 20_000)), 0.0)
        assertEquals(72.0, c.update(data(72, null)), 0.0)
        assertEquals(72.0, c.update(data(72, 0)), 0.0)
    }

    private val mass = 84.5

    /** [seconds] mesures à 1 Hz à partir de [fromS], cadence et rapport fixes. */
    private fun VirtualBike.ride(
        fromS: Int,
        seconds: Int,
        rpm: Double,
        ratio: Double,
        grade: Double = 0.0,
    ) = repeat(seconds) { advance((fromS + it) * 1_000L, rpm, ratio, grade) }

    @Test
    fun `velo virtuel - allure stable, la vitesse des pedales et la puissance du plat`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 31, 60.0, 2.40)
        assertEquals(18.2, bike.speedKmh, 0.1)
        assertEquals(50, bike.targetWatts(60.0, 2.40, 0.0, 600), "plus d'accélération : la puissance du plat")
    }

    @Test
    fun `velo virtuel - depart arrete et changement de vitesse, l'acceleration se paie`() {
        val bike = VirtualBike(mass)
        assertTrue(bike.targetWatts(60.0, 2.40, 0.0, 600) > 2 * PowerGears.targetWatts(60.0, 2.40, 0.0, mass, 600))
        bike.ride(0, 31, 60.0, 2.40)
        val shifted = bike.targetWatts(60.0, 3.49, 0.0, 600)
        assertTrue(shifted > PowerGears.targetWatts(60.0, 3.49, 0.0, mass, 600), "relance : plus que la puissance du plat")
        bike.ride(31, 40, 60.0, 3.49)
        assertEquals(PowerGears.targetWatts(60.0, 3.49, 0.0, mass, 600).toDouble(), bike.targetWatts(60.0, 3.49, 0.0, 600).toDouble(), 2.0)
    }

    @Test
    fun `velo virtuel - roue libre a plat, la vitesse decroit doucement`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 31, 60.0, 2.40)
        assertEquals(0, bike.targetWatts(0.0, 2.40, 0.0, 600), "pédales arrêtées : roue libre")
        bike.ride(31, 1, 0.0, 2.40)
        assertTrue(bike.speedKmh in 17.5..18.2, "une seconde : à peine plus lent")
        bike.ride(32, 30, 0.0, 2.40)
        assertTrue(bike.speedKmh in 5.0..14.0, "trente secondes : nettement plus lent, pas arrêté")
        bike.ride(62, 600, 0.0, 2.40)
        assertEquals(0.0, bike.speedKmh, 0.0, "jamais sous zéro")
    }

    @Test
    fun `velo virtuel - pedales arretees, la vitesse ralentit comme le volant`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 31, 60.0, 2.40)
        val riding = bike.speedKmh
        // Volant relevé le 18 octobre après l'arrêt des pédales : 17 019 puis environ −8 % par seconde.
        val flywheel = listOf(17_019, 15_245, 13_170, 10_874, 9_102, 6_285, 4_746, 2_413, 0)
        flywheel.forEachIndexed { i, f -> bike.advance((31 + i) * 1_000L, 0.0, 2.40, 0.0, f) }
        assertEquals(0.0, bike.speedKmh, 0.0, "volant arrêté : vélo arrêté")
        val half = VirtualBike(mass)
        half.ride(0, 31, 60.0, 2.40)
        half.advance(31_000, 0.0, 2.40, 0.0, 20_000)
        half.advance(32_000, 0.0, 2.40, 0.0, 10_000)
        assertEquals(riding / 2, half.speedKmh, 0.2, "volant à moitié : moitié de la vitesse à l'arrêt des pédales")
    }

    @Test
    fun `velo virtuel - le volant ne joue ni en descente ni en pedalant`() {
        val descent = VirtualBike(mass)
        descent.ride(0, 31, 60.0, 2.40, grade = -5.0)
        val before = descent.speedKmh
        descent.advance(31_000, 0.0, 2.40, -5.0, 20_000)
        descent.advance(32_000, 0.0, 2.40, -5.0, 5_000)
        assertTrue(descent.speedKmh >= before, "descente : le vélo roule, le volant peut s'arrêter")
        val pedaling = VirtualBike(mass)
        pedaling.ride(0, 31, 60.0, 2.40)
        pedaling.advance(31_000, 60.0, 2.40, 0.0, 20_000)
        pedaling.advance(32_000, 60.0, 2.40, 0.0, 5_000)
        assertEquals(18.2, pedaling.speedKmh, 0.1, "pédales qui tournent : le rapport et la cadence décident")
    }

    @Test
    fun `velo virtuel - en descente, roue libre jusqu'a la vitesse limite`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 600, 0.0, 2.40, grade = -5.0)
        // Air et roulement équilibrent la pente vers 44 km/h.
        assertEquals(44.0, bike.speedKmh, 1.0)
        assertEquals(0, bike.targetWatts(80.0, 2.40, -5.0, 600), "petit braquet : les pédales ne suivent plus")
    }

    @Test
    fun `velo virtuel - retrograder sans pedaler plus vite, pas de pedalage dans le vide`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 41, 60.0, 3.49)
        assertEquals(
            PowerGears.targetWatts(60.0, 2.40, 0.0, mass, 600),
            bike.targetWatts(60.0, 2.40, 0.0, 600),
            "aussitôt la puissance du nouveau rapport, pas 0 W",
        )
        bike.ride(41, 12, 60.0, 2.40)
        assertEquals(PowerGears.speedKmh(60.0, 2.40), bike.speedKmh, 0.5, "la roue rejoint les pédales en quelques secondes")
        bike.ride(53, 30, 60.0, 2.40)
        assertEquals(PowerGears.speedKmh(60.0, 2.40), bike.speedKmh, 0.1)
    }

    @Test
    fun `velo virtuel - creux de cadence a allure stable, la consigne ne tombe pas a zero`() {
        val bike = VirtualBike(mass)
        bike.ride(0, 31, 70.0, 2.82)
        assertTrue(bike.targetWatts(66.0, 2.82, 0.0, 600) > 0, "cadence estimée un peu basse : chaîne tenue")
        assertTrue(bike.targetWatts(66.0, 2.82, 2.0, 600) > 0, "de même en côte")
        assertEquals(0, bike.targetWatts(0.0, 2.82, 0.0, 600), "pédales arrêtées : roue libre")
    }

    @Test
    fun `velo virtuel - trou de plus de 3 s ignore, remise a zero`() {
        val bike = VirtualBike(mass)
        bike.advance(0, 60.0, 2.40, 0.0)
        bike.advance(10_000, 60.0, 2.40, 0.0)
        assertEquals(0.0, bike.speedKmh, 0.0)
        bike.ride(11, 31, 60.0, 2.40)
        bike.reset()
        assertEquals(0.0, bike.speedKmh, 0.0)
    }
}
