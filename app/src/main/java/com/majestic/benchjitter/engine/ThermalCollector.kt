package com.majestic.benchjitter.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.abs
import kotlin.math.max

data class ThermalReading(
    val label: String,
    val valueC: Double,
    val maxC: Double,
)

/** Что реально обнаружено на устройстве — чтобы «недоступно» было объяснимо. */
data class ThermalDiagnostics(
    val zonesFound: Int,
    val zonesTyped: Int,
    val zonesShowingTemp: Int,
    val zoneTypeSamples: List<String>,
    val sensorCandidates: List<String>,
    val socFamilyLabel: String,
)

data class ThermalSnapshot(
    val readings: List<ThermalReading>,
    val hasSensorData: Boolean,
    val thermalStatusLabel: String?,
    val diagnostics: ThermalDiagnostics,
    /** true с момента «Старт» и до reset()/смены режима: максимумы относятся к прогону. */
    val runMeasured: Boolean,
) {
    val hasData: Boolean get() = readings.isNotEmpty() || thermalStatusLabel != null
}

// Скрытый в SDK Sensor.TYPE_CPU_HOTSPOT = 331 — по определению температура
// процессорных кластеров. (13 — это TYPE_AMBIENT_TEMPERATURE, не CPU.)
private const val SENSOR_TYPE_CPU_HOTSPOT = 331

// Именования «температурных» сенсоров у производителей разные: temperature /
// thermal / hot (qualcomm hotspot-сенсоры называются «cpu-hot» и т. п.).
private val SENSOR_TEMP_NAME_KEYS = listOf("temp", "thermal", "hot")

// Широкий фильтр для диагностического списка «что устройство вообще публикует».
private val CANDIDATE_NAME_HINTS = listOf(
    "temp", "thermal", "hot", "cpu", "gpu", "npu", "mem", "ram", "ddr", "ufs", "battery",
)

internal fun isTempCandidateName(name: String): Boolean {
    val n = name.lowercase()
    return CANDIDATE_NAME_HINTS.any { n.contains(it) }
}

// Шаблоны охватывают три основных семейства SoC:
//  Qualcomm — подтверждено наживую: cpu-*, gpuss-*, nspss (Hexagon), ddr/mem/ufs;
//  MediaTek — кластерные зоны c0-0-usr / CPU-MID / CPU-SUPER, GPU-SW, pvm
//    (тепловые сенсоры Mali PVM), memsys;
//  Google Tensor — tcu (тепловые блоки TCU), b_tz — термисторы батареи (исключаются), ddr.
private val CPU_TOKENS = listOf("cpu", "cluster", "soc", "tcu")
private val GPU_TOKENS = listOf("gpu", "npu", "nsp", "mali", "pvm")
private val RAM_TOKENS = listOf("ddr", "ram", "mem", "ufs")
private val BATTERY_ZONE_TOKENS = listOf("battery", "batt", "b_tz")
// Имена кластерных зон в стиле MediaTek (c0-…, c1-…) — общий паттерн,
// без collisions с полноценными именами (cpu… начинается не с «c+цифра»).
private val CLUSTER_NAME_RE = Regex("^c\\d(?:[-_]|$)")

private fun classifyCategory(lowered: String): String? = when {
    CPU_TOKENS.any { lowered.contains(it) } || CLUSTER_NAME_RE.containsMatchIn(lowered) -> "CPU"
    GPU_TOKENS.any { lowered.contains(it) } -> "GPU"
    RAM_TOKENS.any { lowered.contains(it) } -> "RAM"
    else -> null
}

/**
 * Классификация сенсора по имени: категория (CPU/GPU/RAM) или null.
 * Датчики температуры аккумулятора пропускаются — она берётся надёжнее через
 * ACTION_BATTERY_CHANGED.EXTRA_TEMPERATURE.
 */
internal fun classifyTempSensor(name: String, sensorType: Int): String? {
    if (sensorType == SENSOR_TYPE_CPU_HOTSPOT) return "CPU"
    val n = name.lowercase()
    if (SENSOR_TEMP_NAME_KEYS.none { n.contains(it) }) return null
    return classifyCategory(n)
}

/**
 * Классификация системной тепловой зоны (файл type в thermal_zoneNN).
 * Типы зон как раз названы по узлам: «cpu-0-0-usr», «gpuss-usr», «mem-usr».
 * Аккумуляторную зону пропускаем (см. выше).
 */
internal fun classifyZoneType(zoneType: String): String? {
    val n = zoneType.lowercase()
    if (BATTERY_ZONE_TOKENS.any { n.contains(it) }) return null
    return classifyCategory(n)
}

/** EXTRA_TEMPERATURE приходит в десятых долях °C. */
internal fun batteryTempC(extraTenths: Int): Double = extraTenths / 10.0

/** Семейство SoC — влияет на строку диагностики; сами шаблоны уже универсальны. */
enum class SocFamily { QUALCOMM, MEDIATEK, GOOGLE_TENSOR, OTHER }

internal fun socFamilyLabel(family: SocFamily): String = when (family) {
    SocFamily.QUALCOMM -> "Qualcomm (Snapdragon)"
    SocFamily.MEDIATEK -> "MediaTek (Dimensity/Helio)"
    SocFamily.GOOGLE_TENSOR -> "Google Tensor"
    SocFamily.OTHER -> "не определено"
}

/** Идентификация по Build.SOC_* (API 36+) либо /proc/cpuinfo; на старых ОС — OTHER. */
internal fun detectSocFamily(socInfo: String): SocFamily {
    val n = socInfo.lowercase()
    return when {
        n.contains("mediatek") || n.contains("dimensity") || n.contains("helio") ||
            Regex("\\bmt\\d{4}\\b").containsMatchIn(n) -> SocFamily.MEDIATEK
        n.contains("tensor") || n.contains("goose") || n.contains("zumipro") || n.contains("caiman") ||
            Regex("\\bgs\\d{3}\\b").containsMatchIn(n) -> SocFamily.GOOGLE_TENSOR
        n.contains("qualcomm") || n.contains("qcom") || n.contains("snapdragon") ||
            n.contains("kona") || n.contains("lito") ||
            Regex("\\b(?:sm|msm|sdm)\\d{3,4}\\b").containsMatchIn(n) -> SocFamily.QUALCOMM
        else -> SocFamily.OTHER
    }
}

/** Зоны отдают то миллиградусы (45000), то целые градусы (45). */
internal fun zoneTempC(raw: Double): Double = if (abs(raw) >= 1000.0) raw / 1000.0 else raw

/** Разумный диапазон показаний температуры: отбрасываем мусор и sentinel-значения. */
private fun plausibleTemp(v: Double): Boolean = v.isFinite() && v > -40.0 && v < 120.0

/** Человекочитаемая метка PowerManager.getCurrentThermalStatus (API 29+). */
internal fun thermalStatusLabel(status: Int): String = when (status) {
    0 -> "Норма"
    1 -> "Лёгкий нагрев"
    2 -> "Умеренный нагрев"
    3 -> "Сильный нагрев"
    4 -> "Критический нагрев"
    5 -> "Аварийное состояние"
    6 -> "Готовность к thermal shutdown"
    else -> "Неизвестно"
}

/**
 * Источник температур — два пути плюс диагностика того, что device реально отдаёт:
 * 1) Sensor API: температурные сенсоры (type 331 CPU_HOTSPOT, имена temp/thermal/hot;
 *    одно событие может нести температуры нескольких кластеров);
 * 2) тепловые зоны /sys/class/thermal и /sys/devices/virtual/thermal — названия
 *    файла type прямо указывают узел (cpu/gpu/mem); доступ зависит от SELinux
 *    прошивки, поэтому отдельно считаем, что найдено, а что читается.
 * Плюс температура аккумулятора (есть всегда) и тепловой статус (Android 10+).
 * При нескольких источниках одной категории показывается самый горячий.
 */
class ThermalCollector(context: Context) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val powerManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(PowerManager::class.java)
        } else {
            null
        }

    private class ZoneSource(val tempFile: File, val label: String) {
        var showedTemp = false
    }

    private val trackedSensors = mutableMapOf<Sensor, String>()
    private val zoneSources = mutableListOf<ZoneSource>()
    private val lastC = mutableMapOf<String, Double>()
    private val peakC = mutableMapOf<String, Double>()
    private var batteryPeakC = Double.NaN

    // Счётчики диагностики.
    private var zonesFound = 0
    private var zonesTyped = 0
    private val zoneTypeSamples = mutableListOf<String>()
    private val sensorCandidates = mutableListOf<String>()
    private var discovered = false
    private var measuring = false
    private var socFamilyCache: SocFamily? = null

    private fun socFamily(): SocFamily = socFamilyCache ?: run {
        val build = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" }.getOrDefault("")
        } else {
            ""
        }
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            .lines()
            .filter { line ->
                val l = line.lowercase()
                l.startsWith("model") || l.startsWith("hardware") || l.startsWith("vendor_id") ||
                    l.contains("qualcomm") || l.contains("mediatek") || l.contains("tensor")
            }
            .joinToString(" ")
        detectSocFamily("$build $cpuinfo").also { socFamilyCache = it }
    }

    private fun zonesShowingTempCount(): Int = zoneSources.count { it.showedTemp }

    private fun mergeTemp(label: String, v: Double) {
        if (!plausibleTemp(v)) return
        val prev = lastC[label]
        val best = if (prev == null || v > prev) v else prev
        lastC[label] = best
        peakC[label] = max(peakC[label] ?: best, best)
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val label = trackedSensors[event.sensor] ?: return
            for (v in event.values) mergeTemp(label, v.toDouble())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Стресс-режим открыт, тест ещё не идёт: наблюдаем текущие температуры, но
     * максимумы предыдущего прогона сбрасываем — в новом режиме они не имеют
     * смысла, а «макс за прогон» без прогона показывать нечестно.
     */
    fun observeIdle() {
        reset()
        ensureDiscovery()
        registerSensorListeners()
    }

    fun start() {
        reset()
        measuring = true
        ensureDiscovery()
        registerSensorListeners()
    }

    private fun registerSensorListeners() {
        for (sensor in trackedSensors.keys) {
            runCatching {
                sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private fun ensureDiscovery() {
        if (discovered) return
        val sensors = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        for (s in sensors) {
            if (isTempCandidateName(s.name) && sensorCandidates.size < 8) {
                sensorCandidates.add("${s.name} (type ${s.type})")
            }
            val label = classifyTempSensor(s.name, s.type) ?: continue
            trackedSensors[s] = label
        }
        // Обе распространённые раскладки каталога тепловых зон; дубликаты (symlink
        // /sys/class/thermal -> /devices/virtual/thermal) убираем по canonicalPath.
        val seen = HashSet<String>()
        for (root in listOf("/sys/class/thermal", "/sys/devices/virtual/thermal")) {
            val dirs = runCatching { File(root).listFiles() }.getOrNull() ?: continue
            for (zone in dirs) {
                if (!zone.name.startsWith("thermal_zone")) continue
                val canonical = runCatching { zone.canonicalPath }.getOrDefault(zone.absolutePath)
                if (!seen.add(canonical)) continue
                zonesFound += 1
                val zoneType = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                    ?: continue
                zonesTyped += 1
                if (zoneTypeSamples.size < 8) zoneTypeSamples.add(zoneType)
                val label = classifyZoneType(zoneType) ?: continue
                zoneSources.add(ZoneSource(File(zone, "temp"), label))
            }
        }
        discovered = true
    }

    fun stop() {
        // Данные прогона сохраняем: после «Стоп» панель и отчёт показывают последний
        // снимок. Обнуление — только по reset() или при новом старте.
        runCatching { sensorManager?.unregisterListener(listener) }
    }

    fun reset() {
        // Сбрасываем измерения и статус прогона; обнаруженные зоны/сенсоры и
        // диагностика остаются — это свойства устройства, а не прогона.
        measuring = false
        lastC.clear()
        peakC.clear()
        batteryPeakC = Double.NaN
        zoneSources.forEach { it.showedTemp = false }
    }

    private fun batteryTempNow(): Double = runCatching {
        // null-приёмник: sticky-интент отдаётся сразу, без собственного ресивера.
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths < 0) Double.NaN else batteryTempC(tenths)
    }.getOrDefault(Double.NaN)

    private fun pollZones() {
        for (z in zoneSources) {
            val raw = runCatching { z.tempFile.readText().trim().toDoubleOrNull() }.getOrNull()
                ?: continue
            val c = zoneTempC(raw)
            if (plausibleTemp(c)) z.showedTemp = true
            mergeTemp(z.label, c)
        }
    }

    fun snapshot(): ThermalSnapshot {
        pollZones()
        val readings = mutableListOf<ThermalReading>()
        val battery = batteryTempNow()
        if (plausibleTemp(battery)) {
            batteryPeakC = if (batteryPeakC.isNaN() || battery > batteryPeakC) battery else batteryPeakC
            readings.add(ThermalReading("Аккумулятор", battery, batteryPeakC))
        }
        for (label in listOf("CPU", "GPU", "RAM")) {
            val v = lastC[label]
            if (v != null) readings.add(ThermalReading(label, v, peakC[label] ?: v))
        }
        val statusLabel = runCatching {
            powerManager?.getCurrentThermalStatus()?.let { thermalStatusLabel(it) }
        }.getOrNull()
        return ThermalSnapshot(
            readings = readings,
            hasSensorData = lastC.isNotEmpty(),
            thermalStatusLabel = statusLabel,
            diagnostics = ThermalDiagnostics(
                zonesFound = zonesFound,
                zonesTyped = zonesTyped,
                zonesShowingTemp = zonesShowingTempCount(),
                zoneTypeSamples = zoneTypeSamples.toList(),
                sensorCandidates = sensorCandidates.toList(),
                socFamilyLabel = socFamilyLabel(socFamily()),
            ),
            runMeasured = measuring,
        )
    }
}
