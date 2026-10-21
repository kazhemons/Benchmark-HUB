package com.majestic.benchjitter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterStatsTest {

    private fun assertNear(expected: Double, actual: Double, delta: Double = 1e-6) {
        assertEquals(expected, actual, delta)
    }

    @Test
    fun percentileLinearInterpolation() {
        val sorted = DoubleArray(100) { (it + 1).toDouble() } // 1.0..100.0
        assertNear(50.5, JitterStats.percentile(sorted, 0.5))
        assertNear(90.1, JitterStats.percentile(sorted, 0.90))
        assertNear(95.05, JitterStats.percentile(sorted, 0.95))
        assertNear(99.01, JitterStats.percentile(sorted, 0.99))
        assertNear(1.0, JitterStats.percentile(sorted, 0.0))
        assertNear(100.0, JitterStats.percentile(sorted, 1.0))
    }

    @Test
    fun medianOddAndSingle() {
        val sorted = doubleArrayOf(1.0, 2.0, 3.0)
        assertNear(2.0, JitterStats.median(sorted))
        assertNear(7.0, JitterStats.median(doubleArrayOf(7.0)))
        assertTrue(JitterStats.median(doubleArrayOf()).isNaN())
    }

    @Test
    fun meanAndSampleStdDevKnownTextbookCase() {
        // Классический учебный пример: mean = 5, выборочное σ (Bessel) = 2.13808993...
        val stats = JitterStats()
        for (v in doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)) stats.record(v)
        val snap = stats.snapshot()
        assertEquals(8L, snap.totalCount)
        assertEquals(8, snap.windowCount)
        assertNear(5.0, snap.meanUs)
        assertNear(2.138089935180, snap.stdDevUs, 1e-9)
        assertNear(2.0, snap.minUs)
        assertNear(9.0, snap.maxUs)
        assertNear(4.5, snap.p50Us)
    }

    @Test
    fun cumulativeMinMaxWithWindowPercentiles() {
        val stats = JitterStats()
        // Худший выбор в начале прогона — min/max остаются накопительными.
        stats.record(1000.0)
        stats.record(500.0)
        for (i in 1..1000) stats.record(60.0 + (i % 7))
        val snap = stats.snapshot(window = 400)
        assertEquals(1002L, snap.totalCount)
        assertEquals(400, snap.windowCount)
        // min/max накопительные: худшие первые сэмплы остаются, даже выпав из окна
        assertNear(1000.0, snap.maxUs)
        assertNear(60.0, snap.minUs)
        // В окне значения 60..66 -> p50 обязан быть внутри этого диапазона.
        assertTrue(snap.p50Us >= 60.0 && snap.p50Us <= 66.0)
    }

    @Test
    fun tailChronologicalAndCapacityBounded() {
        val stats = JitterStats(capacity = 50)
        for (i in 1..100) stats.record(i.toDouble())
        val snap = stats.snapshot(window = 40, tail = 20)
        assertEquals(100L, snap.totalCount)
        assertEquals(40, snap.windowCount)
        assertEquals(20, snap.tail.size)
        assertNear(81.0, snap.tail.first())
        assertNear(100.0, snap.tail.last())
        // Окно — только последние 40 сэмплов (61..100)
        assertNear(61.0, snap.windowSorted.first())
        assertNear(100.0, snap.windowSorted.last())
    }

    @Test
    fun resetClearsEverything() {
        val stats = JitterStats()
        repeat(10) { stats.record(1.0) }
        stats.reset()
        val snap = stats.snapshot()
        assertEquals(0L, snap.totalCount)
        assertEquals(0, snap.windowCount)
        assertTrue(snap.isEmpty)
        assertTrue(snap.p50Us.isNaN())
        assertTrue(snap.minUs.isNaN())
        assertEquals(0, snap.tail.size)
    }

    @Test
    fun ignoresInvalidSamples() {
        val stats = JitterStats()
        stats.record(-1.0)
        stats.record(Double.NaN)
        stats.record(Double.POSITIVE_INFINITY)
        stats.record(10.0)
        assertEquals(1L, stats.count())
    }
}
