package dev.kickzwift.app.session

import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.trainer.ResendTestPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pourquoi l'essai du renvoi n'a pas pu partir ou s'est interrompu. */
enum class ResendTestFailure { NO_TRAINER, RIDING, FTMS, WRITE }

/** État de l'essai du renvoi (Réglages › Resend test). */
sealed interface ResendTestState {
    data object Idle : ResendTestState

    data class Running(
        /** De 1 à [count]. */
        val index: Int,
        val count: Int,
        val step: ResendTestPlan.Step,
        val remainingS: Int,
        val results: List<ResendTestPlan.Result>,
    ) : ResendTestState

    data class Done(
        val results: List<ResendTestPlan.Result>,
    ) : ResendTestState

    /** Arrêté avant la fin (Debug stop, ou une sortie qui commence) ; le trainer est remis à plat. */
    data class Stopped(
        val results: List<ResendTestPlan.Result>,
    ) : ResendTestState

    data class Failed(
        val reason: ResendTestFailure,
    ) : ResendTestState
}

/**
 * Déroule [ResendTestPlan] sur [trainer], hors sortie : trames brutes au début de chaque étape,
 * renvois chaque seconde, mesures du trainer pendant l'étape, moyennes de début et de fin
 * d'étape. Tout est journalisé (une ligne par mesure, une par résultat). Le trainer est remis à
 * plat en vitesse 12 à la fin, à l'arrêt comme sur une écriture refusée.
 */
class ResendTest(
    private val env: SessionEnvironment,
    private val trainer: Trainer,
    private val publish: (ResendTestState) -> Unit,
) {
    private class WriteRefused : Exception()

    suspend fun run() {
        val s = env.settings.value
        val mass = s.bikeKg + s.riderKg
        val steps = ResendTestPlan.steps(s.bikeKg, s.riderKg)
        val results = mutableListOf<ResendTestPlan.Result>()
        var outcome: ResendTestState? = null
        env.log("essai du renvoi : début, masses ${s.bikeKg} + ${s.riderKg} kg")
        try {
            for ((i, step) in steps.withIndex()) {
                send(step.frames)
                val samples = measure(step) { left -> publish(ResendTestState.Running(i + 1, steps.size, step, left, results.toList())) }
                ResendTestPlan.result(step, samples, mass)?.let {
                    results += it
                    env.log(describe(it))
                }
            }
            outcome = ResendTestState.Done(results.toList())
            env.log("essai du renvoi : terminé")
        } catch (_: WriteRefused) {
            outcome = ResendTestState.Failed(ResendTestFailure.WRITE)
        } catch (e: CancellationException) {
            env.log("essai du renvoi : arrêté")
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { send(ResendTestPlan.RESTORE) } }
            publish(outcome ?: ResendTestState.Stopped(results.toList()))
        }
    }

    private suspend fun send(frames: List<ByteArray>) {
        for (frame in frames) {
            trainer.writeFrame(frame).onFailure {
                env.log("essai du renvoi : écriture refusée, ${it.message}")
                throw WriteRefused()
            }
        }
    }

    /**
     * Mesures reçues pendant l'étape, renvois compris ; [onTick] reçoit chaque seconde le temps
     * restant.
     */
    private suspend fun measure(
        step: ResendTestPlan.Step,
        onTick: (Int) -> Unit,
    ): List<ResendTestPlan.Sample> =
        coroutineScope {
            val start = env.now()
            val samples = mutableListOf<ResendTestPlan.Sample>()
            val collector =
                launch {
                    trainer.data.filterNotNull().filter { it.atMs >= start }.collect { d ->
                        samples += ResendTestPlan.Sample(d.atMs - start, d.powerW, d.cadenceRpm)
                        val flywheel = d.flywheelRaw?.let { ", volant $it" }.orEmpty()
                        env.log("essai du renvoi · ${label(step)} : ${d.powerW} W, ${d.cadenceRpm} rpm$flywheel")
                    }
                }
            var nextKeepalive = start + ResendTestPlan.KEEPALIVE_MS
            while (true) {
                val now = env.now()
                val left = step.durationMs - (now - start)
                if (left <= 0) break
                if (step.keepalive.isNotEmpty() && now >= nextKeepalive) {
                    send(step.keepalive)
                    nextKeepalive += ResendTestPlan.KEEPALIVE_MS
                }
                onTick(((left + MS_PER_S - 1) / MS_PER_S).toInt())
                delay(minOf(left, MS_PER_S))
            }
            collector.cancel()
            samples
        }

    private fun label(step: ResendTestPlan.Step) = step.ratio?.let { "${step.code} $it" } ?: "échauffement"

    private fun describe(r: ResendTestPlan.Result): String =
        "essai du renvoi : ${label(r.step)} (${WAYS.getValue(r.step.way!!)}) : début ${r.earlyW ?: "—"} W, " +
            "fin ${r.powerW} W à ${r.cadenceRpm} rpm ; vrai vélo ${r.realBikeW} W"

    private companion object {
        const val MS_PER_S = 1_000L

        val WAYS =
            mapOf(
                ResendTestPlan.Way.NO_RESEND to "sans renvoi",
                ResendTestPlan.Way.GRADE to "pente renvoyée chaque seconde",
                ResendTestPlan.Way.GRADE_AND_GEAR to "pente, rapport et masses renvoyés",
            )
    }
}
