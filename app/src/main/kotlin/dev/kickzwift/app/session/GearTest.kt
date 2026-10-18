package dev.kickzwift.app.session

import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.trainer.GearTestPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Pourquoi l'essai des vitesses n'a pas pu partir ou s'est interrompu. */
enum class GearTestFailure { NO_TRAINER, RIDING, FTMS, WRITE }

/** État de l'essai des vitesses (Réglages › Gear test). */
sealed interface GearTestState {
    data object Idle : GearTestState

    data class Running(
        /** De 1 à [count]. */
        val index: Int,
        val count: Int,
        val step: GearTestPlan.Step,
        val remainingS: Int,
        val results: List<GearTestPlan.Result>,
    ) : GearTestState

    data class Done(
        val results: List<GearTestPlan.Result>,
    ) : GearTestState

    /** Arrêté avant la fin (Debug stop, ou une sortie qui commence) ; le trainer est remis à plat. */
    data class Stopped(
        val results: List<GearTestPlan.Result>,
    ) : GearTestState

    data class Failed(
        val reason: GearTestFailure,
    ) : GearTestState
}

/**
 * Déroule [GearTestPlan] sur [trainer], hors sortie : trames brutes au début de chaque étape,
 * mesures du trainer pendant l'étape, moyennes de fin d'étape. Tout est journalisé (une ligne
 * par mesure, une par résultat). Le trainer est remis à plat en vitesse 12 à la fin, à l'arrêt
 * comme sur une écriture refusée.
 */
class GearTest(
    private val env: SessionEnvironment,
    private val trainer: Trainer,
    private val publish: (GearTestState) -> Unit,
) {
    suspend fun run() {
        val s = env.settings.value
        val mass = s.bikeKg + s.riderKg
        val steps = GearTestPlan.steps(s.bikeKg, s.riderKg)
        val results = mutableListOf<GearTestPlan.Result>()
        var outcome: GearTestState? = null
        env.log("test des vitesses : début, masses ${s.bikeKg} + ${s.riderKg} kg")
        try {
            for ((i, step) in steps.withIndex()) {
                if (!send(step.frames)) {
                    outcome = GearTestState.Failed(GearTestFailure.WRITE)
                    return
                }
                val samples = measure(step) { left -> publish(GearTestState.Running(i + 1, steps.size, step, left, results.toList())) }
                GearTestPlan.result(step, samples, mass)?.let {
                    results += it
                    env.log(describe(it))
                }
            }
            outcome = GearTestState.Done(results.toList())
            env.log("test des vitesses : terminé")
        } catch (e: CancellationException) {
            env.log("test des vitesses : arrêté")
            throw e
        } finally {
            withContext(NonCancellable) { send(GearTestPlan.RESTORE) }
            publish(outcome ?: GearTestState.Stopped(results.toList()))
        }
    }

    private suspend fun send(frames: List<ByteArray>): Boolean =
        frames.all { frame ->
            trainer.writeFrame(frame).onFailure { env.log("test des vitesses : écriture refusée, ${it.message}") }.isSuccess
        }

    /** Mesures reçues pendant l'étape ; [onTick] reçoit chaque seconde le temps restant. */
    private suspend fun measure(
        step: GearTestPlan.Step,
        onTick: (Int) -> Unit,
    ): List<GearTestPlan.Sample> =
        coroutineScope {
            val start = env.now()
            val samples = mutableListOf<GearTestPlan.Sample>()
            val collector =
                launch {
                    trainer.data.filterNotNull().filter { it.atMs >= start }.collect { d ->
                        samples += GearTestPlan.Sample(d.atMs - start, d.powerW, d.cadenceRpm, d.speedKmh)
                        val flywheel = d.flywheelRaw?.let { ", volant $it" }.orEmpty()
                        env.log("test des vitesses · ${label(step)} : ${d.powerW} W, ${d.cadenceRpm} rpm, ${kmh(d.speedKmh)} km/h$flywheel")
                    }
                }
            while (true) {
                val left = step.durationMs - (env.now() - start)
                if (left <= 0) break
                onTick(((left + MS_PER_S - 1) / MS_PER_S).toInt())
                delay(minOf(left, MS_PER_S))
            }
            collector.cancel()
            samples
        }

    private fun label(step: GearTestPlan.Step) = step.ratio?.let { "${step.code} $it" } ?: "échauffement"

    private fun describe(r: GearTestPlan.Result): String {
        val ratio = r.step.ratio ?: 0.0
        val real = kmh(GearTestPlan.realBikeKmh(r.cadenceRpm.toDouble(), ratio))
        return "test des vitesses : ${label(r.step)} (${WAYS.getValue(r.step.way!!)}) : ${r.powerW} W à ${r.cadenceRpm} rpm, " +
            "${kmh(r.speedKmh)} km/h ; vrai vélo ${r.realBikeW} W, $real km/h"
    }

    private fun kmh(v: Double) = "%.1f".format(Locale.ROOT, v)

    private companion object {
        const val MS_PER_S = 1_000L

        val WAYS =
            mapOf(
                GearTestPlan.Way.GEAR_ONLY to "rapport seul",
                GearTestPlan.Way.AFTER_GRADE to "rapport seul après une pente",
                GearTestPlan.Way.WITH_MASSES to "rapport et masses, comme l'app",
                GearTestPlan.Way.COMBINED to "pente, rapport et masses en une trame",
            )
    }
}
