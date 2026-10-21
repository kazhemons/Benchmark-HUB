package com.majestic.benchjitter.stats

/**
 * Снимок статистики. Значения — в микросекундах.
 * mean/σ/percentile считаются по последним [windowCount] сэмплам («окно»),
 * min/max — накопительные за весь прогон.
 */
data class StatsSnapshot(
    val totalCount: Long,
    val windowCount: Int,
    val meanUs: Double,
    val stdDevUs: Double,
    val minUs: Double,
    val maxUs: Double,
    val p50Us: Double,
    val p90Us: Double,
    val p95Us: Double,
    val p99Us: Double,
    /** Сортированные сэмплы окна — для гистограммы. */
    val windowSorted: DoubleArray,
    /** Последние сэмплы в хронологическом порядке — для живого графика. */
    val tail: DoubleArray,
) {
    val isEmpty: Boolean get() = totalCount == 0L
}

/**
 * Накопитель значений в микросекундах с кольцевым буфером.
 * Потокобезопасен: пишет потоковый движок, читает UI.
 */
class JitterStats(private val capacity: Int = 8192) {
    private val samples = ArrayDeque<Double>()
    private var totalCount = 0L
    private var minUs = Double.NaN
    private var maxUs = Double.NaN

    @Synchronized
    fun record(valueMicros: Double) {
        if (!valueMicros.isFinite() || valueMicros < 0.0) return
        samples.addLast(valueMicros)
        if (samples.size > capacity) samples.removeFirst()
        totalCount += 1
        if (minUs.isNaN() || valueMicros < minUs) minUs = valueMicros
        if (maxUs.isNaN() || valueMicros > maxUs) maxUs = valueMicros
    }

    @Synchronized
    fun reset() {
        samples.clear()
        totalCount = 0
        minUs = Double.NaN
        maxUs = Double.NaN
    }

    @Synchronized
    fun count(): Long = totalCount

    @Synchronized
    fun snapshot(window: Int = WINDOW_DEFAULT, tail: Int = TAIL_DEFAULT): StatsSnapshot {
        val winSize = minOf(window, samples.size)
        val windowArr = lastN(winSize)
        val windowSorted = windowArr.copyOf().also { it.sort() }

        var sum = 0.0
        for (v in windowArr) sum += v
        val mean = if (winSize > 0) sum / winSize else Double.NaN
        var sq = 0.0
        if (winSize > 1) {
            for (v in windowArr) {
                val d = v - mean
                sq += d * d
            }
        }
        val stdDev = if (winSize > 1) kotlin.math.sqrt(sq / (winSize - 1)) else if (winSize == 1) 0.0 else Double.NaN

        return StatsSnapshot(
            totalCount = totalCount,
            windowCount = winSize,
            meanUs = mean,
            stdDevUs = stdDev,
            minUs = minUs,
            maxUs = maxUs,
            p50Us = percentile(windowSorted, 0.5),
            p90Us = percentile(windowSorted, 0.90),
            p95Us = percentile(windowSorted, 0.95),
            p99Us = percentile(windowSorted, 0.99),
            windowSorted = windowSorted,
            tail = lastN(minOf(tail, samples.size)),
        )
    }

    /** Последние [n] сэмплов в хронологическом порядке. */
    @Synchronized
    fun tail(n: Int): DoubleArray = lastN(minOf(n, samples.size))

    private fun lastN(n: Int): DoubleArray {
        val out = DoubleArray(n)
        var skip = samples.size - n
        var i = 0
        for (v in samples) {
            if (skip > 0) {
                skip--
                continue
            }
            out[i++] = v
        }
        return out
    }

    companion object {
        const val WINDOW_DEFAULT = 400
        const val TAIL_DEFAULT = 260

        /** Линейно интерполируемый перцентиль по отсортированному массиву. */
        fun percentile(sorted: DoubleArray, q: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            if (sorted.size == 1) return sorted[0]
            val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(sorted.size - 1)
            val frac = pos - lo
            return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
        }

        fun median(sorted: DoubleArray): Double = percentile(sorted, 0.5)
    }
}
