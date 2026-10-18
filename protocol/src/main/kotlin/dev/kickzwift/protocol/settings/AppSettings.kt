package dev.kickzwift.protocol.settings

import dev.kickzwift.protocol.gear.GearTable
import dev.kickzwift.protocol.gesture.GestureTiming
import dev.kickzwift.protocol.grade.GradeLimits
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.ride.RideButtonTracker
import dev.kickzwift.protocol.session.SessionConfig

enum class ThemeChoice { DARK, LIGHT, SYSTEM }

enum class OrientationChoice { AUTO, PORTRAIT, LANDSCAPE }

/**
 * Qui fait les vitesses : l'app, en consigne de puissance calculée chaque seconde d'après la
 * cadence et le rapport ([dev.kickzwift.protocol.gear.PowerGears]), ou le trainer, qui reçoit le
 * rapport (protocole Zwift) ou un décalage de pente (repli FTMS).
 */
enum class GearMode { POWER, TRAINER }

/**
 * Réglages de l'app (spécification, écran Réglages), tous modifiables sans recompiler.
 * Valeurs par défaut : celles de la spécification, « Comportements temps réel ».
 */
data class AppSettings(
    val mapping: ButtonMapping = ButtonMapping.DEFAULT,
    val gears: List<Double> = GearTable.ZWIFT,
    val startGear: Int = DEFAULT_START_GEAR,
    val riderKg: Double = DEFAULT_RIDER_KG,
    val bikeKg: Double = DEFAULT_BIKE_KG,
    /** Intervalle minimal entre deux écritures au trainer. */
    val writeIntervalMs: Long = DEFAULT_WRITE_INTERVAL_MS,
    val timing: GestureTiming = GestureTiming(),
    val paddlePress: Int = RideButtonTracker.DEFAULT_PRESS,
    val paddleRelease: Int = RideButtonTracker.DEFAULT_RELEASE,
    val gradeLimits: GradeLimits = GradeLimits(),
    val ergMaxW: Int = DEFAULT_ERG_MAX_W,
    val theme: ThemeChoice = ThemeChoice.DARK,
    val orientation: OrientationChoice = OrientationChoice.AUTO,
    val haptics: Boolean = true,
    val imperial: Boolean = false,
    /** Pilote le KICKR en FTMS plutôt qu'en protocole Zwift (options avancées). */
    val ftmsFallback: Boolean = false,
    /** Vitesses par la puissance par défaut : le KICKR relevé n'applique pas le rapport du protocole Zwift. */
    val gearMode: GearMode = GearMode.POWER,
) {
    init {
        require(gears.isNotEmpty() && gears.zipWithNext().all { (a, b) -> a < b } && gears.first() > 0) { "table de rapports invalide" }
        require(startGear in 1..gears.size) { "vitesse de départ hors de la table" }
        require(riderKg in MASS_RANGE && bikeKg in MASS_RANGE) { "masse hors bornes" }
        require(writeIntervalMs in WRITE_INTERVAL_RANGE) { "intervalle d'écriture hors bornes" }
        require(paddleRelease in 0..paddlePress && paddlePress in 1..AXIS_MAX) { "seuils des palettes invalides" }
        require(ergMaxW in 1..ERG_CEILING_W) { "plafond ERG hors bornes" }
    }

    fun sessionConfig(): SessionConfig =
        SessionConfig(
            gears = gears,
            startGear = startGear,
            riderKg = riderKg,
            bikeKg = bikeKg,
            gradeLimits = gradeLimits,
            ergMaxW = ergMaxW,
            powerGears = gearMode == GearMode.POWER,
        )

    companion object {
        const val DEFAULT_START_GEAR = 12

        /** Taille maximale de la table des rapports, à la saisie comme à la relecture. */
        const val MAX_GEARS = 60
        const val DEFAULT_RIDER_KG = 75.0
        const val DEFAULT_BIKE_KG = 9.5
        const val DEFAULT_WRITE_INTERVAL_MS = 150L
        const val DEFAULT_ERG_MAX_W = 600
        const val ERG_CEILING_W = 2_000
        const val AXIS_MAX = 100
        val MASS_RANGE = 1.0..300.0
        val WRITE_INTERVAL_RANGE = 50L..1_000L
    }
}

/** Mémoire de l'app entre deux sorties : dernière vitesse, cinq derniers parcours. */
data class RideMemory(
    val lastGear: Int? = null,
    val recentRoutes: List<RecentRoute> = emptyList(),
) {
    /** Le parcours passe en tête de liste, qui garde [MAX_RECENT] entrées. */
    fun withRoute(route: RecentRoute): RideMemory =
        copy(recentRoutes = (listOf(route) + recentRoutes.filterNot { it.file == route.file }).take(MAX_RECENT))

    companion object {
        const val MAX_RECENT = 5
    }
}

/** Parcours importé : fichier dans le stockage interne de l'app et nom affiché. */
data class RecentRoute(
    val file: String,
    val name: String,
)
