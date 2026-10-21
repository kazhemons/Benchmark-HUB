package com.majestic.benchjitter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Живой график интервалов кадров: отклонения от номинального периода.
 * Пунктир по центру — номинал; бары выше/ниже — опережение/отставание.
 * Красным — интервалы длиннее 1.5 номинального (пропущенный кадр).
 */
@Composable
fun FrameIntervalChart(
    samples: DoubleArray,
    nominalUs: Double,
    primaryColor: Color,
    badColor: Color,
    baselineColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val baseY = h * 0.5f
        drawLine(
            color = baselineColor,
            start = Offset(0f, baseY),
            end = Offset(w, baseY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
        )
        if (samples.isEmpty() || nominalUs <= 0.0) return@Canvas

        var maxAbs = nominalUs * 0.02
        for (v in samples) {
            val d = abs(v - nominalUs)
            if (d > maxAbs) maxAbs = d
        }

        val n = samples.size
        val barW = w / n
        for (i in 0 until n) {
            val v = samples[i]
            val rel = ((v - nominalUs) / maxAbs).toFloat()
            val barH = rel * (h * 0.46f)
            val top = if (barH > 0f) baseY - barH else baseY
            drawRect(
                color = if (v > nominalUs * 1.5) badColor else primaryColor,
                topLeft = Offset(i * barW, top),
                size = Size(barW.coerceAtLeast(0.75f), abs(barH).coerceAtLeast(0.5f)),
            )
        }
    }
}

/**
 * Живой график опозданий пробуждения (0 у базы; чем ближе к нулю — тем точнее ОС).
 * Красным — опоздания больше половины интервала (просроченная метка).
 */
@Composable
fun WakeLatencyChart(
    samples: DoubleArray,
    intervalUs: Double,
    primaryColor: Color,
    badColor: Color,
    baselineColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val baseY = h - 2.dp.toPx()
        drawLine(
            color = baselineColor,
            start = Offset(0f, baseY),
            end = Offset(w, baseY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
        )
        if (samples.isEmpty()) return@Canvas

        var hi = 100.0
        for (v in samples) if (v > hi) hi = v
        hi *= 1.15

        val n = samples.size
        val barW = w / n
        val usableH = h - 4.dp.toPx()
        for (i in 0 until n) {
            val v = samples[i]
            val barH = ((v / hi) * usableH).toFloat().coerceAtLeast(0.5f)
            drawRect(
                color = if (v > intervalUs / 2.0) badColor else primaryColor,
                topLeft = Offset(i * barW, baseY - barH),
                size = Size(barW.coerceAtLeast(0.75f), barH),
            )
        }
    }
}

/** Гистограмма: [windowSorted] — сортированные сэмплы окна. */
@Composable
fun DistributionChart(
    windowSorted: DoubleArray,
    loUs: Double,
    hiUs: Double,
    badFromUs: Double,
    primaryColor: Color,
    badColor: Color,
    bins: Int = 24,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (windowSorted.isEmpty() || hiUs <= loUs) return@Canvas

        val counts = IntArray(bins)
        val span = hiUs - loUs
        for (v in windowSorted) {
            if (v < loUs) continue
            val idx = (((v - loUs) / span) * bins).toInt().coerceIn(0, bins - 1)
            counts[idx] += 1
        }
        var maxCount = 1
        for (c in counts) if (c > maxCount) maxCount = c

        val barW = size.width / bins
        for (b in 0 until bins) {
            val c = counts[b]
            if (c == 0) continue
            val barH = (c.toFloat() / maxCount) * size.height
            val center = loUs + span * (b + 0.5) / bins
            drawRect(
                color = if (center > badFromUs) badColor else primaryColor.copy(alpha = 0.85f),
                topLeft = Offset(b * barW, size.height - barH),
                size = Size((barW - 1.dp.toPx()).coerceAtLeast(1f), barH),
            )
        }
    }
}

/** Полоса-«пульс»: точка проходит полосу за 3 с — видно, что замер идёт в реальном времени. */
private const val PULSE_CYCLE_MS = 3000.0

@Composable
fun FramePulseStrip(
    frameTimeNanos: () -> Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val t = frameTimeNanos()
    Canvas(modifier) {
        val mid = size.height / 2
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 1.5.dp.toPx(),
        )
        if (t <= 0L) return@Canvas
        val phase = ((t / 1_000_000.0) % PULSE_CYCLE_MS) / PULSE_CYCLE_MS
        drawCircle(
            color = color,
            radius = (size.height / 2) * 0.8f,
            center = Offset((phase.toFloat() * size.width).coerceIn(0f, size.width), mid),
        )
    }
}
