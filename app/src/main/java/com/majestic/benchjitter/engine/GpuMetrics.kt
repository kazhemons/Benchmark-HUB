package com.majestic.benchjitter.engine

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import com.majestic.benchjitter.stats.JitterStats
import com.majestic.benchjitter.stats.StatsSnapshot

/**
 * Время GPU-стадии кадра: длительность из FrameMetrics.getMetric(GPU_DURATION).
 * В API 37 listener FrameMetrics открыт на Window (addOnFrameMetricsAvailableListener).
 * attach() пытается подписаться; supported отражает успех — на устройствах без
 * этого API подписку принять не удалось (честно сообщаем в UI).
 */
class GpuMetrics {

    val gpuStats = JitterStats() // мкс GPU-стадии на кадр
    private var attachedWindow: Window? = null
    private var listenerAttached = false
    private val handler = Handler(Looper.getMainLooper())

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val gpuNs = metrics.getMetric(FrameMetrics.GPU_DURATION)
        if (gpuNs > 0L) gpuStats.record(gpuNs / 1_000.0)
    }

    val supported: Boolean get() = listenerAttached

    fun attach(window: Window) {
        if (attachedWindow != null) return
        val ok = runCatching {
            window.addOnFrameMetricsAvailableListener(listener, handler)
        }.isSuccess
        if (ok) {
            attachedWindow = window
            listenerAttached = true
        }
    }

    fun detach() {
        val w = attachedWindow ?: return
        attachedWindow = null
        listenerAttached = false
        runCatching { w.removeOnFrameMetricsAvailableListener(listener) }
    }

    fun reset() = gpuStats.reset()

    fun snapshot(): StatsSnapshot = gpuStats.snapshot()
}
