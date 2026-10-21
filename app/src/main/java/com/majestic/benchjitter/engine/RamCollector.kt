package com.majestic.benchjitter.engine

import com.majestic.benchjitter.stats.JitterStats
import java.util.Arrays

data class RamSnapshot(
    val allocatedMB: Double,
    val chunkBytes: Int,
    val fillMeanUs: Double,
    val fillStdDevUs: Double,
    val fillP99Us: Double,
    val fillMBps: Double,
    val hitLimit: Boolean,
    val heapCapMB: Double,
    val hasData: Boolean,
)

/**
 * Стресс-тест RAM: поток последовательно выделяет чанки по [chunkBytes] байт,
 * заполняет их (касается каждой страницы — физическая память, а не виртуальная)
 * и держит, пока позволяет куча процесса (Runtime.maxMemory минус текущее
 * использование минус запас HEADROOM). Каждая итерация — время заполнения чанка.
 * Потолок — когда следующий чанк уже не влезает (или OOM).
 * Чистый JVM: тестировается юнит-тестом (override потолка для теста).
 */
class RamCollector(
    val chunkBytes: Int = 16 * 1024 * 1024,
    private val maxBytesOverride: Long? = null,
) {

    private companion object {
        const val HEADROOM = 48L * 1024 * 1024 // запас на сам процесс и GC
    }

    private val chunks = ArrayList<ByteArray>()
    @Volatile private var running = false
    @Volatile var hitLimit = false
        private set
    @Volatile private var allocatedBytes = 0L
    private var worker: Thread? = null

    val fillStats = JitterStats() // сэмплы = мкс заполнения чанка

    fun isRunning(): Boolean = running

    fun heapCapBytes(): Long {
        val override = maxBytesOverride
        if (override != null) return override
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) - HEADROOM
    }

    fun startFresh() {
        stop()
        clearChunks()
        fillStats.reset()
        hitLimit = false
        running = true
        worker = Thread { workLoop() }.also { it.isDaemon = true; it.name = "bj-ram"; it.start() }
    }

    private fun workLoop() {
        while (running) {
            val cap = effectiveCap()
            if (cap < chunkBytes) {
                hitLimit = true
                break
            }
            try {
                val chunk = ByteArray(chunkBytes)
                val t0 = System.nanoTime()
                Arrays.fill(chunk, 0x5A) // касание страниц: реальная аллокация
                val dtUs = (System.nanoTime() - t0) / 1_000.0
                synchronized(chunks) { chunks.add(chunk) }
                allocatedBytes += chunkBytes
                fillStats.record(dtUs)
            } catch (e: OutOfMemoryError) {
                hitLimit = true
                break
            }
        }
        running = false
    }

    private fun effectiveCap(): Long {
        val cap = heapCapBytes()
        val allocated = allocatedBytes
        val override = maxBytesOverride
        if (override != null) return override - allocated
        return cap - allocated
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(2_000) } }
        worker = null
    }

    private fun clearChunks() {
        synchronized(chunks) { chunks.clear() }
        allocatedBytes = 0L
    }

    /** Остановить и освободить память (кнопка «Сбросить»). */
    fun reset() {
        stop()
        clearChunks()
        fillStats.reset()
        hitLimit = false
        System.gc()
    }

    fun fillMBps(meanUs: Double): Double =
        if (meanUs <= 0.0 || meanUs.isNaN()) Double.NaN
        else chunkBytes * 1_000_000.0 / 1_048_576.0 / meanUs

    fun snapshot(): RamSnapshot {
        val snap = fillStats.snapshot()
        return RamSnapshot(
            allocatedMB = allocatedBytes / 1_048_576.0,
            chunkBytes = chunkBytes,
            fillMeanUs = snap.meanUs,
            fillStdDevUs = snap.stdDevUs,
            fillP99Us = snap.p99Us,
            fillMBps = fillMBps(snap.meanUs),
            hitLimit = hitLimit,
            heapCapMB = (maxBytesOverride ?: Runtime.getRuntime().maxMemory()) / 1_048_576.0,
            hasData = snap.totalCount > 0,
        )
    }
}
