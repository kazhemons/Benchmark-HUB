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
import com.majestic.benchjitter.i18n.tr
import com.majestic.benchjitter.stats.StatsSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Форматирование величин (базовые значения в микросекундах) ----

fun fmtUs(v: Double): String = when {
    v.isNaN() -> "—"
    v >= 1000.0 -> String.format(Locale.US, tr("%.2f мс", "%.2f ms"), v / 1000.0)
    else -> String.format(Locale.US, tr("%.1f мкс", "%.1f µs"), v)
}

fun fmtHz(nominalUs: Double): String =
    if (nominalUs <= 0.0 || nominalUs.isNaN()) "—"
    else String.format(Locale.US, tr("%.1f Гц", "%.1f Hz"), 1_000_000.0 / nominalUs)

fun fmtMs(ms: Double): String =
    if (ms.isNaN()) "—" else String.format(Locale.US, tr("%.1f мс", "%.1f ms"), ms)

fun fmtDuration(sec: Double): String =
    if (sec < 60.0) String.format(Locale.US, tr("%.0f с", "%.0f s"), sec)
    else String.format(Locale.US, tr("%d мин %02d с", "%d min %02d s"), (sec / 60).toLong(), (sec % 60).toLong())

fun fmtFps(fps: Double): String =
    if (fps.isNaN() || !fps.isFinite()) "—" else String.format(Locale.US, "%.1f", fps)

fun fmtMops(mops: Double): String = when {
    mops.isNaN() || mops <= 0.0 -> "—"
    mops >= 1000.0 -> String.format(Locale.US, tr("%.2f Гоп/с", "%.2f Gops/s"), mops / 1000.0)
    else -> String.format(Locale.US, tr("%.1f Моп/с", "%.1f Mops/s"), mops)
}

fun fmtMB(mb: Double): String = when {
    mb.isNaN() || mb <= 0.0 -> "—"
    mb >= 1024.0 -> String.format(Locale.US, tr("%.2f ГБ", "%.2f GB"), mb / 1024.0)
    else -> String.format(Locale.US, tr("%.0f МБ", "%.0f MB"), mb)
}

fun fmtMBps(mbps: Double): String = when {
    mbps.isNaN() || mbps <= 0.0 -> "—"
    mbps >= 1024.0 -> String.format(Locale.US, tr("%.2f ГБ/с", "%.2f GB/s"), mbps / 1024.0)
    else -> String.format(Locale.US, tr("%.0f МБ/с", "%.0f MB/s"), mbps)
}

fun fmtCount(v: Long): String = String.format(Locale.US, "%,d", v).replace(',', ' ')

fun fmtPct(pct: Double): String =
    if (pct.isNaN()) "—" else String.format(Locale.US, "%.1f%%", pct)

fun fmtC(v: Double): String =
    if (v.isNaN()) "—" else String.format(Locale.US, "%.1f°C", v)

fun fmtGB(gb: Double): String =
    if (gb.isNaN() || gb <= 0.0) "—" else String.format(Locale.US, tr("%.1f ГБ", "%.1f GB"), gb)

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
    value(tr("Среднее (mean)", "Mean"), fmtUs(snap.meanUs))
    value(tr("Стандартное отклонение (σ)", "Standard deviation (σ)"), fmtUs(snap.stdDevUs))
    value(tr("Минимум", "Minimum"), fmtUs(snap.minUs))
    value(tr("Максимум", "Maximum"), fmtUs(snap.maxUs))
    value(tr("Медиана (p50)", "Median (p50)"), fmtUs(snap.p50Us))
    value(tr("Перцентиль p90", "p90 percentile"), fmtUs(snap.p90Us))
    value(tr("Перцентиль p95", "p95 percentile"), fmtUs(snap.p95Us))
    value(tr("Перцентиль p99", "p99 percentile"), fmtUs(snap.p99Us))
    line()
}

private fun StringBuilder.cpuBlock(c: CpuSnapshot) {
    val k = CpuCollector.QUANTUM_OPS / 1_000_000.0
    value(tr("Потоков", "Threads"), c.threads)
    value(tr("Выполнено операций", "Operations completed"), fmtCount(c.totalOpsDone))
    value(tr("Средний темп (mean)", "Mean rate"), fmtMops(c.meanQps * k))
    value(tr("Разброс (σ)", "Spread (σ)"), fmtMops(c.stdDevQps * k))
    value(tr("Относительный разброс", "Relative spread"), fmtPct(if (c.meanQps > 0) c.stdDevQps / c.meanQps * 100.0 else Double.NaN))
    value(tr("Медиана темпа (p50)", "Median rate (p50)"), fmtMops(c.p50Qps * k))
    value(tr("Перцентиль p99", "p99 percentile"), fmtMops(c.p99Qps * k))
    value(tr("Единица", "Unit"), tr("Моп/с — миллионы float-операций в секунду суммарно по всем потокам", "Mops/s — millions of float operations per second summed across all threads"))
    line()
}

private fun StringBuilder.ramBlock(r: RamSnapshot) {
    value(tr("Удерживается", "Held"), fmtMB(r.allocatedMB))
    value(tr("Лимит кучи процесса (оценка)", "Process heap limit (estimate)"), fmtMB(r.heapCapMB))
    value(tr("Размер чанка", "Chunk size"), tr("${r.chunkBytes / 1048576} МБ", "${r.chunkBytes / 1048576} MB"))
    value(tr("Время заполнения чанка (mean)", "Chunk fill time (mean)"), fmtUs(r.fillMeanUs))
    value(tr("Разброс времени чанка (σ)", "Chunk time spread (σ)"), fmtUs(r.fillStdDevUs))
    value(tr("Худшее время чанка (p99)", "Worst chunk time (p99)"), fmtUs(r.fillP99Us))
    value(tr("Скорость заполнения", "Fill speed"), fmtMBps(r.fillMBps))
    value(tr("Потолок достигнут", "Limit reached"), if (r.hitLimit) tr("да", "yes") else tr("нет", "no"))
    line()
}

private fun tempMetricName(label: String): String = when (label) {
    "Аккумулятор", "Battery" -> tr("Температура аккумулятора", "Battery temperature")
    "CPU" -> tr("Температура CPU", "CPU temperature")
    "GPU" -> tr("Температура GPU", "GPU temperature")
    "RAM" -> tr("Температура RAM", "RAM temperature")
    else -> tr("Температура $label", "$label temperature")
}

/** Метки PowerManager приходят из ThermalCollector по-русски; переводим их здесь. */
private fun localizedThermalStatus(label: String): String = when (label) {
    "Норма" -> tr("Норма", "Normal")
    "Лёгкий нагрев" -> tr("Лёгкий нагрев", "Mild warming")
    "Умеренный нагрев" -> tr("Умеренный нагрев", "Moderate warming")
    "Сильный нагрев" -> tr("Сильный нагрев", "Strong heating")
    "Критический нагрев" -> tr("Критический нагрев", "Critical heating")
    "Аварийное состояние" -> tr("Аварийное состояние", "Emergency state")
    "Готовность к thermal shutdown" -> tr("Готовность к thermal shutdown", "Thermal shutdown imminent")
    "Неизвестно" -> tr("Неизвестно", "Unknown")
    else -> label
}

private fun StringBuilder.thermalBlock(t: ThermalSnapshot) {
    line(tr("Температуры устройства:", "Device temperatures:"))
    t.readings.forEach {
        val v = if (t.runMeasured) {
            tr("${fmtC(it.valueC)} · макс за прогон ${fmtC(it.maxC)}", "${fmtC(it.valueC)} · peak during run ${fmtC(it.maxC)}")
        } else {
            fmtC(it.valueC)
        }
        value(tempMetricName(it.label), v)
    }
    if (!t.hasSensorData) {
        line(tr("Температуры CPU/GPU/RAM: устройство не публикует ни температурных сенсоров, ни системных тепловых зон — доступна только батарея", "CPU/GPU/RAM temperatures: the device publishes neither temperature sensors nor system thermal zones — only battery is available"))
    }
    value(
        tr("Тепловой статус устройства", "Device thermal status"),
        t.thermalStatusLabel?.let { localizedThermalStatus(it) } ?: tr("недоступен (требуется Android 10+)", "unavailable (Android 10+ required)"),
    )
    val d = t.diagnostics
    line(tr("Диагностика нагрева:", "Thermal diagnostics:"))
    value(tr("Семейство SoC", "SoC family"), if (d.socFamilyLabel == "не определено") tr("не определено", "unknown") else d.socFamilyLabel)
    value(
        tr("Тепловые зоны", "Thermal zones"),
        tr("найдено ${d.zonesFound}, типов прочитано ${d.zonesTyped}, показывают температуру ${d.zonesShowingTemp}", "found ${d.zonesFound}, typed ${d.zonesTyped}, reporting temperature ${d.zonesShowingTemp}"),
    )
    if (d.zoneTypeSamples.isNotEmpty()) {
        value(tr("Примеры типов зон", "Sample zone names"), d.zoneTypeSamples.joinToString(", "))
    }
    value(
        tr("Сенсоры-кандидаты устройства", "Candidate sensors"),
        if (d.sensorCandidates.isEmpty()) tr("нет", "none") else d.sensorCandidates.joinToString("; "),
    )
    line()
}

fun buildReport(p: ReportParams): String {
    val snap = p.snapshot
    val isUsMode = p.mode == BenchMode.FRAMES || p.mode == BenchMode.WAKE || p.mode == BenchMode.GPU
    val sb = StringBuilder()
    sb.line("=== Benchmark HUB 1.5.0 ===")
    val socPart = if (Build.VERSION.SDK_INT >= 36) {
        runCatching { " · SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" }.getOrDefault("")
    } else {
        ""
    }
    sb.value(tr("Устройство", "Device"), "${Build.MANUFACTURER} ${Build.MODEL}$socPart · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.value(tr("Дата", "Date"), SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    if (p.mode != BenchMode.DEVICE) {
        sb.value(tr("Длительность", "Duration"), fmtDuration(p.durationSec))
    }
    if (isUsMode) {
        sb.value(tr("Сэмплов", "Samples"), tr("${snap.totalCount} (окно анализа: ${snap.windowCount})", "${snap.totalCount} (analysis window: ${snap.windowCount})"))
    }
    sb.line()
    // название режима — то же, что заголовки на экране (BenchMode.label)
    sb.value(tr("Режим", "Mode"), p.mode.label)
    when (p.mode) {
        BenchMode.FRAMES -> {
            val f = p.frame
            sb.value(tr("Номинал экрана", "Display nominal"), "${fmtUs(f?.nominalPeriodUs ?: Double.NaN)} (${fmtHz(f?.nominalPeriodUs ?: 0.0)})")
            sb.value(tr("Замерено FPS", "Measured FPS"), fmtFps(p.measuredFps))
            sb.value(tr("Пропущено кадров", "Missed frames"), f?.missedFrames ?: 0)
            sb.value(tr("Гэпов после паузы", "Gaps after pause"), f?.resyncGaps ?: 0)
            sb.line()
            sb.durationsBlock(snap)
        }
        BenchMode.WAKE -> {
            val w = p.wake
            sb.value(tr("Интервал пробуждения", "Wake interval"), fmtMs((w?.intervalUs ?: 0.0) / 1000.0))
            sb.value(tr("Приоритет потока", "Thread priority"), p.priorityLabel)
            sb.value(tr("Просрочено меток", "Missed deadlines"), w?.missedDeadlines ?: 0)
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
            sb.line(tr("=== Часть CPU ===", "=== CPU part ==="))
            if (c != null && c.hasData) sb.cpuBlock(c)
            sb.line(tr("=== Часть RAM ===", "=== RAM part ==="))
            if (r != null && r.hasData) sb.ramBlock(r)
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
            sb.line(tr("Сравнение: держит ли система темп CPU, пока RAM-поток набивает кучу до потолка", "Comparison: whether the system sustains CPU rate while the RAM stream fills the heap to its limit"))
            sb.line()
        }
        BenchMode.GPU -> {
            val f = p.frame
            sb.value(tr("Замерено FPS тяжёлой сцены", "Measured FPS of the heavy scene"), fmtFps(p.measuredFps))
            sb.value(tr("Пропущено кадров", "Missed frames"), f?.missedFrames ?: 0)
            sb.line()
            sb.line(tr("Метрики интервалов кадров под GPU-нагрузкой:", "Frame interval metrics under GPU load:"))
            sb.durationsBlock(snap)
            val g = p.gpuMs
            if (p.gpuSupported && g != null && !g.isEmpty) {
                sb.line(tr("Метрики GPU-стадии кадра (FrameMetrics GPU_DURATION):", "Frame GPU-stage metrics (FrameMetrics GPU_DURATION):"))
                sb.durationsBlock(g)
            } else {
                sb.value(tr("GPU-стадия кадра", "GPU frame stage"), tr("недоступна: FrameMetrics-подписка не принята устройством", "unavailable: the FrameMetrics subscription was not accepted by the device"))
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.ROM -> {
            val r = p.rom
            if (r != null && r.hasData) {
                val chunk = r.ioChunkKB * 1024
                sb.value(tr("Файл", "File"), tr("${r.fileSizeMB.toInt()} МБ, куски ${r.ioChunkKB} КБ, fsync на файле", "${r.fileSizeMB.toInt()} MB, ${r.ioChunkKB} KB chunks, fsync on the file"))
                sb.value(tr("Проходов завершено", "Completed passes"), r.passesDone)
                val ws = r.writeSnap
                sb.value(tr("Запись, средняя скорость", "Write speed (mean)"), fmtMBps(mbpsOf(chunk, ws.meanUs)))
                sb.value(tr("Запись, медиана (p50)", "Write speed (median p50)"), fmtMBps(mbpsOf(chunk, ws.p50Us)))
                sb.value(tr("Запись, перцентиль p95", "Write speed (p95)"), fmtMBps(mbpsOf(chunk, ws.p95Us)))
                sb.value(tr("Запись, перцентиль p99", "Write speed (p99)"), fmtMBps(mbpsOf(chunk, ws.p99Us)))
                sb.value(tr("Запись, разброс времени куска (σ)", "Write chunk time spread (σ)"), fmtUs(ws.stdDevUs))
                val rs = r.readSnap
                sb.value(tr("Чтение, средняя скорость (через кэш ФС)", "Read speed (via filesystem cache)"), fmtMBps(mbpsOf(chunk, rs.meanUs)))
                if (r.storageFull) sb.value(tr("Внимание", "Warning"), tr("свободное место на разделе заканчивался", "free space on the partition was running out"))
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
        BenchMode.DEVICE -> {
            val dev = p.device
            if (dev != null) {
                if (dev.cores.isNotEmpty()) {
                    sb.value(tr("Ядер CPU", "CPU cores"), dev.cores.size.toString())
                    groupCoreFreqs(dev.cores).forEach { (count, mhz) ->
                        val freqValue = tr("$mhz МГц", "$mhz MHz")
                        sb.value(tr("Ядро × $count (максимум)", "×$count cores (max frequency)"), freqValue)
                    }
                } else {
                    sb.value(tr("Ядер CPU", "CPU cores"), tr("не опубликованы", "not published"))
                }
                sb.value(tr("Оперативная память (видимая приложению)", "RAM (visible to the app)"), fmtMB(dev.totalRamMB.toDouble()))
                sb.value(tr("Хранилище (раздел данных)", "Storage (data partition)"), tr("свободно ${fmtGB(dev.storageFreeGB)} из ${fmtGB(dev.storageTotalGB)}", "${fmtGB(dev.storageFreeGB)} free of ${fmtGB(dev.storageTotalGB)}"))
                val refreshHzLabel = String.format(Locale.US, "%.0f", dev.refreshHz)
                val refreshLine = if (dev.refreshHz > 0.0) {
                    tr(" · $refreshHzLabel Гц", " · $refreshHzLabel Hz")
                } else {
                    ""
                }
                sb.value(tr("Экран", "Display"), "${dev.widthPx}×${dev.heightPx} · ${dev.densityDpi} dpi$refreshLine")
                sb.line()
            }
            p.thermal?.let { if (it.hasData) sb.thermalBlock(it) }
        }
    }
    if (isUsMode) {
        sb.append(tr("Mean/σ/перцентили — по последним ${maxOf(snap.windowCount, 1)} измерениям; min/max — накопительные за прогон", "Mean/σ/percentiles cover the last ${maxOf(snap.windowCount, 1)} measurements; min/max accumulate over the whole run"))
    }
    return sb.toString()
}

fun copyReportToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("benchmark-hub-report", text))
    Toast.makeText(context, tr("Отчёт скопирован", "Report copied"), Toast.LENGTH_SHORT).show()
}

fun shareReport(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, tr("Отправить отчёт", "Share report")))
}
