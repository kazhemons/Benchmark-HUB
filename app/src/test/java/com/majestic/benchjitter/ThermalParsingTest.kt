package com.majestic.benchjitter

import com.majestic.benchjitter.engine.batteryTempC
import com.majestic.benchjitter.engine.classifyTempSensor
import com.majestic.benchjitter.engine.classifyZoneType
import com.majestic.benchjitter.engine.detectSocFamily
import com.majestic.benchjitter.engine.isTempCandidateName
import com.majestic.benchjitter.engine.SocFamily
import com.majestic.benchjitter.engine.thermalStatusLabel
import com.majestic.benchjitter.engine.zoneTempC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalParsingTest {

    @Test
    fun batteryExtraIsInTenthsOfDegree() {
        assertEquals(31.4, batteryTempC(314), 1e-9)
        assertEquals(0.0, batteryTempC(0), 1e-9)
    }

    @Test
    fun cpuHotspotSensorTypeIsAlwaysCpu() {
        // 331 = скрытый Sensor.TYPE_CPU_HOTSPOT — по определению CPU-кластеры,
        // тип важнее имени (GPU-сенсоры таким типом не помечаются).
        assertEquals("CPU", classifyTempSensor("Qualcomm cluster sensor", 331))
    }

    @Test
    fun namesClassifyByCategory() {
        assertEquals("CPU", classifyTempSensor("cpu-temperature", 331))
        assertEquals("GPU", classifyTempSensor("gpunpu-temperature", 999))
        assertEquals("GPU", classifyTempSensor("GPU Thermal Sensor", 999))
        assertEquals("RAM", classifyTempSensor("ddr-temperature", 999))
        assertEquals("RAM", classifyTempSensor("UFS mem temp", 999))
    }

    @Test
    fun qualcommHotstyleNamesAreAccepted() {
        assertEquals("CPU", classifyTempSensor("cpu-hot", 331))
        assertEquals("GPU", classifyTempSensor("gpu-hot-spot", 999))
    }

    @Test
    fun zoneTypesClassifyByNodeName() {
        assertEquals("CPU", classifyZoneType("cpu-0-0-usr"))
        assertEquals("CPU", classifyZoneType("cpu-cluster0"))
        assertEquals("GPU", classifyZoneType("gpuss-0-0-usr"))
        assertEquals("GPU", classifyZoneType("npu-usr"))
        assertEquals("GPU", classifyZoneType("nspss-2-step")) // Hexagon NPU — ускоритель
        assertEquals("RAM", classifyZoneType("mem-usr"))
        assertEquals("RAM", classifyZoneType("ddr-usr"))
        assertNull(classifyZoneType("battery")) // батарея берётся из EXTRA_TEMPERATURE
        assertNull(classifyZoneType("mdmss-3-usr")) // модем не относим ни к одной категории
        assertNull(classifyZoneType("wcn-usr")) // wifi-модуль тоже
        assertNull(classifyZoneType("display-usr"))
    }

    @Test
    fun mediaTekZoneNamesAreRecognized() {
        assertEquals("CPU", classifyZoneType("c0-0-usr")) // кластерные имена MTK
        assertEquals("CPU", classifyZoneType("c2"))
        assertEquals("CPU", classifyZoneType("CPU-MID"))
        assertEquals("CPU", classifyZoneType("CPU-SUPER"))
        assertEquals("GPU", classifyZoneType("GPU-SW"))
        assertEquals("GPU", classifyZoneType("pvm_tsens0")) // Mali PVM-сенсоры
        assertEquals("GPU", classifyZoneType("mali-0"))
        assertEquals("RAM", classifyZoneType("memsys"))
    }

    @Test
    fun googleTensorZoneNamesAreRecognized() {
        assertEquals("CPU", classifyZoneType("tcu-0-usr")) // Thermal Co-Processing Unit
        assertEquals("CPU", classifyZoneType("soc-thermal"))
        assertNull(classifyZoneType("b_tz_03")) // термисторы батареи исключены
        assertNull(classifyZoneType("b_tz_07"))
    }

    @Test
    fun socFamiliesAreDetected() {
        assertEquals(SocFamily.QUALCOMM, detectSocFamily("Qualcomm Technologies, Inc. KONAIRE SM8350"))
        assertEquals(SocFamily.QUALCOMM, detectSocFamily("qcom lito"))
        assertEquals(SocFamily.MEDIATEK, detectSocFamily("Model name: MediaTek MT6891Z/CZA Dimensity"))
        assertEquals(SocFamily.MEDIATEK, detectSocFamily("Helio G99"))
        assertEquals(SocFamily.GOOGLE_TENSOR, detectSocFamily("Google Tensor GS201 zumipro"))
        assertEquals(SocFamily.GOOGLE_TENSOR, detectSocFamily("tensor goose caiman"))
        assertEquals(SocFamily.OTHER, detectSocFamily("Exynos 2100 S5E984"))
        assertEquals(SocFamily.OTHER, detectSocFamily(""))
    }

    @Test
    fun zoneRawUnitsAreNormalized() {
        assertEquals(45.0, zoneTempC(45000.0), 1e-9) // миллиградусы
        assertEquals(45.0, zoneTempC(45.0), 1e-9) // целые градусы
        assertEquals(-5.0, zoneTempC(-5.0), 1e-9)
    }

    @Test
    fun candidateFilterIsDeliberatelyBroadForDiagnostics() {
        assertTrue(isTempCandidateName("battery-temperature"))
        assertTrue(isTempCandidateName("cpu-usage")) // диагностически полезно показать и это
        assertTrue(isTempCandidateName("gpuss"))
        assertTrue(isTempCandidateName("thermal_zone watcher"))
        assertFalse(isTempCandidateName("gyroscope"))
        assertFalse(isTempCandidateName("light"))
        assertFalse(isTempCandidateName("proximity"))
    }

    @Test
    fun unrelatedSensorsAreIgnored() {
        assertNull(classifyTempSensor("battery-temperature", 999)) // берётся из EXTRA_TEMPERATURE
        assertNull(classifyTempSensor("ambient-temperature", 999))
        assertNull(classifyTempSensor("light", 5))
        assertNull(classifyTempSensor("cpu-boot-count", 999)) // без «temp» не сенсор температуры
    }

    @Test
    fun thermalStatusLabelsAreHumanReadable() {
        assertEquals("Норма", thermalStatusLabel(0))
        assertEquals("Сильный нагрев", thermalStatusLabel(3))
        assertEquals("Готовность к thermal shutdown", thermalStatusLabel(6))
        assertEquals("Неизвестно", thermalStatusLabel(99))
    }
}
