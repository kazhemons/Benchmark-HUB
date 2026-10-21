package com.majestic.benchjitter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoReportTest {

    @Test
    fun coreFreqsGroupByDescendingFrequency() {
        // раскладка Snapdragon 888: 1×2840 + 3×2208 + 4×1804 МГц
        val cores = listOf(
            CpuCoreInfo(0, 1804), CpuCoreInfo(4, 1804), CpuCoreInfo(5, 1804), CpuCoreInfo(7, 1804),
            CpuCoreInfo(1, 2208), CpuCoreInfo(2, 2208), CpuCoreInfo(3, 2208),
            CpuCoreInfo(6, 2840),
        )
        val groups = groupCoreFreqs(cores)
        assertEquals(listOf(1 to 2840, 3 to 2208, 4 to 1804), groups)
    }

    @Test
    fun emptyCoresGiveNoGroups() {
        assertEquals(emptyList<Pair<Int, Int>>(), groupCoreFreqs(emptyList()))
    }
}
