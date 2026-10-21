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
            Text("Нагрев устройства", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snap.readings.isEmpty()) {
                Text(
                    "Устройство не отдаёт показаний температуры",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snap.readings.forEach { r ->
                val hot = !r.maxC.isNaN() && r.maxC >= 45.0
                val lineText = if (snap.runMeasured) {
                    "${r.label}: ${fmtC(r.valueC)} · макс за прогон ${fmtC(r.maxC)}"
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
                    "Температуры CPU/GPU/RAM доступны, только если устройство публикует температурные сенсоры или системные тепловые зоны",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val d = snap.diagnostics
                Text(
                    "Диагностика: тепловых зон найдено ${d.zonesFound}, читается температур ${d.zonesShowingTemp}; " +
                        "сенсоров-кандидатов ${d.sensorCandidates.size}" +
                        if (d.sensorCandidates.isNotEmpty()) " (${d.sensorCandidates.joinToString(", ")})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Тепловой статус: ${snap.thermalStatusLabel ?: "недоступен (требуется Android 10+)"}",
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
        caption = "Стресс CPU — суммарный темп всех потоков (квант = ${CpuCollector.QUANTUM_OPS} float-операций)",
        mainValue = if (s.hasData) "${fmtMops(score)} · ${s.threads} пот." else "—",
        subLines = listOf(
            "Поток: ${threadsSelected} выбрано · выполнено операций: ${fmtCount(s.totalOpsDone)}",
            "p50 ${fmtMops(s.p50Qps * CpuCollector.QUANTUM_OPS / 1_000_000.0)} · p99 ${fmtMops(s.p99Qps * CpuCollector.QUANTUM_OPS / 1_000_000.0)}",
            "Стабильность троттлинга: σ ${fmtPct(stability)} от среднего (меньше — лучше)",
        ),
    )
}

@Composable
internal fun RamHero(s: RamSnapshot) {
    StressHero(
        caption = "Стресс RAM — удержание выделенной кучи (чанки по ${s.chunkBytes / 1048576} МБ, полное касание страниц)",
        mainValue = if (s.hasData) fmtMB(s.allocatedMB) else "—",
        subLines = listOf(
            "Скорость заполнения: ${fmtMBps(s.fillMBps)} · время чанка mean ${fmtUs(s.fillMeanUs)} · p99 ${fmtUs(s.fillP99Us)} · σ ${fmtUs(s.fillStdDevUs)}",
            if (s.hitLimit) "Потолок достигнут: следующий чанк не влезает (лимит кучи процесса ≈ ${fmtMB(s.heapCapMB)})" else "Память растёт: следующий чанк ещё влезает",
            "Освобождение — кнопка «Сбросить»",
        ),
    )
}

@Composable
internal fun GpuHero(intervalSnap: StatsSnapshot, gpuSnap: StatsSnapshot, measuredFps: Double, supported: Boolean) {
    val gpuMeanMs = if (!gpuSnap.isEmpty) gpuSnap.meanUs / 1000.0 else Double.NaN
    StressHero(
        caption = "Стресс GPU — тяжёлая сцена (сотни градиентных кругов, непрерывная анимация)",
        mainValue = if (!intervalSnap.isEmpty) "${fmtFps(measuredFps)} fps" else "—",
        subLines = listOf(
            if (supported) "GPU-стадия кадра: mean ${fmtMs(gpuMeanMs)} · p95 ${fmtMs(gpuSnap.p95Us / 1000.0)} · p99 ${fmtMs(gpuSnap.p99Us / 1000.0)}"
            else "GPU-стадия кадра недоступна: FrameMetrics-подписка не принята этим устройством",
            "Интервал кадров: σ ${fmtUs(intervalSnap.stdDevUs)} · пропуски смотрите на графике режима «Джиттер кадров»",
        ),
    )
}

@Composable
internal fun DeviceHero(dev: DeviceInfo) {
    val groups = groupCoreFreqs(dev.cores)
    val coresLine = if (groups.isEmpty()) {
        "частоты ядер не опубликованы"
    } else {
        groups.joinToString(" · ") { "${it.first}×${it.second} МГц" }
    }
    val screenLine = "${dev.widthPx}×${dev.heightPx} · ${dev.densityDpi} dpi" +
        if (dev.refreshHz > 0.0) " · ${dev.refreshHz.toInt()} Гц" else ""
    StressHero(
        caption = "Раздел «Устройство» — справка без нагрузочного теста",
        mainValue = "${dev.manufacturer} ${dev.model}",
        subLines = listOf(
            "CPU: ${dev.cores.size} ядер · максимумы: $coresLine",
            "Память (видимая приложению): ${fmtMB(dev.totalRamMB.toDouble())}",
            "Хранилище (раздел данных): свободно ${fmtGB(dev.storageFreeGB)} из ${fmtGB(dev.storageTotalGB)}",
            "Экран: $screenLine",
        ),
    )
}

@Composable
internal fun RomHero(s: RomSnapshot) {
    StressHero(
        caption = "Стресс ROM — последовательная запись файла ${s.fileSizeMB.toInt()} МБ (fsync на файле) и чтение обратно",
        mainValue = if (s.hasData) "запись ${fmtMBps(s.writeMBpsMean)}" else "—",
        subLines = listOf(
            "Чтение (через кэш ФС — это потолок кэша, не сырая скорость флеша): ${fmtMBps(s.readMBpsMean)}",
            "Проходов завершено: ${s.passesDone} · куски по ${s.ioChunkKB} КБ · σ записи ${fmtUs(s.writeSnap.stdDevUs)} · p99 ${fmtUs(s.writeSnap.p99Us)}",
            if (s.storageFull) "Свободное место на разделе заканчивается — прогон остановлен" else "Файл пишется в cacheDir и удаляется после каждого прохода",
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
            Text("Рабочие потоки (всего ядер: $cores)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { idx, count ->
                    FilterChip(
                        selected = idx == selectedIndex,
                        onClick = { onSelect(idx) },
                        label = { Text(if (count >= cores) "Все ($count)" else "$count") },
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
