package com.majestic.benchjitter.engine

import com.majestic.benchjitter.stats.JitterStats
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class RomSnapshot(
    val passesDone: Long,
    val fileSizeMB: Double,
    val ioChunkKB: Int,
    val writeSnap: com.majestic.benchjitter.stats.StatsSnapshot,
    val readSnap: com.majestic.benchjitter.stats.StatsSnapshot,
    val writeMBpsMean: Double,
    val readMBpsMean: Double,
    val storageFull: Boolean,
    val hasData: Boolean,
)

/**
 * Стресс-тест ROM (флеш): в cacheDir пишется файл [fileSizeBytes] кусками
 * [ioChunkBytes] с fsync в конце файла, затем читается обратно и удаляется.
 * Цикл повторяется, пока идёт прогон. Каждый кусок записи/чтения — сэмпл времени.
 * Скорость записи честная (fsync прижимает данные на носитель); чтение идёт
 * через кэш ФС — это потолок кэша, а не «сырая» скорость флеша (описание в UI).
 */
class RomCollector(
    private val dir: File,
    val fileSizeBytes: Int = 64 * 1024 * 1024,
    val ioChunkBytes: Int = 1024 * 1024,
) {

    private val file = File(dir, "benchjitter_io.bin")

    val writeStats = JitterStats() // мкс на кусок записи
    val readStats = JitterStats()  // мкс на кусок чтения

    @Volatile var passesDone = 0L
        private set
    @Volatile var storageFull = false
        private set
    @Volatile private var running = false
    private var worker: Thread? = null

    fun isRunning(): Boolean = running

    fun startFresh() {
        stop()
        writeStats.reset()
        readStats.reset()
        passesDone = 0L
        storageFull = false
        deleteFile()
        running = true
        worker = Thread { workLoop() }.also { it.isDaemon = true; it.name = "bj-rom"; it.start() }
    }

    private fun workLoop() {
        // детерминированный псевдослучайный паттерн: не схмётся в нули/константы
        val buf = ByteArray(ioChunkBytes)
        run { var v = 7; for (i in buf.indices) { v = (v * 31 + 7) and 0xFF; buf[i] = v.toByte() } }
        try {
            while (running) {
                if (dir.usableSpace < fileSizeBytes * 2L) {
                    storageFull = true
                    break
                }
                try {
                    FileOutputStream(file).use { fos ->
                        var written = 0
                        while (running && written < fileSizeBytes) {
                            val t0 = System.nanoTime()
                            fos.write(buf)
                            writeStats.record((System.nanoTime() - t0) / 1_000.0)
                            written += ioChunkBytes
                        }
                        fos.flush()
                        fos.fd.sync()
                    }
                    if (!running) break
                } catch (e: java.io.IOException) {
                    storageFull = true
                    break
                }
                try {
                    var readTotal = 0L
                    var sink = 0L
                    FileInputStream(file).use { fis ->
                        while (running && readTotal < fileSizeBytes) {
                            val t0 = System.nanoTime()
                            val n = fis.read(buf)
                            readStats.record((System.nanoTime() - t0) / 1_000.0)
                            if (n <= 0) break
                            sink += buf[0].toInt() and 0xFF
                            readTotal += n
                        }
                    }
                    if (sink == -1L) println(sink) // защитный холст от DCE (никогда не выполняется)
                    deleteFile()
                    passesDone += 1
                } catch (e: java.io.IOException) {
                    storageFull = true
                    break
                }
            }
        } finally {
            running = false
            deleteFile() // не оставляем мусор в cacheDir
        }
    }

    /** Скорость по среднему времени куска: МБ/с. */
    fun mbps(meanUs: Double): Double =
        if (meanUs <= 0.0 || meanUs.isNaN()) Double.NaN
        else ioChunkBytes * 1_000_000.0 / 1_048_576.0 / meanUs

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(3_000) } }
        worker = null
    }

    fun reset() {
        stop()
        writeStats.reset()
        readStats.reset()
        passesDone = 0L
        storageFull = false
        deleteFile()
    }

    private fun deleteFile() {
        runCatching { file.delete() }
    }

    fun snapshot(): RomSnapshot {
        val ws = writeStats.snapshot()
        val rs = readStats.snapshot()
        return RomSnapshot(
            passesDone = passesDone,
            fileSizeMB = fileSizeBytes / 1_048_576.0,
            ioChunkKB = ioChunkBytes / 1024,
            writeSnap = ws,
            readSnap = rs,
            writeMBpsMean = mbps(ws.meanUs),
            readMBpsMean = mbps(rs.meanUs),
            storageFull = storageFull,
            hasData = ws.totalCount > 0,
        )
    }
}
