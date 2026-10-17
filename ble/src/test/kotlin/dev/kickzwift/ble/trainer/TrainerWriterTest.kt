package dev.kickzwift.ble.trainer

import dev.kickzwift.ble.Trainer
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerWriterTest {
    /** Faux trainer : note chaque écriture et son heure ; la requête 520 relit [applied]. */
    private class FakeTrainer(
        private val clock: () -> Long,
    ) : Trainer {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
        override val data: StateFlow<RidingData?> = MutableStateFlow(null)
        val writes = mutableListOf<Pair<Long, TrainerTarget>>()
        val reads = mutableListOf<Long>()
        var applied: Double? = null
        var answer: () -> Double? = { applied }
        var fail = false

        override suspend fun apply(target: TrainerTarget): Result<Unit> {
            if (fail) return Result.failure(IllegalStateException("non prêt"))
            writes += clock() to target
            (target as? TrainerTarget.Simulation)?.let { applied = it.gearRatio }
            return Result.success(Unit)
        }

        override suspend fun readGearRatio(): Double? {
            reads += clock()
            return answer()
        }
    }

    private fun sim(
        ratio: Double,
        grade: Double = 0.0,
    ) = TrainerTarget.Simulation(grade, ratio, 75.0, 9.5)

    private fun TestScope.setup(): Triple<FakeTrainer, TrainerWriter, MutableList<String>> {
        val logs = mutableListOf<String>()
        val trainer = FakeTrainer { currentTime }
        val writer = TrainerWriter(trainer, now = { currentTime }, log = { logs += it })
        backgroundScope.launch { writer.run() }
        runCurrent()
        return Triple(trainer, writer, logs)
    }

    @Test
    fun `rafale de 10 changements - seule la derniere consigne part apres l'intervalle, puis une verification`() =
        runTest {
            val (trainer, writer, logs) = setup()
            for (gear in 1..10) {
                writer.submit(sim(1.0 + gear / 10.0))
                advanceTimeBy(10)
            }
            advanceTimeBy(5_000)
            assertEquals(listOf(sim(1.1), sim(2.0)), trainer.writes.map { it.second })
            assertEquals(150L, trainer.writes[1].first - trainer.writes[0].first)
            assertEquals(1, trainer.reads.size)
            assertEquals(2.0, trainer.applied)
            assertEquals(emptyList<String>(), logs)
            assertEquals(sim(2.0), writer.lastSent)
        }

    @Test
    fun `consigne bornee - pente et plafond ERG`() =
        runTest {
            val (trainer, writer) = setup()
            writer.submit(sim(2.4, grade = 30.0))
            advanceTimeBy(200)
            writer.submit(TrainerTarget.Erg(900))
            advanceTimeBy(200)
            writer.submit(TrainerTarget.Erg(-5))
            advanceTimeBy(200)
            assertEquals(listOf(sim(2.4, 16.0), TrainerTarget.Erg(600), TrainerTarget.Erg(0)), trainer.writes.map { it.second })
        }

    @Test
    fun `consigne inchangee non reecrite, sauf refresh qui reverifie`() =
        runTest {
            val (trainer, writer) = setup()
            writer.submit(sim(2.4))
            advanceTimeBy(2_000)
            writer.submit(sim(2.4))
            advanceTimeBy(2_000)
            assertEquals(1, trainer.writes.size)
            assertEquals(1, trainer.reads.size)
            // Pente seule changée : écrite, rapport déjà vérifié.
            writer.submit(sim(2.4, grade = 1.0))
            advanceTimeBy(2_000)
            assertEquals(2, trainer.writes.size)
            assertEquals(1, trainer.reads.size)
            writer.refresh(sim(2.4, grade = 1.0))
            advanceTimeBy(2_000)
            assertEquals(3, trainer.writes.size)
            assertEquals(2, trainer.reads.size)
        }

    @Test
    fun `ecart a la relecture - une reecriture et une entree au journal`() =
        runTest {
            val (trainer, writer, logs) = setup()
            trainer.answer = { 1.23 }
            writer.submit(sim(2.4))
            advanceTimeBy(5_000)
            assertEquals(2, trainer.writes.size)
            assertEquals(1, trainer.reads.size)
            assertEquals(1, logs.size)
            assertTrue(logs.single().contains("1.23"))
        }

    @Test
    fun `sans requete 520 (FTMS) - rien a comparer`() =
        runTest {
            val (trainer, writer, logs) = setup()
            trainer.answer = { null }
            writer.submit(sim(2.4))
            advanceTimeBy(5_000)
            assertEquals(1, trainer.writes.size)
            assertEquals(1, trainer.reads.size)
            assertEquals(emptyList<String>(), logs)
        }

    @Test
    fun `verification au plus une fois par seconde`() =
        runTest {
            val (trainer, writer) = setup()
            writer.submit(sim(2.0))
            advanceTimeBy(400)
            writer.submit(sim(2.2))
            advanceTimeBy(3_000)
            writer.submit(sim(2.4))
            advanceTimeBy(3_000)
            assertEquals(3, trainer.reads.size)
            assertTrue(trainer.reads.zipWithNext().all { (a, b) -> b - a >= 1_000 })
        }

    @Test
    fun `echec d'ecriture - journalise, la meme consigne repart ensuite`() =
        runTest {
            val (trainer, writer, logs) = setup()
            trainer.fail = true
            writer.submit(sim(2.4))
            advanceTimeBy(200)
            assertEquals(1, logs.size)
            trainer.fail = false
            writer.submit(sim(2.4))
            advanceTimeBy(200)
            assertEquals(listOf(sim(2.4)), trainer.writes.map { it.second })
        }

    @Test
    fun `remise a plat immediate`() =
        runTest {
            val (trainer, writer) = setup()
            assertTrue(writer.neutral())
            assertEquals(listOf(TrainerTarget.Neutral), trainer.writes.map { it.second })
            assertEquals(TrainerTarget.Neutral, writer.lastSent)
            trainer.fail = true
            assertFalse(writer.neutral(1.seconds))
        }
}
