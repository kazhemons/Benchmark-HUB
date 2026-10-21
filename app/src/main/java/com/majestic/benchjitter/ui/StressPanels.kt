package com.majestic.benchjitter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.majestic.benchjitter.engine.CpuCollector
import com.majestic.benchjitter.engine.CpuSnapshot
import com.majestic.benchjitter.engine.RamSnapshot
import com.majestic.benchjitter.engine.RomSnapshot
import com.majestic.benchjitter.engine.ThermalSnapshot
import com.majestic.benchjitter.i18n.tr
import com.majestic.benchjitter.stats.StatsSnapshot
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- Панели стресс-режимов ----------

@Composable
internal fun StressHero(caption: String, mainValue: String, subLines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                mainValue,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            subLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun TemperaturePanel(snap: ThermalSnapshot?) {
    if (snap == null || !snap.hasData) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("Нагрев устройства", "Device thermals"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snap.readings.isEmpty()) {
                Text(
                    tr("Устройство не отдаёт показаний температуры", "The device reports no temperature readings"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snap.readings.forEach { r ->
                val hot = !r.maxC.isNaN() && r.maxC >= 45.0
                val lineText = if (snap.runMeasured) {
                    tr("${r.label}: ${fmtC(r.valueC)} · макс за прогон ${fmtC(r.maxC)}", "${r.label}: ${fmtC(r.valueC)} · peak during run ${fmtC(r.maxC)}")
                } else {
                    "${r.label}: ${fmtC(r.valueC)}"
                }
                Text(
                    lineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!snap.hasSensorData) {
                Text(
                    tr("Температуры CPU/GPU/RAM доступны, только если устройство публикует температурные сенсоры или системные тепловые зоны", "CPU/GPU/RAM temperature readings are available only if the device publishes temperature sensors or system thermal zones"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val d = snap.diagnostics
                Text(
                    tr("Диагностика: тепловых зон найдено ${d.zonesFound}, читается температур ${d.zonesShowingTemp}; сенсоров-кандидатов ${d.sensorCandidates.size}${if (d.sensorCandidates.isNotEmpty()) " (${d.sensorCandidates.joinToString(", ")})" else ""}",
                        "Thermal diagnostics: thermal zones found ${d.zonesFound}, reporting temperature ${d.zonesShowingTemp}; candidate sensors ${d.sensorCandidates.size}${if (d.sensorCandidates.isNotEmpty()) " (${d.sensorCandidates.joinToString(", ")})" else ""}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                tr("Тепловой статус: ${snap.thermalStatusLabel ?: "недоступен (требуется Android 10+)"}", "Thermal status: ${snap.thermalStatusLabel ?: "unavailable (requires Android 10+)"}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun CpuHero(s: CpuSnapshot, threadsSelected: Int) {
    val score = if (s.hasData) s.meanQps * CpuCollector.QUANTUM_OPS / 1_000_000.0 else Double.NaN
    val stability = if (s.hasData && s.meanQps > 0.0) s.stdDevQps / s.meanQps * 100.0 else Double.NaN
    StressHero(
        caption = tr("Стресс CPU — суммарный темп всех потоков (квант = ${CpuCollector.QUANTUM_OPS} float-операций)", "CPU stress — aggregate rate of all threads (quantum = ${CpuCollector.QUANTUM_OPS} float operations)"),
        mainValue = if (s.hasData) tr("${fmtMops(score)} · ${s.threads} пот.", "${fmtMops(score)} · ${s.threads} threads") else "—",
        subLines = listOf(
            tr("Поток: ${threadsSelected} выбрано · выполнено операций: ${fmtCount(s.totalOpsDone)}", "Threads: ${threadsSelected} selected · operations completed: ${fmtCount(s.totalOpsDone)}"),
            "p50 ${fmtMops(s.p50Qps * CpuCollector.QUANTUM_OPS / 1_000_000.0)} · p99 ${fmtMops(s.p99Qps * CpuCollector.QUANTUM_OPS / 1_000_000.0)}",
            tr("Стабильность троттлинга: σ ${fmtPct(stability)} от среднего (меньше — лучше)", "Throttling stability: σ ${fmtPct(stability)} of the mean (lower is better)"),
        ),
    )
}

@Composable
internal fun RamHero(s: RamSnapshot) {
    StressHero(
        caption = tr("Стресс RAM — удержание выделенной кучи (чанки по ${s.chunkBytes / 1048576} МБ, полное касание страниц)", "RAM stress — holding the allocated heap (chunks of ${s.chunkBytes / 1048576} MB, every page touched)"),
        mainValue = if (s.hasData) fmtMB(s.allocatedMB) else "—",
        subLines = listOf(
            tr("Скорость заполнения: ${fmtMBps(s.fillMBps)} · время чанка mean ${fmtUs(s.fillMeanUs)} · p99 ${fmtUs(s.fillP99Us)} · σ ${fmtUs(s.fillStdDevUs)}", "Fill rate: ${fmtMBps(s.fillMBps)} · chunk time mean ${fmtUs(s.fillMeanUs)} · p99 ${fmtUs(s.fillP99Us)} · σ ${fmtUs(s.fillStdDevUs)}"),
            if (s.hitLimit)
                tr("Потолок достигнут: следующий чанк не влезает (лимит кучи процесса ≈ ${fmtMB(s.heapCapMB)})",
                    "Limit reached: the next chunk does not fit (process heap cap ≈ ${fmtMB(s.heapCapMB)})")
            else
                tr("Память растёт: следующий чанк ещё влезает", "Memory still growing: the next chunk fits"),
            tr("Освобождение — кнопка «Сбросить»", "Release memory with the Reset button"),
        ),
    )
}

@Composable
internal fun GpuHero(intervalSnap: StatsSnapshot, gpuSnap: StatsSnapshot, measuredFps: Double, supported: Boolean) {
    val gpuMeanMs = if (!gpuSnap.isEmpty) gpuSnap.meanUs / 1000.0 else Double.NaN
    StressHero(
        caption = tr("Стресс GPU — тяжёлая сцена (сотни градиентных кругов, непрерывная анимация)", "GPU stress — heavy scene (hundreds of gradient circles, continuous animation)"),
        mainValue = if (!intervalSnap.isEmpty) "${fmtFps(measuredFps)} fps" else "—",
        subLines = listOf(
            if (supported)
                tr("GPU-стадия кадра: mean ${fmtMs(gpuMeanMs)} · p95 ${fmtMs(gpuSnap.p95Us / 1000.0)} · p99 ${fmtMs(gpuSnap.p99Us / 1000.0)}", "Frame GPU stage: mean ${fmtMs(gpuMeanMs)} · p95 ${fmtMs(gpuSnap.p95Us / 1000.0)} · p99 ${fmtMs(gpuSnap.p99Us / 1000.0)}")
            else
                tr("GPU-стадия кадра недоступна: FrameMetrics-подписка не принята этим устройством", "Frame GPU stage unavailable: FrameMetrics subscription not accepted by this device"),
            tr("Интервал кадров: σ ${fmtUs(intervalSnap.stdDevUs)} · пропуски смотрите на графике режима «Джиттер кадров»", "Frame intervals: σ ${fmtUs(intervalSnap.stdDevUs)} · see dropped frames on the Frame Jitter chart"),
        ),
    )
}

@Composable
internal fun DeviceHero(dev: DeviceInfo) {
    val groups = groupCoreFreqs(dev.cores)
    val coresLine = if (groups.isEmpty()) {
        tr("частоты ядер не опубликованы", "core frequencies are not published")
    } else {
        groups.joinToString(" · ") { tr("${it.first}×${it.second} МГц", "${it.first}×${it.second} MHz") }
    }
    val screenLine = "${dev.widthPx}×${dev.heightPx} · ${dev.densityDpi} dpi" +
        if (dev.refreshHz > 0.0) tr(" · ${dev.refreshHz.toInt()} Гц", " · ${dev.refreshHz.toInt()} Hz") else ""
    StressHero(
        caption = tr("Раздел «Устройство» — справка без нагрузочного теста", "Device section — reference information, no load test"),
        mainValue = "${dev.manufacturer} ${dev.model}",
        subLines = listOf(
            tr("CPU: ${dev.cores.size} ядер · максимумы: $coresLine", "CPU: ${dev.cores.size} cores · maxima: $coresLine"),
            tr("Память (видимая приложению): ${fmtMB(dev.totalRamMB.toDouble())}", "RAM (visible to the app): ${fmtMB(dev.totalRamMB.toDouble())}"),
            tr("Хранилище (раздел данных): свободно ${fmtGB(dev.storageFreeGB)} из ${fmtGB(dev.storageTotalGB)}", "Storage (data partition): ${fmtGB(dev.storageFreeGB)} free of ${fmtGB(dev.storageTotalGB)}"),
            tr("Экран: $screenLine", "Display: $screenLine"),
        ),
    )
}

@Composable
internal fun RomHero(s: RomSnapshot) {
    StressHero(
        caption = tr("Стресс ROM — последовательная запись файла ${s.fileSizeMB.toInt()} МБ (fsync на файле) и чтение обратно", "ROM stress — sequential write of a ${s.fileSizeMB.toInt()} MB file (fsync on the file), then reading it back"),
        mainValue = if (s.hasData) tr("запись ${fmtMBps(s.writeMBpsMean)}", "write ${fmtMBps(s.writeMBpsMean)}") else "—",
        subLines = listOf(
            tr("Чтение (через кэш ФС — это потолок кэша, не сырая скорость флеша): ${fmtMBps(s.readMBpsMean)}", "Read (via the filesystem cache — a cache ceiling, not raw flash speed): ${fmtMBps(s.readMBpsMean)}"),
            tr("Проходов завершено: ${s.passesDone} · куски по ${s.ioChunkKB} КБ · σ записи ${fmtUs(s.writeSnap.stdDevUs)} · p99 ${fmtUs(s.writeSnap.p99Us)}",
                "Passes completed: ${s.passesDone} · chunks of ${s.ioChunkKB} KB · write σ ${fmtUs(s.writeSnap.stdDevUs)} · p99 ${fmtUs(s.writeSnap.p99Us)}"),
            if (s.storageFull)
                tr("Свободное место на разделе заканчивается — прогон остановлен",
                    "Free space on the partition is running out — run stopped")
            else
                tr("Файл пишется в cacheDir и удаляется после каждого прохода", "The file is written to cacheDir and deleted after each pass"),
        ),
    )
}

// ---------- Выбор числа потоков CPU ----------

@Composable
internal fun CpuControls(
    options: List<Int>,
    cores: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("Рабочие потоки (всего ядер: $cores)", "Worker threads (total cores: $cores)"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { idx, count ->
                    FilterChip(
                        selected = idx == selectedIndex,
                        onClick = { onSelect(idx) },
                        label = { Text(if (count >= cores) tr("Все ($count)", "All ($count)") else "$count") },
                    )
                }
            }
        }
    }
}

// ---------- Тяжёлая GPU-сцена: золотой угол spiral, сотни радиальных градиентов ----------

private const val GPU_CIRCLES = 220
private const val GOLDEN_ANGLE = 2.39996323f
private const val TAU = 6.28318531f

@Composable
internal fun GpuScene(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "gpu-scene")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gpu-phase",
    )
    val cA = MaterialTheme.colorScheme.primary
    val cB = MaterialTheme.colorScheme.tertiary
    val cC = MaterialTheme.colorScheme.secondary

    // Лёгкая анимация активна только во время прогона: в покое кадр статичен.
    val animatedPhase = if (active) phase else 0f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        val cx0 = size.width / 2f
        val cy0 = size.height / 2f
        val n = GPU_CIRCLES
        val minDim = size.minDimension
        val ph = animatedPhase
        repeat(n) { i ->
            val fi = i.toFloat()
            val ga = GOLDEN_ANGLE * fi + ph * TAU
            val rr = minDim * 0.42f * sqrt((fi + 0.5f) / n)
            val cx = cx0 + rr * cos(ga)
            val cy = cy0 + rr * 0.62f * sin(ga)
            val radius = minDim * (0.06f + 0.03f * (i % 5)) * (1.15f - 0.35f * ph)
            val inner = if (i % 3 == 0) cA else if (i % 3 == 1) cB else cC
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(inner.copy(alpha = 0.85f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
                alpha = 0.75f,
            )
        }
    }
}
