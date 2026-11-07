package dev.kickzwift.app.data

import dev.kickzwift.protocol.fit.CurvePoint
import dev.kickzwift.protocol.fit.FitEncoder
import dev.kickzwift.protocol.fit.FitSample
import dev.kickzwift.protocol.fit.FitSummary
import dev.kickzwift.protocol.fit.RideCurve
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RideFitsTest {
    private val start = 1_792_692_195_391L

    private fun ride(
        firstMs: Long,
        powerW: Int,
    ) = listOf(FitSample(firstMs, powerW, 80, 30.0, 0.0, 140))

    @Test
    fun `le FIT parti avec l'activite donne sa courbe`() {
        val rides = listOf(ride(start - 3_600_000, 100), ride(start + 800, 200))
        assertEquals(listOf(CurvePoint(200, 140)), curveFor(start, rides).points)
    }

    @Test
    fun `aucun FIT ne correspond - courbe vide`() {
        assertEquals(RideCurve.EMPTY, curveFor(start, listOf(ride(start + 61_000, 100))))
        assertEquals(RideCurve.EMPTY, curveFor(start, emptyList()))
    }

    private fun writeFit(
        dir: File,
        name: String,
        firstMs: Long,
    ) = File(dir, name).apply {
        val samples = ride(firstMs, 150)
        writeBytes(FitEncoder.encode(samples, FitSummary(firstMs, firstMs + 1_000, 1_000, 8.0, 150, 150, 80)))
    }

    @Test
    fun `suppression - seul le FIT de l'activite disparait`(
        @TempDir dir: File,
    ) = runTest {
        val own = writeFit(dir, "zrkc-ride-a.fit", start + 800)
        val other = writeFit(dir, "zrkc-ride-b.fit", start + 86_400_000)
        val journal = File(dir, "journal.txt").apply { writeText("journal") }

        deleteFit(dir, start) { }

        assertFalse(own.exists())
        assertTrue(other.exists())
        assertTrue(journal.exists())
    }

    @Test
    fun `suppression - sans FIT ni dossier, rien a faire`(
        @TempDir dir: File,
    ) = runTest {
        deleteFit(dir, start) { }
        deleteFit(File(dir, "absent"), start) { }
    }
}
