package com.majestic.benchjitter.engine

import com.majestic.benchjitter.stats.JitterStats

data class CpuSnapshot(
    val threads: Int,
    val totalOpsDone: Long,
    val meanQps: Double,
    val stdDevQps: Double,
    val p50Qps: Double,
    val p99Qps: Double,
    val hasData: Boolean,
)

/**
 * Стресс-тест CPU: N рабочих потонов гоняют одинаковый поток зависимых float-операций
 * (цепочка sqrt — константы не свернутся, DCE не выкинет). Считается, сколько
 * «квантов» (QUANTUM_OPS операций) суммарно выполняют все потоки в секунду.
 * Профиль скорости пишется в JitterStats (единица — кванты/с): mean/σ/перцентили
 * показывают и производительность, и стабильность троттлинга.
 * Чистый JVM: тестировается юнит-тестом.
 */
class CpuCollector {

    companion object {
        const val QUANTUM_OPS = 100_000
        private const val SAMPLE_MS = 1_000L
    }

    private class Worker {
        @Volatile var stop = false
        @Volatile var quanta = 0L
        private var sink = 0.0

        fun run() {
            var a = 1.0
            while (!stop) {
                var acc = 0.0
                var i = 0
                while (i < QUANTUM_OPS) {
                    a = Math.sqrt(a * a + i * 1e-3 + 1.0) * 1.0000001 - 1.0000001
                    acc += a
                    i += 1
                }
                quanta += 1L
                sink = acc
            }
        }
    }

    private var workers = emptyList<Thread>()
    private var workerBodies = emptyList<Worker>()
    private var sampler: Thread? = null

    @Volatile private var running = false
    @Volatile private var currentThreads = 0
    @Volatile var totalOpsQuanta = 0L
        private set

    val rateStats = JitterStats() // сэмплы = кванты/с

    fun isRunning(): Boolean = running
    fun currentThreads(): Int = currentThreads

    fun startFresh(threadCount: Int) {
        stop()
        rateStats.reset()
        totalOpsQuanta = 0L
        currentThreads = threadCount.coerceAtLeast(1)
        running = true

        workerBodies = List(currentThreads) { Worker() }
        workers = workerBodies.map { w ->
            Thread { w.run() }.also { it.isDaemon = true; it.name = "bj-cpu"; it.start() }
        }
        sampler = Thread { sampleLoop() }.also { it.isDaemon = true; it.name = "bj-cpu-sample"; it.start() }
    }

    private fun sampleLoop() {
        var prevTotal = 0L
        var prevNs = System.nanoTime()
        Thread.sleep(SAMPLE_MS) // окно разгона: потоки уже вышли на частоты
        while (running) {
            Thread.sleep(SAMPLE_MS)
            val nowNs = System.nanoTime()
            val total = workerBodies.sumOf { it.quanta }
            val elapsedMs = (nowNs - prevNs) / 1_000_000.0
            if (elapsedMs > 0.0) {
                val delta = total - prevTotal
                if (delta > 0L) {
                    rateStats.record(delta * 1_000.0 / elapsedMs) // кванты/с
                    totalOpsQuanta += delta
                }
            }
            prevTotal = total
            prevNs = nowNs
        }
    }

    fun stop() {
        running = false
        workerBodies.forEach { it.stop = true }
        workers.forEach { runCatching { it.join(1_000) } }
        workers = emptyList()
        workerBodies = emptyList()
        sampler?.let { runCatching { it.join(1_000) } }
        sampler = null
    }

    fun reset() {
        stop()
        rateStats.reset()
        totalOpsQuanta = 0L
        currentThreads = 0
    }

    fun snapshot(): CpuSnapshot {
        val snap = rateStats.snapshot()
        return CpuSnapshot(
            threads = currentThreads,
            totalOpsDone = totalOpsQuanta * QUANTUM_OPS,
            meanQps = snap.meanUs,
            stdDevQps = snap.stdDevUs,
            p50Qps = snap.p50Us,
            p99Qps = snap.p99Us,
            hasData = snap.totalCount > 0,
        )
    }
}
