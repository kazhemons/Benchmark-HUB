package com.majestic.benchjitter.engine

import com.majestic.benchjitter.stats.JitterStats

/**
 * Режим 1: интервалы между кадрами дисплея.
 * Вызывать [onFrameNanos] с метками времени кадра (withFrameNanos / Choreographer),
 * привязанными к vsync. Сэмплы — интервалы в микросекундах.
 *
 * Номинальный период = медиана последних сэмплов (= период экрана).
 * Пропущенный кадр — интервал длиннее 1.5 номинального периода.
 * Интервалы-гэпы (> 100 мс: пауза, переключение активности) в распределение не попадают.
 */
class FrameCollector {
    val stats = JitterStats()

    @Volatile
    var missedFrames: Long = 0
        private set

    @Volatile
    var resyncGaps: Long = 0
        private set

    /** Медианный период кадра, мкс (0 пока недостаточно данных). */
    @Volatile
    var nominalPeriodUs: Double = 0.0
        private set

    private var lastFrameNs = 0L
    private val nominalWindow = ArrayDeque<Double>()

    fun reset() {
        stats.reset()
        missedFrames = 0
        resyncGaps = 0
        nominalPeriodUs = 0.0
        nominalWindow.clear()
        lastFrameNs = 0
    }

    fun onFrameNanos(nanos: Long) {
        val prev = lastFrameNs
        lastFrameNs = nanos
        if (prev == 0L) return

        val intervalUs = (nanos - prev) / 1000.0
        if (intervalUs <= 0.0) return
        if (intervalUs > MAX_TRACKED_INTERVAL_US) {
            // Кадр после паузы/сворачивания — это гэп, а не пропущенный кадр.
            resyncGaps += 1
            return
        }

        stats.record(intervalUs)

        nominalWindow.addLast(intervalUs)
        if (nominalWindow.size > NOMINAL_WINDOW) nominalWindow.removeFirst()
        if (nominalWindow.size >= MIN_SAMPLES_FOR_NOMINAL) {
            val med = JitterStats.percentile(nominalWindow.toDoubleArray().also { it.sort() }, 0.5)
            if (med > 0.0) nominalPeriodUs = med
        }

        if (nominalPeriodUs > 0.0 && intervalUs > nominalPeriodUs * MISSED_FACTOR) {
            missedFrames += 1
        }
    }

    private companion object {
        const val MAX_TRACKED_INTERVAL_US = 100_000.0
        const val MISSED_FACTOR = 1.5
        const val NOMINAL_WINDOW = 120
        const val MIN_SAMPLES_FOR_NOMINAL = 30
    }
}
