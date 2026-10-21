package com.majestic.benchjitter.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StatFs
import android.view.Display
import android.widget.Toast
import com.majestic.benchjitter.engine.CpuCollector
import com.majestic.benchjitter.engine.CpuSnapshot
import com.majestic.benchjitter.engine.FrameCollector
import com.majestic.benchjitter.engine.RamSnapshot
import com.majestic.benchjitter.engine.RomSnapshot
import com.majestic.benchjitter.engine.ThermalSnapshot
import com.majestic.benchjitter.engine.WakeCollector
import com.majestic.benchjitter.stats.StatsSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Форматирование величин (базовые значения в микросекундах) ----

fun fmtUs(v: Double): String = when {
    v.isNaN() -> "—"
    v >= 1000.0 -> String.format(Locale.US, "%.2f мс", v / 1000.0)
    else -> String.format(Locale.US, "%.1f мкс", v)
}

fun fmtHz(nominalUs: Double): String =
    if (nominalUs <= 0.0 || nominalUs.isNaN()) "—"
    else String.format(Locale.US, "%.1f Гц", 1_000_000.0 / nominalUs)

fun fmtMs(ms: Double): String =
    if (ms.isNaN()) "—" else String.format(Locale.US, "%.1f мс", ms)

fun fmtDuration(sec: Double): String =
    if (sec < 60.0) String.format(Locale.US, "%.0f с", sec)
    else String.format(Locale.US, "%d мин %02d с", (sec / 60).toLong(), (sec % 60).toLong())

fun fmtFps(fps: Double): String =
    if (fps.isNaN() || !fps.isFinite()) "—" else String.format(Locale.US, "%.1f", fps)

fun fmtMops(mops: Double): String = when {
    mops.isNaN() || mops <= 0.0 -> "—"
    mops >= 1000.0 -> String.format(Locale.US, "%.2f Гоп/с", mops / 1000.0)
    else -> String.format(Locale.US, "%.1f Моп/с", mops)
}

fun fmtMB(mb: Double): String = when {
    mb.isNaN() || mb <= 0.0 -> "—"
    mb >= 1024.0 -> String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
    else -> String.format(Locale.US, "%.0f МБ", mb)
}

fun fmtMBps(mbps: Double): String = when {
    mbps.isNaN() || mbps <= 0.0 -> "—"
    mbps >= 1024.0 -> String.format(Locale.US, "%.2f ГБ/с", mbps / 1024.0)
    else -> String.format(Locale.US, "%.0f МБ/с", mbps)
}

fun fmtCount(v: Long): String = String.format(Locale.US, "%,d", v).replace(',', ' ')

fun fmtPct(pct: Double): String =
    if (pct.isNaN()) "—" else String.format(Locale.US, "%.1f%%", pct)

fun fmtC(v: Double): String =
    if (v.isNaN()) "—" else String.format(Locale.US, "%.1f°C", v)

fun fmtGB(gb: Double): String =
    if (gb.isNaN() || gb <= 0.0) "—" else String.format(Locale.US, "%.1f ГБ", gb)

/** Скорость в МБ/с по среднему времени куска (мкс). */
fun mbpsOf(chunkBytes: Int, meanUs: Double): Double =
    if (meanUs <= 0.0 || meanUs.isNaN()) Double.NaN
    else chunkBytes * 1_000_000.0 / 1_048_576.0 / meanUs

// ---- Текстовый отчёт (для сравнения прошивок/устройств) ----

// ---- Информация об устройстве (режим «Устройство») ----

data class CpuCoreInfo(val core: Int, val maxMHz: Int)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val cores: List<CpuCoreInfo>,
    val totalRamMB: Long,
    val storageTotalGB: Double,
    val storageFreeGB: Double,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshHz: Double,
)

/** Группировка ядер по максимальной частоте: [(количество, МГц)], по убыванию частоты. */
internal fun groupCoreFreqs(cores: List<CpuCoreInfo>): List<Pair<Int, Int>> =
    cores.groupingBy { it.maxMHz }.eachCount().entries
        .sortedByDescending { it.key }
        .map { it.value to it.key }

fun collectDeviceInfo(context: Context): DeviceInfo {
    val cores = mutableListOf<CpuCoreInfo>()
    runCatching {
        File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.forEach { dir ->
                val idx = dir.name.removePrefix("cpu").toIntOrNull() ?: return@forEach
                val khz = runCatching {
                    File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toIntOrNull()
                }.getOrNull()
                if (khz != null && khz > 0) cores.add(CpuCoreInfo(idx, khz / 1000))
            }
    }
    cores.sortBy { it.core }
    val totalRamMB = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        mi.totalMem / (1024L * 1024L)
    }.getOrDefault(0L)
    val (storageTotalGB, storageFreeGB) = runCatching {
        val sf = StatFs(context.cacheDir.absolutePath)
        sf.totalBytes / 1_000_000_000.0 to sf.availableBytes / 1_000_000_000.0
    }.getOrDefault(0.0 to 0.0)
    val metrics = context.resources.displayMetrics
    val refreshHz = runCatching {
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).mode.refreshRate.toDouble()
    }.getOrDefault(0.0)
    return DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        cores = cores.toList(),
        totalRamMB = totalRamMB,
        storageTotalGB = storageTotalGB,
        storageFreeGB = storageFreeGB,
        widthPx = metrics.widthPixels,
        heightPx = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshHz = refreshHz,
    )
}

data class ReportParams(
    val mode: BenchMode,
    val durationSec: Double,
    val snapshot: StatsSnapshot,
    val measuredFps: Double,
    val frame: FrameCollector? = null,
    val wake: WakeCollector? = null,
    val priorityLabel: String = "",
    val cpu: CpuSnapshot? = null,
    val ram: RamSnapshot? = null,
    val rom: RomSnapshot? = null,
    val gpuMs: StatsSnapshot? = null,
    val gpuSupported: Boolean = false,
    val thermal: ThermalSnapshot? = null,
    val device: DeviceInfo? = null,
)

private fun StringBuilder.line(s: String) {
    append(s).append('\n')
}

private fun StringBuilder.line() {
    append('\n')
}

/** Одна метрика — одна строка, имя с большой буквы. */
private fun StringBuilder.value(name: String, v: Any) {
    line("$name: $v")
}

/** Блок распределения по микросекундным измерениям: по одному значению на строку. */
private fun StringBuilder.durationsBlock(snap: StatsSnapshot) {
    value("Среднее (mean)", fmtUs(snap.meanUs))
    value("Стандартное отклонение (σ)", fmtUs(snap.stdDevUs))
    value("Минимум", fmtUs(snap.minUs))
    value("Максимум", fmtUs(snap.maxUs))
    value("Медиана (p50)", fmtUs(snap.p50Us))
    value("Перцентиль p90", fmtUs(snap.p90Us))
    value("Перцентиль p95", fmtUs(snap.p95Us))
    value("Перцентиль p99", fmtUs(snap.p99Us))
    line()
}

private fun StringBuilder.cpuBlock(c: CpuSnapshot) {
    val k = CpuCollector.QUANTUM_OPS / 1_000_000.0
    value("Потоков", c.threads)
    value("Выполнено операций", fmtCount(c.totalOpsDone))
    value("Средний темп (mean)", fmtMops(c.meanQps * k))
    value("Разброс (σ)", fmtMops(c.stdDevQps * k))
    value("Относительный разброс", fmtPct(if (c.meanQps > 0) c.stdDevQps / c.meanQps * 100.0 else Double.NaN))
    value("Медиана темпа (p50)", fmtMops(c.p50Qps * k))
    value("Перцентиль p99", fmtMops(c.p99Qps * k))
    line("Единица: Моп/с — миллионы float-операций в секунду суммарно по всем потокам")
    line()
}

private fun StringBuilder.ramBlock(r: RamSnapshot) {
    value("Удерживается", fmtMB(r.allocatedMB))
    value("Лимит кучи процесса (оценка)", fmtMB(r.heapCapMB))
    value("Размер чанка", "${r.chunkBytes / 1048576} МБ")
    value("Время заполнения чанка (mean)", fmtUs(r.fillMeanUs))
    value("Разброс времени чанка (σ)", fmtUs(r.fillStdDevUs))
    value("Худшее время чанка (p99)", fmtUs(r.fillP99Us))
    value("Скорость заполнения", fmtMBps(r.fillMBps))
    value("Потолок достигнут", if (r.hitLimit) "да" else "нет")
    line()
}

private fun tempMetricName(label: String): String =
    if (label == "Аккумулятор") "Температура аккумулятора" else "Температура $label"

private fun StringBuilder.thermalBlock(t: ThermalSnapshot) {
    line("Температуры устройства:")
    t.readings.forEach {
        val v = if (t.runMeasured) {
            "${fmtC(it.valueC)} · макс за прогон ${fmtC(it.maxC)}"
        } else {
            fmtC(it.valueC)
        }
        value(tempMetricName(it.label), v)
    }
    if (!t.hasSensorData) {
        line("Температуры CPU/GPU/RAM: устройство не публикует ни температурных сенсоров, ни системных тепловых зон — доступна только батарея")
    }
    value("Тепловой статус устройства", t.thermalStatusLabel ?: "недоступен (требуется Android 10+)")
    val d = t.diagnostics
    line("Диагностика нагрева:")
    value("Семейство SoC", d.socFamilyLabel)
    value(
        "Тепловые зоны",
        "найдено ${d.zonesFound}, типов прочитано ${d.zonesTyped}, показывают температуру ${d.zonesShowingTemp}",
    )
    if (d.zoneTypeSamples.isNotEmpty()) {
        value("Примеры типов зон", d.zoneTypeSamples.joinToString(", "))
    }
    value(
        "Сенсоры-кандидаты устройства",
        if (d.sensorCandidates.isEmpty()) "нет" else d.sensorCandidates.joinToString("; "),
    )
    line()
}

fun buildReport(p: ReportParams): String {
    val snap = p.snapshot
    val isUsMode = p.mode == BenchMode.FRAMES || p.mode == BenchMode.WAKE || p.mode == BenchMode.GPU
    val sb = StringBuilder()
    sb.line("=== Benchmark HUB 1.4.0 ===")
    val socPart = if (Build.VERSION.SDK_INT >= 36) {
        runCatching { " · SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" }.getOrDefault("")
    } else {
        ""
    }
    sb.value("Устройство", "${Build.MANUFACTURER} ${Build.MODEL}$socPart · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.value("Дата", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    if (p.mode != BenchMode.DEVICE) {
        sb.value("Длительность", fmtDuration(p.durationSec))
    }
    if (isUsMode) {
        sb.value("Сэмплов", "${snap.totalCount} (окно анализа: ${snap.windowCount})")
    }
    sb.line()
    // название режима — то же, что заголовки на экране (BenchMode.label)
    sb.value("Режим", p.mode.label)
    when (p.mode) {
        BenchMode.FRAMES -> {
            val f = p.frame
            sb.value("Номинал экрана", "${fmtUs(f?.nominalPeriodUs ?: Double.NaN)} (${fmtHz(f?.nominalPeriodUs ?: 0.0)})")
            sb.value("Замерено FPS", fmtFps(p.measuredFps))
            sb.value("Пропущено кадров", f?.missedFrames ?: 0)
            sb.value("Гэпов после паузы", f?.resyncGaps ?: 0)
            sb.line()
            sb.durationsBlock(snap)
        }
        BenchMode.WAKE -> {
            val w = p.wake
            sb.value("Интервал пробуждения", fmtMs((w?.intervalUs ?: 0.0) / 1000.0))
            sb.value("Приоритет потока", p.priorityLabel)
            sb.value("Просрочено меток", w?.missedDeadlines ?: 0)
            sb.line()
            sb.durationsBlock(snap)
        }
        BenchMode.CPU -> {
            val c = p.cpu
            if (c != null && c.hasData) sb.cpuBlock(c)
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.RAM -> {
            val r = p.ram
            if (r != null && r.hasData) sb.ramBlock(r)
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.COMBO -> {
            val c = p.cpu
            val r = p.ram
            sb.line("=== Часть CPU ===")
            if (c != null && c.hasData) sb.cpuBlock(c)
            sb.line("=== Часть RAM ===")
            if (r != null && r.hasData) sb.ramBlock(r)
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
            sb.line("Сравнение: держит ли система темп CPU, пока RAM-поток набивает кучу до потолка")
            sb.line()
        }
        BenchMode.GPU -> {
            val f = p.frame
            sb.value("Замерено FPS тяжёлой сцены", fmtFps(p.measuredFps))
            sb.value("Пропущено кадров", f?.missedFrames ?: 0)
            sb.line()
            sb.line("Метрики интервалов кадров под GPU-нагрузкой:")
            sb.durationsBlock(snap)
            val g = p.gpuMs
            if (p.gpuSupported && g != null && !g.isEmpty) {
                sb.line("Метрики GPU-стадии кадра (FrameMetrics GPU_DURATION):")
                sb.durationsBlock(g)
            } else {
                sb.value("GPU-стадия кадра", "недоступна: FrameMetrics-подписка не принята устройством")
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.ROM -> {
            val r = p.rom
            if (r != null && r.hasData) {
                val chunk = r.ioChunkKB * 1024
                sb.value("Файл", "${r.fileSizeMB.toInt()} МБ, куски ${r.ioChunkKB} КБ, fsync на файле")
                sb.value("Проходов завершено", r.passesDone)
                val ws = r.writeSnap
                sb.value("Запись, средняя скорость", fmtMBps(mbpsOf(chunk, ws.meanUs)))
                sb.value("Запись, медиана (p50)", fmtMBps(mbpsOf(chunk, ws.p50Us)))
                sb.value("Запись, перцентиль p95", fmtMBps(mbpsOf(chunk, ws.p95Us)))
                sb.value("Запись, перцентиль p99", fmtMBps(mbpsOf(chunk, ws.p99Us)))
                sb.value("Запись, разброс времени куска (σ)", fmtUs(ws.stdDevUs))
                val rs = r.readSnap
                sb.value("Чтение, средняя скорость (через кэш ФС)", fmtMBps(mbpsOf(chunk, rs.meanUs)))
                if (r.storageFull) sb.value("Внимание", "свободное место на разделе заканчивался")
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.DEVICE -> {
            val dev = p.device
            if (dev != null) {
                if (dev.cores.isNotEmpty()) {
                    sb.value("Ядер CPU", dev.cores.size.toString())
                    groupCoreFreqs(dev.cores).forEach { (count, mhz) ->
                        sb.value("Ядро × $count (максимум)", "$mhz МГц")
                    }
                } else {
                    sb.value("Ядер CPU", "не опубликованы")
                }
                sb.value("Оперативная память (видимая приложению)", fmtMB(dev.totalRamMB.toDouble()))
                sb.value("Хранилище (раздел данных)", "свободно ${fmtGB(dev.storageFreeGB)} из ${fmtGB(dev.storageTotalGB)}")
                val refreshLine = if (dev.refreshHz > 0.0) {
                    " · ${String.format(Locale.US, "%.0f", dev.refreshHz)} Гц"
                } else {
                    ""
                }
                sb.value("Экран", "${dev.widthPx}×${dev.heightPx} · ${dev.densityDpi} dpi$refreshLine")
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
    }
    if (isUsMode) {
        sb.append("Mean/σ/перцентили — по последним ${maxOf(snap.windowCount, 1)} измерениям; min/max — накопительные за прогон")
    }
    return sb.toString()
}

fun copyReportToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("benchmark-hub-report", text))
    Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
}

fun shareReport(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
}
