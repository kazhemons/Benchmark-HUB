package com.majestic.benchjitter.ui

import com.majestic.benchjitter.i18n.tr

// Единый источник названий режимов: и шапки в UI, и строки «режим:» в отчётах.
// Нигде больше названия режимов не хардкодятся (правило проверено пользователем).
enum class BenchMode(val labelRu: String, val labelEn: String) {
    FRAMES("Джиттер кадров", "Frame Jitter"),
    WAKE("Задержки потока", "Thread Wake Latency"),
    CPU("Стресс CPU", "CPU Stress"),
    RAM("Стресс RAM", "RAM Stress"),
    COMBO("Стресс CPU+RAM", "CPU+RAM Stress"),
    GPU("Стресс GPU", "GPU Stress"),
    ROM("Стресс ROM", "ROM Stress"),
    DEVICE("Устройство", "Device"),
    ;

    /** Заголовок режима на экране и в отчёте — один и тот же, по языку устройства. */
    val label: String
        get() = tr(labelRu, labelEn)
}

/** Варианты теста режима «Стресс GPU»: выбор в UI и строка «тест» в отчёте. */
enum class GpuTest(val labelRu: String, val labelEn: String) {
    BAGEL("Бублик", "Bagel"),
    OCEAN("Океан (WebGPU)", "Ocean (WebGPU)"),
    ;

    val label: String
        get() = tr(labelRu, labelEn)
}
