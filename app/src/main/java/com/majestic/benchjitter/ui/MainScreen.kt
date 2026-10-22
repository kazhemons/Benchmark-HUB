package com.majestic.benchjitter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.majestic.benchjitter.engine.CpuCollector
import com.majestic.benchjitter.engine.CpuSnapshot
import com.majestic.benchjitter.engine.FrameCollector
import com.majestic.benchjitter.engine.GpuMetrics
import com.majestic.benchjitter.engine.RamCollector
import com.majestic.benchjitter.engine.RamSnapshot
import com.majestic.benchjitter.engine.RomCollector
import com.majestic.benchjitter.engine.RomSnapshot
import com.majestic.benchjitter.engine.ThermalCollector
import com.majestic.benchjitter.engine.ThermalSnapshot
import com.majestic.benchjitter.engine.WakeCollector
import com.majestic.benchjitter.i18n.tr
import com.majestic.benchjitter.stats.JitterStats
import com.majestic.benchjitter.stats.StatsSnapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.os.Build

// ---------- Корень: тема + динамические цвета (Material You, Android 12+) ----------

@Composable
fun BenchJitterApp() {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val useDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        MainScreen()
    }
}

// ---------- Основной экран ----------

private val INTERVAL_OPTIONS_MS = listOf(0.5, 1.0, 2.0, 5.0, 10.0)

private fun emptySnap(): StatsSnapshot = JitterStats().snapshot()

// Стресс-режимы — режимы с нагревообразующей нагрузкой; за ними наблюдем датчики нагрева.
private fun isStressMode(mode: BenchMode): Boolean =
    mode == BenchMode.CPU || mode == BenchMode.RAM || mode == BenchMode.COMBO ||
        mode == BenchMode.GPU || mode == BenchMode.ROM

/** Режимы, в которых наблюдателю нагрева нужно работать (тест или раздел «Устройство»). */
private fun observesThermalMode(mode: BenchMode): Boolean =
    isStressMode(mode) || mode == BenchMode.DEVICE

@Composable
private fun MainScreen() {
    val context = LocalContext.current

    var mode by rememberSaveable { mutableStateOf(BenchMode.FRAMES) }
    var running by remember { mutableStateOf(false) }
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var frozenElapsedMs by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var intervalIndex by rememberSaveable { mutableIntStateOf(1) }   // 1 мс
    var priorityIndex by rememberSaveable { mutableIntStateOf(1) }   // «Обычный»
    var cpuThreadsIdx by rememberSaveable { mutableIntStateOf(-1) }  // -1 = авто (мин(4, ядер))
    var gpuTest by rememberSaveable { mutableStateOf(GpuTest.BAGEL) }

    val frames = remember { FrameCollector() }
    val wake = remember { WakeCollector() }
    val cpu = remember { CpuCollector() }
    val ram = remember { RamCollector() }
    val rom = remember { RomCollector(context.cacheDir) }
    val gpu = remember { GpuMetrics() }
    val thermal = remember { ThermalCollector(context) }

    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val cpuThreadOptions = remember(cores) {
        listOf(1, 2, 4, cores).filter { it in 1..cores }.distinct().sorted()
    }
    val defaultThreadsIdx = max(0, cpuThreadOptions.indexOf(minOf(4, cores)))
    val cpuThreadsIdxSafe =
        if (cpuThreadsIdx in 0 until cpuThreadOptions.size) cpuThreadsIdx else defaultThreadsIdx
    val cpuThreads = cpuThreadOptions[cpuThreadsIdxSafe]

    // Режим 1 (и тяжёлая сцена GPU): сэмплирование привязано к реальным кадрам дисплея.
    // Запись frameNanos не даёт UI «заснуть» — кадры идут на частоте дисплея,
    // а интервал betweenFrame измеряется метками withFrameNanos (vsync).
    LaunchedEffect(mode, running) {
        if ((mode == BenchMode.FRAMES || mode == BenchMode.GPU) && running) {
            while (true) {
                withFrameNanos { frameTime ->
                    frameNanos = frameTime
                    frames.onFrameNanos(frameTime)
                }
            }
        }
    }

    // Режим 2: отдельный поток, паркуется до абсолютных дедлайнов.
    LaunchedEffect(mode, running) {
        if (mode == BenchMode.WAKE && running) {
            wake.startFresh(
                INTERVAL_OPTIONS_MS[intervalIndex] * 1000.0,
                WakeCollector.PRIORITIES[priorityIndex].second,
            )
            try {
                awaitCancellation()
            } finally {
                wake.stop()
            }
        }
    }

    // Стресс-режимы: профилировщики стартуют/останавливаются вместе с прогоном.
    LaunchedEffect(mode, running, cpuThreads) {
        if (running && (mode == BenchMode.CPU || mode == BenchMode.COMBO)) {
            cpu.startFresh(cpuThreads)
            try {
                awaitCancellation()
            } finally {
                cpu.stop()
            }
        }
    }
    LaunchedEffect(mode, running) {
        if (running && (mode == BenchMode.RAM || mode == BenchMode.COMBO)) {
            ram.startFresh()
            try {
                awaitCancellation()
            } finally {
                ram.stop()
            }
        }
    }
    LaunchedEffect(mode, running) {
        if (running && mode == BenchMode.ROM) {
            rom.startFresh()
            try {
                awaitCancellation()
            } finally {
                rom.stop()
            }
        }
    }
    // GPU-стадия: подписка на FrameMetrics через Window хост-активности (supported
    // честно отражает, удалось ли подписаться на данном устройстве).
    val hostWindow = remember { (context as? android.app.Activity)?.window }

    LaunchedEffect(mode, running) {
        if (running && mode == BenchMode.GPU) {
            hostWindow?.let { gpu.attach(it) }
            try {
                awaitCancellation()
            } finally {
                gpu.detach()
            }
        }
    }

    // Стресс-режимы: наблюдение за нагревом активно, пока режим открыт —
    // текущие значения видны без прогона; старт очишивает измерения и продолжает.
    LaunchedEffect(mode) {
        if (observesThermalMode(mode)) {
            thermal.observeIdle()
            try {
                awaitCancellation()
            } finally {
                thermal.stop()
            }
        }
    }
    LaunchedEffect(mode, running) {
        if (running && isStressMode(mode)) {
            thermal.start()
        }
    }

    // Смена интервала/приоритета на ходу, без рестарта эффекта.
    LaunchedEffect(intervalIndex) {
        wake.setInterval(INTERVAL_OPTIONS_MS[intervalIndex] * 1000.0)
    }
    LaunchedEffect(priorityIndex) {
        wake.setPriority(WakeCollector.PRIORITIES[priorityIndex].second)
    }

    // Обновление статистики UI ~4 Гц на прогоне; в простое ~1 Гц — блок «Нагрев
    // устройства» в стресс-режимах показывает текущую температуру и без теста.
    LaunchedEffect(running) {
        while (true) {
            delay(if (running) 250L else 1_000L)
            tick += 1
        }
    }

    // µs-снимки (кадры/пробуждения/GPU-интервалы) — общий StatsSnapshot;
    // у стресс-движков свои снимки.
    val snap = remember(tick, mode) {
        when (mode) {
            BenchMode.FRAMES, BenchMode.GPU -> frames.stats.snapshot()
            BenchMode.WAKE -> wake.stats.snapshot()
            else -> emptySnap()
        }
    }
    val cpuSnap = remember(tick, mode) {
        if (mode == BenchMode.CPU || mode == BenchMode.COMBO) cpu.snapshot() else null
    }
    val ramSnap = remember(tick, mode) {
        if (mode == BenchMode.RAM || mode == BenchMode.COMBO) ram.snapshot() else null
    }
    val romSnap = remember(tick, mode) { if (mode == BenchMode.ROM) rom.snapshot() else null }
    val gpuSnap = remember(tick, mode) { if (mode == BenchMode.GPU) gpu.snapshot() else null }
    val thermalSnap = remember(tick, mode) { if (observesThermalMode(mode)) thermal.snapshot() else null }
    // Данные устройства собираются только при открытии раздела «Устройство».
    val deviceInfo = remember(mode) {
        if (mode == BenchMode.DEVICE) collectDeviceInfo(context) else null
    }

    val elapsedSec = when {
        running && startedAtMs > 0 -> (System.currentTimeMillis() - startedAtMs) / 1000.0
        else -> frozenElapsedMs / 1000.0
    }
    val measuredFps =
        if (elapsedSec > 0.01 && snap.totalCount > 0) snap.totalCount / elapsedSec else Double.NaN

    val hasData = when (mode) {
        BenchMode.FRAMES, BenchMode.GPU, BenchMode.WAKE -> snap.totalCount > 0
        BenchMode.CPU -> cpuSnap?.hasData == true
        BenchMode.RAM -> ramSnap?.hasData == true
        BenchMode.COMBO -> cpuSnap?.hasData == true || ramSnap?.hasData == true
        BenchMode.ROM -> romSnap?.hasData == true
        BenchMode.DEVICE -> deviceInfo != null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderRow(running = running)

            ModeToggle(mode) { selected ->
                if (selected != mode) {
                    mode = selected
                    running = false
                    tick += 1
                }
            }

            when (mode) {
                BenchMode.FRAMES -> {
                    val nominal = frames.nominalPeriodUs
                    val ratioPct =
                        if (nominal > 0.0 && !snap.stdDevUs.isNaN()) snap.stdDevUs / nominal * 100.0 else Double.NaN
                    HeroPanel(
                        caption = tr("Джиттер кадров — σ отклонений от номинала", "Frame jitter — σ of deviation from nominal"),
                        mainValue = if (snap.isEmpty) "—" else fmtUs(snap.stdDevUs),
                        ratioPct = ratioPct,
                        hasData = !snap.isEmpty,
                        subLines = listOf(
                            tr("Номинал экрана: ${fmtHz(nominal)} · период ${fmtUs(if (nominal > 0) nominal else Double.NaN)}", "Display nominal: ${fmtHz(nominal)} · period ${fmtUs(if (nominal > 0) nominal else Double.NaN)}"),
                            tr("Замерено FPS: ${fmtFps(measuredFps)} · пропущено кадров: ${frames.missedFrames}", "Measured FPS: ${fmtFps(measuredFps)} · dropped frames: ${frames.missedFrames}"),
                            tr("Гэпов после паузы: ${frames.resyncGaps}", "Resync gaps after pauses: ${frames.resyncGaps}"),
                        ),
                    )
                }
                BenchMode.WAKE -> {
                    val ratioPct =
                        if (!snap.p99Us.isNaN()) snap.p99Us / wake.intervalUs * 100.0 else Double.NaN
                    HeroPanel(
                        caption = tr("Задержки пробуждения — p99 опоздания", "Wake latencies — p99 lateness"),
                        mainValue = if (snap.isEmpty) "—" else fmtUs(snap.p99Us),
                        ratioPct = ratioPct,
                        hasData = !snap.isEmpty,
                        subLines = listOf(
                            tr("Интервал: ${fmtMs(wake.intervalUs / 1000.0)} · приоритет: ${WakeCollector.PRIORITIES[priorityIndex].first}", "Interval: ${fmtMs(wake.intervalUs / 1000.0)} · priority: ${WakeCollector.PRIORITIES[priorityIndex].first}"),
                            tr("Просрочено меток: ${wake.missedDeadlines}", "Missed deadlines: ${wake.missedDeadlines}"),
                            tr("Идеал — всё распределение вплотную у нуля", "Ideal — the whole distribution sits tight against zero"),
                        ),
                    )
                }
                BenchMode.CPU -> cpuSnap?.let { CpuHero(it, cpuThreads) }
                BenchMode.RAM -> ramSnap?.let { RamHero(it) }
                BenchMode.COMBO -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cpuSnap?.let { CpuHero(it, cpuThreads) }
                    ramSnap?.let { RamHero(it) }
                }
                BenchMode.GPU -> GpuHero(snap, gpuSnap ?: emptySnap(), measuredFps, gpu.supported, gpuTest)
                BenchMode.ROM -> romSnap?.let { RomHero(it) }
                BenchMode.DEVICE -> deviceInfo?.let { DeviceHero(it) }
            }

            if (observesThermalMode(mode)) {
                TemperaturePanel(thermalSnap)
            }

            if (mode != BenchMode.DEVICE) {
            ControlsRow(
                running = running,
                hasData = hasData,
                elapsedSec = elapsedSec,
                onStart = {
                    if (mode == BenchMode.FRAMES || mode == BenchMode.GPU) frames.reset()
                    startedAtMs = System.currentTimeMillis()
                    frozenElapsedMs = 0L
                    running = true
                    tick += 1
                },
                onStop = {
                    running = false
                    frozenElapsedMs = System.currentTimeMillis() - startedAtMs
                },
                onReset = {
                    frames.reset()
                    wake.clearData()
                    cpu.reset()
                    ram.reset()
                    rom.reset()
                    gpu.reset()
                    thermal.reset()
                    startedAtMs = 0L
                    frozenElapsedMs = 0L
                    tick += 1
                },
            )
            }

            if (mode == BenchMode.WAKE) {
                WakeControls(
                    intervalIndex = intervalIndex,
                    onIntervalChange = { intervalIndex = it },
                    priorityIndex = priorityIndex,
                    onPriorityChange = { priorityIndex = it },
                )
            }
            if (mode == BenchMode.CPU || mode == BenchMode.COMBO) {
                CpuControls(
                    options = cpuThreadOptions,
                    cores = cores,
                    selectedIndex = cpuThreadsIdxSafe,
                    onSelect = { cpuThreadsIdx = it },
                )
            }
            if (mode == BenchMode.GPU) {
                GpuTestControls(selected = gpuTest, onSelect = { gpuTest = it })
                when (gpuTest) {
                    GpuTest.BAGEL -> GpuScene(active = running)
                    GpuTest.OCEAN -> OceanScene(active = running)
                }
            }

            if (mode == BenchMode.FRAMES || mode == BenchMode.WAKE || mode == BenchMode.GPU) {
                StatsGrid(snap)

                ChartsSection(
                    // для GPU график тот же, что у «Джиттер кадров»: те же интервалы кадров
                    mode = if (mode == BenchMode.GPU) BenchMode.FRAMES else mode,
                    snap = snap,
                    nominalUs = frames.nominalPeriodUs,
                    intervalUs = wake.intervalUs,
                    missedFrames = frames.missedFrames,
                    resyncGaps = frames.resyncGaps,
                    missedDeadlines = wake.missedDeadlines,
                    running = running,
                    frameTimeNanos = { frameNanos },
                )
            }

            if (hasData) {
                val reportText = buildReport(
                    buildParamsFor(
                        mode, elapsedSec, snap, measuredFps, frames, wake, priorityIndex,
                        cpuSnap, ramSnap, romSnap, gpuSnap, gpu.supported, gpuTest, thermalSnap, deviceInfo,
                    ),
                )
                ReportRow(
                    onCopy = { copyReportToClipboard(context, reportText) },
                    onShare = { shareReport(context, reportText) },
                )
            }

            FooterNote(mode)
        }
    }
}

// ---------- Шапка ----------

@Composable
private fun HeaderRow(running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "BENCHMARK HUB",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        val dotColor =
            if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Canvas(Modifier.size(8.dp)) {
            drawCircle(color = dotColor, radius = size.minDimension / 2f)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            if (running) tr("идёт замер", "measuring") else tr("готов", "ready"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Сетка режимов ----------

@Composable
private fun ModeToggle(current: BenchMode, onSelect: (BenchMode) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                ModeCell(BenchMode.FRAMES.label, current == BenchMode.FRAMES, Modifier.weight(1f)) {
                    onSelect(BenchMode.FRAMES)
                }
                ModeCell(BenchMode.WAKE.label, current == BenchMode.WAKE, Modifier.weight(1f)) {
                    onSelect(BenchMode.WAKE)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ModeCell(BenchMode.CPU.label, current == BenchMode.CPU, Modifier.weight(1f)) {
                    onSelect(BenchMode.CPU)
                }
                ModeCell(BenchMode.RAM.label, current == BenchMode.RAM, Modifier.weight(1f)) {
                    onSelect(BenchMode.RAM)
                }
                ModeCell(BenchMode.COMBO.label, current == BenchMode.COMBO, Modifier.weight(1f)) {
                    onSelect(BenchMode.COMBO)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ModeCell(BenchMode.GPU.label, current == BenchMode.GPU, Modifier.weight(1f)) {
                    onSelect(BenchMode.GPU)
                }
                ModeCell(BenchMode.ROM.label, current == BenchMode.ROM, Modifier.weight(1f)) {
                    onSelect(BenchMode.ROM)
                }
                ModeCell(BenchMode.DEVICE.label, current == BenchMode.DEVICE, Modifier.weight(1f)) {
                    onSelect(BenchMode.DEVICE)
                }
            }
        }
    }
}

@Composable
private fun ModeCell(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Главный показатель ----------

@Composable
private fun HeroPanel(
    caption: String,
    mainValue: String,
    ratioPct: Double,
    hasData: Boolean,
    subLines: List<String>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mainValue,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (hasData) {
                    Text(
                        verdictText(ratioPct),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = verdictColor(ratioPct),
                    )
                }
            }
            subLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun verdictText(ratioPct: Double): String = when {
    ratioPct.isNaN() -> ""
    ratioPct < 2.0 -> tr("отлично", "excellent")
    ratioPct < 10.0 -> tr("хорошо", "good")
    else -> tr("плохо", "poor")
}

@Composable
private fun verdictColor(ratioPct: Double): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        ratioPct.isNaN() -> scheme.onSurfaceVariant
        ratioPct < 2.0 -> scheme.primary
        ratioPct < 10.0 -> scheme.tertiary
        else -> scheme.error
    }
}

// ---------- Кнопки управления ----------

@Composable
private fun ControlsRow(
    running: Boolean,
    hasData: Boolean,
    elapsedSec: Double,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = if (running) onStop else onStart) {
            Text(if (running) tr("Стоп", "Stop") else tr("Пуск", "Start"))
        }
        OutlinedButton(onClick = onReset, enabled = !running && hasData) {
            Text(tr("Сброс", "Reset"))
        }
        Spacer(Modifier.weight(1f))
        val status = when {
            running -> tr("идёт: ${fmtDuration(elapsedSec)}", "running: ${fmtDuration(elapsedSec)}")
            hasData -> tr("прогон: ${fmtDuration(elapsedSec)}", "run: ${fmtDuration(elapsedSec)}")
            else -> "—"
        }
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Управление режима «Задержки потока» ----------

@Composable
private fun WakeControls(
    intervalIndex: Int,
    onIntervalChange: (Int) -> Unit,
    priorityIndex: Int,
    onPriorityChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Интервал пробуждения", "Wake interval"), style = MaterialTheme.typography.titleSmall)
        Text(
            tr("Текущий: ${fmtMs(INTERVAL_OPTIONS_MS[intervalIndex])}", "Current: ${fmtMs(INTERVAL_OPTIONS_MS[intervalIndex])}"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = intervalIndex.toFloat(),
            onValueChange = { v -> onIntervalChange(v.roundToInt()) },
            valueRange = 0f..(INTERVAL_OPTIONS_MS.size - 1).toFloat(),
            steps = INTERVAL_OPTIONS_MS.size - 2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            INTERVAL_OPTIONS_MS.forEach { ms ->
                Text(fmtMs(ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(tr("Приоритет потока", "Thread priority"), style = MaterialTheme.typography.titleSmall)
        PriorityChipRow((0..2), priorityIndex, onPriorityChange)
        PriorityChipRow((3..4), priorityIndex, onPriorityChange)

        Text(
            tr("Смена интервала во время замера начинает новый прогон; приоритет применяется на ходу.", "Changing the interval during a run starts a new run; priority applies on the fly."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriorityChipRow(indices: Iterable<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        indices.forEach { idx ->
            FilterChip(
                selected = selected == idx,
                onClick = { onSelect(idx) },
                label = { Text(WakeCollector.PRIORITIES[idx].first) },
            )
        }
    }
}

// ---------- Сетка статистики ----------

@Composable
private fun StatsGrid(snap: StatsSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("mean/σ/перцентили — по последним ${snap.windowCount} сэмплам · min/max накопительные · всего сэмплов: ${snap.totalCount}",
                "mean/σ/percentiles — over the last ${snap.windowCount} samples · min/max cumulative · total samples: ${snap.totalCount}"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatRow("mean", fmtUs(snap.meanUs), "σ", fmtUs(snap.stdDevUs))
        StatRow(tr("мин", "min"), fmtUs(snap.minUs), tr("макс", "max"), fmtUs(snap.maxUs))
        StatRow("p50", fmtUs(snap.p50Us), "p90", fmtUs(snap.p90Us))
        StatRow("p95", fmtUs(snap.p95Us), "p99", fmtUs(snap.p99Us))
    }
}

@Composable
private fun StatRow(labelA: String, valueA: String, labelB: String, valueB: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(labelA, valueA, Modifier.weight(1f))
        StatCell(labelB, valueB, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------- График ----------

@Composable
private fun ChartsSection(
    mode: BenchMode,
    snap: StatsSnapshot,
    nominalUs: Double,
    intervalUs: Double,
    missedFrames: Long,
    resyncGaps: Long,
    missedDeadlines: Long,
    running: Boolean,
    frameTimeNanos: () -> Long,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val outline = MaterialTheme.colorScheme.outline
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == BenchMode.FRAMES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Интервалы кадров · отклонение от номинала", "Frame intervals · deviation from nominal"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    tr("пропущено: $missedFrames", "dropped: $missedFrames"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (missedFrames > 0) bad else variant,
                )
            }
            if (running) {
                FramePulseStrip(frameTimeNanos, primary, Modifier.fillMaxWidth().height(22.dp))
            }
            FrameIntervalChart(
                snap.tail, nominalUs, primary, bad, outline,
                Modifier.fillMaxWidth().height(140.dp),
            )
            if (nominalUs > 0.0) {
                Text(tr("Распределение интервалов (окно)", "Interval distribution (window)"), style = MaterialTheme.typography.labelMedium, color = variant)
                DistributionChart(
                    snap.windowSorted, max(0.0, nominalUs * 0.5), nominalUs * 2.0, nominalUs * 1.5,
                    primary, bad, modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                if (resyncGaps > 0) {
                    Text(tr("Гэпов (паузы приложения): $resyncGaps", "Gaps (app pauses): $resyncGaps"), style = MaterialTheme.typography.labelSmall, color = variant)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Опоздания пробуждения относительно дедлайна", "Wake latencies relative to deadline"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    tr("просрочено: $missedDeadlines", "missed: $missedDeadlines"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (missedDeadlines > 0) bad else variant,
                )
            }
            WakeLatencyChart(
                snap.tail, intervalUs, primary, bad, outline,
                Modifier.fillMaxWidth().height(140.dp),
            )
            if (snap.windowSorted.isNotEmpty()) {
                val hiUs = max(100.0, snap.windowSorted.last()) * 1.15
                Text(tr("Распределение опозданий (окно)", "Latency distribution (window)"), style = MaterialTheme.typography.labelMedium, color = variant)
                DistributionChart(
                    snap.windowSorted, 0.0, hiUs, intervalUs / 2.0,
                    primary, bad, modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }
    }
}

// ---------- Отчёт ----------

private fun buildParamsFor(
    mode: BenchMode,
    elapsedSec: Double,
    snap: StatsSnapshot,
    measuredFps: Double,
    frames: FrameCollector,
    wake: WakeCollector,
    priorityIndex: Int,
    cpuSnap: CpuSnapshot?,
    ramSnap: RamSnapshot?,
    romSnap: RomSnapshot?,
    gpuSnap: StatsSnapshot?,
    gpuSupported: Boolean,
    gpuTest: GpuTest,
    thermalSnap: ThermalSnapshot?,
    deviceInfo: DeviceInfo?,
): ReportParams = ReportParams(
    mode = mode,
    durationSec = elapsedSec,
    snapshot = snap,
    measuredFps = measuredFps,
    frame = if (mode == BenchMode.FRAMES || mode == BenchMode.GPU) frames else null,
    wake = if (mode == BenchMode.WAKE) wake else null,
    priorityLabel = if (mode == BenchMode.WAKE) WakeCollector.PRIORITIES[priorityIndex].first else "",
    cpu = if (mode == BenchMode.CPU || mode == BenchMode.COMBO) cpuSnap else null,
    ram = if (mode == BenchMode.RAM || mode == BenchMode.COMBO) ramSnap else null,
    rom = if (mode == BenchMode.ROM) romSnap else null,
    gpuMs = if (mode == BenchMode.GPU) gpuSnap else null,
    gpuSupported = gpuSupported,
    gpuTestLabel = if (mode == BenchMode.GPU) gpuTest.label else null,
    thermal = if (observesThermalMode(mode)) thermalSnap else null,
    device = if (mode == BenchMode.DEVICE) deviceInfo else null,
)

@Composable
private fun ReportRow(onCopy: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onCopy) { Text(tr("Скопировать отчёт", "Copy report")) }
        TextButton(onClick = onShare) { Text(tr("Отправить", "Share")) }
    }
}

// ---------- Подвал: паспорт теста ----------

@Composable
private fun FooterNote(mode: BenchMode) {
    val doc = remember(mode) { modeDoc(mode) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            doc.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(doc.tech, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        doc.values.forEach { (name, desc) ->
            Text(
                "• $name — $desc",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
