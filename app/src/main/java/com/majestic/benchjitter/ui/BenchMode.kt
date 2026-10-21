package com.majestic.benchjitter.ui

// Единый источник названий режимов: и шапки в UI, и строки «режим:» в отчётах.
// Нигде больше названия режимов не хардкодятся (правило проверено пользователем).
enum class BenchMode(val label: String) {
    FRAMES("Джиттер кадров"),
    WAKE("Задержки потока"),
    CPU("Стресс CPU"),
    RAM("Стресс RAM"),
    COMBO("Стресс CPU+RAM"),
    GPU("Стресс GPU"),
    ROM("Стресс ROM"),
    DEVICE("Устройство"),
}
