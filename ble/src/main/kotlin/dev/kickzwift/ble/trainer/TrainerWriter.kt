package dev.kickzwift.ble.trainer

import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.grade.GradeLimits
import dev.kickzwift.protocol.trainer.TrainerCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Bornes physiques appliquées à toute consigne (spécification, « Sécurité »). */
data class TrainerLimits(
    val grade: GradeLimits = GradeLimits(),
    val ergMaxW: Int = 600,
) {
    fun clamp(target: TrainerTarget): TrainerTarget =
        when (target) {
            is TrainerTarget.Simulation -> target.copy(gradePct = target.gradePct.coerceIn(grade.minPct, grade.maxPct))
            is TrainerTarget.Erg -> target.copy(watts = target.watts.coerceIn(0, ergMaxW))
            TrainerTarget.Neutral -> target
        }
}

/**
 * Seul écrivain du trainer (spécification, « Comportements temps réel ») :
 *
 * - seule la dernière consigne part (canal « conflated »), au plus une écriture par [interval] ;
 * - une consigne identique à la précédente n'est pas réécrite, sauf [refresh] ;
 * - toute consigne est ramenée dans [limits] ;
 * - après un changement de rapport, une fois le calme revenu et au plus une fois par
 *   [verifyEvery], le rapport appliqué est relu (requête 520) ; en cas d'écart, la consigne
 *   est réécrite une fois et l'écart journalisé.
 */
class TrainerWriter(
    private val trainer: Trainer,
    private val limits: () -> TrainerLimits = { TrainerLimits() },
    private val interval: () -> Duration = { DEFAULT_INTERVAL },
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val verifyEvery: Duration = 1.seconds,
) {
    private class Order(
        val target: TrainerTarget,
        val force: Boolean,
    )

    private val orders = Channel<Order>(Channel.CONFLATED)

    @Volatile
    private var sent: TrainerTarget? = null
    private var pendingRatio: Double? = null
    private var verifiedRatio: Double? = null
    private var lastVerifyAt: Long? = null

    /** Dernière consigne écrite avec succès. */
    val lastSent: TrainerTarget? get() = sent

    fun submit(target: TrainerTarget) {
        orders.trySend(Order(target, force = false))
    }

    /** Réécrit la consigne même inchangée, et revérifie le rapport : après une reconnexion du trainer. */
    fun refresh(target: TrainerTarget) {
        orders.trySend(Order(target, force = true))
    }

    /** Tourne jusqu'à annulation. */
    suspend fun run() {
        while (true) {
            val wait = pendingRatio?.let { quietWait() }
            val order = if (wait == null) orders.receive() else withTimeoutOrNull(wait) { orders.receive() }
            if (order == null) {
                pendingRatio?.let { verify(it) }
                pendingRatio = null
            } else {
                write(order)
            }
        }
    }

    /** Remet le trainer à plat tout de suite, hors file : arrêt du service ou plantage. */
    suspend fun neutral(timeout: Duration = 1.seconds): Boolean =
        withTimeoutOrNull(timeout) { trainer.apply(TrainerTarget.Neutral).isSuccess }
            ?.also { if (it) sent = TrainerTarget.Neutral } == true

    private suspend fun write(order: Order) {
        val target = limits().clamp(order.target)
        if (order.force) verifiedRatio = null
        if (target == sent && !order.force) return
        trainer
            .apply(target)
            .onSuccess {
                sent = target
                val ratio = (target as? TrainerTarget.Simulation)?.gearRatio
                if (ratio != null && ratio != verifiedRatio) pendingRatio = ratio
            }.onFailure {
                sent = null
                log("trainer : écriture refusée, ${it.message}")
            }
        delay(interval())
    }

    private fun quietWait(): Duration {
        val sinceVerify = lastVerifyAt?.let { now() - it } ?: Long.MAX_VALUE
        val throttle = (verifyEvery.inWholeMilliseconds - sinceVerify).coerceAtLeast(0)
        return maxOf(interval().inWholeMilliseconds, throttle).milliseconds
    }

    private suspend fun verify(expected: Double) {
        lastVerifyAt = now()
        val actual = trainer.readGearRatio()
        verifiedRatio = expected
        when {
            actual == null -> Unit // repli FTMS ou pas de réponse : rien à comparer
            TrainerCodec.toGearRatioX10000(expected) == (actual * RATIO_SCALE).roundToInt() -> Unit
            else -> {
                log("trainer : rapport relu $actual au lieu de $expected, réécriture")
                sent?.let { trainer.apply(it) }
            }
        }
    }

    companion object {
        val DEFAULT_INTERVAL = 150.milliseconds
        private const val RATIO_SCALE = 10_000.0
    }
}
