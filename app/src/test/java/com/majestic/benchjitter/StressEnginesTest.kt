package com.majestic.benchjitter

import com.majestic.benchjitter.engine.CpuCollector
import com.majestic.benchjitter.engine.RamCollector
import com.majestic.benchjitter.engine.RomCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Смоук-тесты стресс-движков: цикл собирается, измеряет, останавливается, не оставляет мусора. */
class StressEnginesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cpuCollectorMeasuresThroughput() {
        val c = CpuCollector()
        c.startFresh(1)
        Thread.sleep(2_500) // два окна сэмплера (1 с разгон + первое измерение)
        val s = c.snapshot()
        c.stop()
        assertEquals(1, s.threads)
        assertTrue("окна скорости должны появиться", s.hasData)
        assertTrue("темп положителен", s.meanQps > 0.0)
        assertTrue("кванты намотаны", s.totalOpsDone > 0L)
        assertTrue("p50 положительна", s.p50Qps > 0.0)
    }

    @Test
    fun cpuCollectorResetClearsState() {
        val c = CpuCollector()
        c.startFresh(2)
        Thread.sleep(1_300)
        c.reset()
        val s = c.snapshot()
        assertFalse("после reset данных быть не должно", s.hasData)
        assertEquals(0L, s.totalOpsDone)
    }

    @Test
    fun ramCollectorHitsOverrideCeilingAndResetFrees() {
        val chunk = 2 * 1024 * 1024
        val ceiling = 10L * 1024 * 1024
        val r = RamCollector(chunkBytes = chunk, maxBytesOverride = ceiling)
        r.startFresh()
        var waited = 0
        while (r.isRunning() && waited < 10_000) {
            Thread.sleep(50)
            waited += 50
        }
        val s = r.snapshot()
        assertTrue(s.hasData)
        assertEquals("чанки должны заполнить ровно потолок", 10.0, s.allocatedMB, 0.001)
        assertTrue("потолок должен быть зафиксирован", s.hitLimit)
        assertTrue("заполнение быстрее нуля", s.fillMBps > 0.0)

        r.reset()
        val s2 = r.snapshot()
        assertFalse("после reset данных быть не должно", s2.hasData)
        assertEquals(0.0, s2.allocatedMB, 0.0)
    }

    @Test
    fun romCollectorWritesReadsAndCleansUp() {
        val root = tmp.root
        val r = RomCollector(root, fileSizeBytes = 2 * 1024 * 1024, ioChunkBytes = 512 * 1024)
        r.startFresh()
        var waited = 0
        while (r.passesDone < 1 && waited < 15_000) {
            Thread.sleep(100)
            waited += 100
        }
        assertTrue("хотя бы один проход запись+чтение", r.passesDone >= 1)
        r.stop()
        val s = r.snapshot()
        assertTrue(s.hasData)
        assertTrue("запись измеряется", s.writeSnap.meanUs > 0.0 && s.writeMBpsMean > 1.0)
        assertTrue("чтение измеряется", s.readSnap.meanUs > 0.0 && s.readMBpsMean > 1.0)
        val leftover = root.listFiles { file: File -> file.name.startsWith("benchjitter_io") }
        assertTrue("тестовый файл должен удалиться", leftover == null || leftover.isEmpty())
    }
}
