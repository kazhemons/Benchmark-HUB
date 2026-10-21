package com.majestic.benchjitter.engine

import android.os.Process
import com.majestic.benchjitter.stats.JitterStats
import java.util.concurrent.locks.LockSupport

/**
 * Режим 2: точность пробуждения потока по таймеру.
 * Фоновый поток ждёт абсолютных меток времени с заданным интервалом
 * (LockSupport.parkNanos с репарком против ложных пробуждений) и записывает
 * опоздание пробуждения относительно дедлайна (мкс). Идеал — вплотную у нуля.
 *
 * «Просроченная метка» — опоздание больше половины интервала.
 * Интервал и приоритет меняются на ходу ([setInterval], [setPriority]).
 * Смена интервала начинает новый прогон (старые сэмплы несравнимы с новым периодом).
 */
class WakeCollector {
    val stats = JitterStats()

    @Volatile
    var intervalUs: Double = DEFAULT_INTERVAL_US
        private set

    @Volatile
    var missedDeadlines: Long = 0
        private set

    @Volatile
    private var running = false

    @Volatile
    private var requestedPriority = Process.THREAD_PRIORITY_DEFAULT

    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun startFresh(intervalMicros: Double, priority: Int) {
        stop()
        stats.reset()
        missedDeadlines = 0
        intervalUs = intervalMicros.coerceIn(MIN_INTERVAL_US, MAX_INTERVAL_US)
        requestedPriority = priority
        running = true
        worker = Thread(::loop, "bench-wake").apply { isDaemon = true; start() }
    }

    /** Живая смена интервала: сбрасывает статистику — сравнивать старое с новым нельзя. */
    fun setInterval(intervalMicros: Double) {
        val v = intervalMicros.coerceIn(MIN_INTERVAL_US, MAX_INTERVAL_US)
        if (v == intervalUs) return
        if (!running) {
            // Изменение до старта — просто запоминаем новый интервал.
            intervalUs = v
            return
        }
        stats.reset()
        missedDeadlines = 0
        intervalUs = v
    }

    fun setPriority(priority: Int) {
        requestedPriority = priority
    }

    /** Очистка данных, пока поток остановлен (кнопка «Сброс»). */
    fun clearData() {
        if (!running) {
            stats.reset()
            missedDeadlines = 0
        }
    }

    fun stop() {
        running = false
        worker?.let { t ->
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
    }

    private fun loop() {
        var currentPriority = requestedPriority
        try {
            Process.setThreadPriority(currentPriority)
        } catch (_: Throwable) {
        }

        var interval = intervalUs
        var next = System.nanoTime() + (interval * 1000).toLong()

        while (running) {
            if (requestedPriority != currentPriority) {
                currentPriority = requestedPriority
                try {
                    Process.setThreadPriority(currentPriority)
                } catch (_: Throwable) {
                }
            }

            val newInterval = intervalUs
            if (newInterval != interval) {
                // Интервал изменён на ходу — пересинхронизируемся, ложную просрочку не пишем.
                interval = newInterval
                next = System.nanoTime() + (interval * 1000).toLong()
                continue
            }

            val now = System.nanoTime()
            if (now >= next) {
                val late = (now - next) / 1000.0
                stats.record(late)
                if (late > interval / 2.0) missedDeadlines += 1
                next = if (late > interval) {
                    // Поток голодал дольше интервала — догонять бессмысленно, стартуем заново.
                    now + (interval * 1000).toLong()
                } else {
                    next + (interval * 1000).toLong()
                }
            } else {
                LockSupport.parkNanos(next - now)
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_US = 500.0      // 0.5 мс
        const val MAX_INTERVAL_US = 10_000.0   // 10 мс
        const val DEFAULT_INTERVAL_US = 1_000.0

        /** Подписи -> значения android.os.Process.* (в порядке возрастания важности). */
        val PRIORITIES: List<Pair<String, Int>> = listOf(
            "Фон" to Process.THREAD_PRIORITY_BACKGROUND,
            "Обычный" to Process.THREAD_PRIORITY_DEFAULT,
            "Display" to Process.THREAD_PRIORITY_DISPLAY,
            "Аудио" to Process.THREAD_PRIORITY_AUDIO,
            "Срочный аудио" to Process.THREAD_PRIORITY_URGENT_AUDIO,
        )
    }
}
