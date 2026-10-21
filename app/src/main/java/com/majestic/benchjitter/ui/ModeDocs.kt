package com.majestic.benchjitter.ui

import com.majestic.benchjitter.i18n.tr

/**
 * Паспорт теста для подвала экрана: название, техническое описание методики и
 * описание каждого измерителя — по одному на строку. Текст намеренно честный:
 * где метрика косвенная (чтение через кэш, GPU-стадия не на всех устройствах)
 * — это написано прямо в описании.
 */
internal class ModeDoc(
    val title: String,
    val tech: String,
    val values: List<Pair<String, String>>,
)

private val statsCommon = listOf(
    tr("Среднее (mean)", "Mean (mean)") to tr(
        "среднее значение измерений в последние 400 сэмплов",
        "mean of the last 400 measurement samples",
    ),
    tr("Стандартное отклонение (σ)", "Spread (σ)") to tr(
        "разброс значений вокруг среднего — величина нестабильности; чем меньше, тем стабильнее система",
        "spread of values around the mean — the magnitude of instability; the smaller it is, the more stable the system",
    ),
    tr("Минимум / Максимум", "Minimum / Maximum") to tr(
        "наилучшее и наихудшее измерение за весь прогон (накопительные)",
        "best and worst measurement over the whole run (cumulative)",
    ),
    tr("Медиана (p50)", "Median (p50)") to tr(
        "половина измерений лучше этого значения, половина хуже; устойчивая «типичная» величина, нечувствительная к выбросам",
        "half of the measurements are better than this value, half are worse; a robust \"typical\" figure, insensitive to outliers",
    ),
    tr("Перцентили p90 / p95 / p99", "p90 / p95 / p99 percentiles") to tr(
        "пороги, ниже которых оказывается 90/95/99% измерений; хвосты распределения показывают наихудшие 10/5/1% случаев",
        "thresholds below which 90/95/99% of the measurements fall; the tails of the distribution show the worst 10/5/1% of cases",
    ),
)

// Общие для стресс-режимов: наблюдение за нагревом устройства во время теста.
private val thermalCommon = listOf(
    tr("Температура аккумулятора", "Battery temperature") to tr(
        "показание системной информации о батарее, доступно на любом устройстве; ближайшая доступная оценка общего нагрева корпуса; отдельно ведётся максимум за прогон",
        "a reading from system battery information, available on any device; the closest available estimate of overall chassis heating; a peak during run is tracked separately",
    ),
    tr("Температура CPU/GPU/RAM", "CPU/GPU/RAM temperature") to tr(
        "берётся из температурных сенсоров либо системных тепловых зон (/sys/class/thermal) — если производитель устройства их опубликовал; при нескольких источниках одной категории (кластеры) показывается самый горячий",
        "taken from temperature sensors or from the system thermal zones (/sys/class/thermal) — if the device manufacturer publishes them; when several sources belong to one category (clusters), the hottest one is shown",
    ),
    tr("Тепловой статус устройства", "Thermal status") to tr(
        "системная оценка перегрева по шкале Android (Android 10+): норма → лёгкий → умеренный → сильный → критический → аварийный → готовность к thermal shutdown; с умеренного уровня начинается активный троттлинг",
        "the system's overheating assessment on the Android scale (Android 10+): none → light → moderate → severe → critical → emergency → shutting down; active throttling begins at the moderate level",
    ),
)

internal fun modeDoc(mode: BenchMode): ModeDoc = when (mode) {
    BenchMode.FRAMES -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Приложение непрерывно рендерит анимацию и берёт метку старта каждого кадра через withFrameNanos (источник метки — сигнал vsync, тот же, что у Choreographer). Интервал между соседними метками = фактическое время кадра дисплея. Номинальный период вычисляется как медиана последних 120 интервалов (= период экрана). Пропущенным считается кадр, интервал которого длиннее 1,5 номинального. Паузы приложения (гэпы длиннее 100 мс: сворачивание, блокировка экрана) в статистику не попадают, но считаются отдельно. Статистика считается по последним 400 интервалам, min/max — накопительные.",
            "The app continuously renders an animation and takes the start timestamp of every frame through withFrameNanos (the timestamp source is the vsync signal, the same one Choreographer uses). The interval between adjacent timestamps = the actual display frame time. The nominal period is computed as the median of the last 120 intervals (= the screen period). A frame counts as skipped when its interval is longer than 1.5 times the nominal one. App pauses (gaps longer than 100000 µs: minimizing the app, locking the screen) do not enter the statistics but are counted separately. Statistics are computed over the last 400 intervals; min/max are cumulative.",
        ),
        values = listOf(
            tr("Номинал (Гц и период)", "Nominal (Hz and period)") to tr(
                "медианный интервал кадра; показывает, на какой частоте реально работает экран (может отличаться от максимум, заявленного в настройках)",
                "median frame interval; shows at which frequency the screen actually runs (it may differ from the maximum stated in the settings)",
            ),
            tr("Замерено FPS", "Measured FPS") to tr(
                "фактическое число отрисованных кадров в секунду за время прогона",
                "actual number of frames rendered per second during the run",
            ),
            tr("Пропущено кадров", "Frames skipped") to tr(
                "количество интервалов длиннее 1,5 номинала — провалы рисования",
                "number of intervals longer than 1.5 times the nominal — drawing failures",
            ),
            tr("Гэпы после паузы", "Gaps after a pause") to tr(
                "разрывы рендеринга длиннее 100 мс (пауза приложения, блокировка) — это не дефект, но их видно на графике как отдельные точки",
                "rendering breaks longer than 100000 µs (app pause, screen lock) — this is not a defect, but they are visible on the chart as separate points",
            ),
            tr("График «отклонение от номинала»", "\"Deviation from nominal\" chart") to tr(
                "штрих выше центральной линии — кадр запаздывал, ниже — спешил; красные — превышен порог пропуска",
                "a stroke above the centre line means the frame was late, below it means it ran ahead; red strokes mean the skip threshold was exceeded",
            ),
            tr("Пульс", "Pulse") to tr(
                "полоса, синхронизируемая с реальными кадрами: ровный бег = кадровый ритм не ломается",
                "a bar synchronized with real frames: an even run = the frame rhythm is not broken",
            ),
        ) + statsCommon,
    )

    BenchMode.WAKE -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Отдельный поток выбранного приоритета планирования ждёт абсолютных дедлайнов: цикл LockSupport.parkNanos до точной метки времени с репарком (ложные пробуждения не считаются). Дедлайны выстраиваются через равные интервалы независимо от того, когда поток проснулся фактически. Измеряется опоздание = фактическое пробуждение минус дедлайн. Просроченной считается метка, если опоздание больше половины интервала. Статистика по последним 400 опозданиям, min/max — накопительные.",
            "A separate thread of the chosen scheduling priority waits for absolute deadlines: a LockSupport.parkNanos loop up to an exact time mark, with re-parking (spurious wake-ups are not counted). Deadlines are laid out at equal intervals regardless of when the thread actually woke up. Lateness is measured = actual wake-up minus the deadline. A mark counts as missed when the lateness is more than half of the interval. Statistics over the last 400 lateness values; min/max are cumulative.",
        ),
        values = listOf(
            tr("Интервал пробуждения", "Wake-up interval") to tr(
                "как часто поток должен просыпаться (0,5–10 мс); смена интервала начинает новый прогон",
                "how often the thread must wake up (500–10000 µs); changing the interval starts a new run",
            ),
            tr("Приоритет потока", "Thread priority") to tr(
                "класс планирования ОС: «Фон», «Обычный», «Хороший», «Срочный» — чем выше, тем агрессивнее ОС отдаёт поток процессору",
                "the OS scheduling class: Background, Common, Good, Critical — the higher it is, the more aggressively the OS gives the thread to the processor",
            ),
            tr("Просрочено меток", "Missed marks") to tr(
                "сколько пробуждений опоздали больше чем на половину интервала — прямые провалы планировщика",
                "how many wake-ups were late by more than half of the interval — outright scheduler failures",
            ),
            tr("Идеал", "Ideal") to tr(
                "всё распределение вплотную у нуля; типичная поправка ОС — единицы микросекунд–миллисекунды в зависимости от планировщика",
                "the whole distribution sits right next to zero; a typical OS correction is single-digit microseconds to milliseconds depending on the scheduler",
            ),
        ) + statsCommon,
    )

    BenchMode.CPU -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "N потоков (1 / 2 / 4 / все ядра) выполняют цепочку взаимозависимых float-операций (квадратный корень, каждое звено зависит от предыдущего — свернуть или выкинуть их оптимизатор не может). Квант = 100 000 операций. Каждую секунду измеритель снимает, сколько квантов завершили все потоки, — получается профиль скорости из окон по 1 секунде. По профилю считаются mean/σ/перцентили; скорость приводится к Моп/с (миллионам операций в секунду суммарно).",
            "N threads (1 / 2 / 4 / all cores) run a chain of interdependent float operations (square root, every link depends on the previous one — the optimizer cannot fold them or drop them). A quantum = 100000 operations. Every second the meter samples how many quanta all threads have completed — the result is a speed profile made of 1-second windows. mean/σ/percentiles are computed over the profile; speed is normalized to Mops/s (millions of operations per second in total).",
        ),
        values = listOf(
            tr("Потоки", "Threads") to tr(
                "число одновременно работающих рабочих потоков; смена числа перезапускает измерение",
                "number of worker threads running at the same time; changing the number restarts the measurement",
            ),
            tr("Средний темп (Моп/с)", "Mean rate (Mops/s)") to tr(
                "средняя суммарная скорость — главный сравнительный показатель между устройствами и режимами энергосбережения",
                "average total speed — the main comparison metric between devices and between power-saving modes",
            ),
            tr("Выполнено операций", "Operations completed") to tr(
                "суммарное число выполненных float-операций всеми потоками за прогон",
                "total number of float operations performed by all threads over the run",
            ),
            tr("Относительный разброс", "Relative spread") to tr(
                "σ как процент от среднего темпа: тепловой и энерго троттлинг; ровный прогресс <1–2%",
                "σ as a percentage of the mean rate: thermal and power throttling; an even progression is <1–2%",
            ),
        ) + statsCommon + thermalCommon,
    )

    BenchMode.RAM -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Поток по кругу выделяет чанки по 16 МБ и заполняет их целиком (касание каждой страницы переводит память из виртуальной в физическую). Все чанки удерживаются в списке — куча растёт, пока позволяет лимит кучи процесса (Runtime maxMemory минус уже занятое минус запас 48 МБ) или пока не случается OutOfMemoryError. Измеряется время заполнения каждого чанка — по этим временам считается распределение.",
            "A thread in a loop allocates 16 MB chunks and fills them completely (touching every page moves memory from virtual to physical). All chunks are kept in a list — the heap grows for as long as the process heap limit allows (Runtime maxMemory minus what is already used minus a 48 MB reserve) or until an OutOfMemoryError happens. The fill time of each chunk is measured — the distribution is computed from those times.",
        ),
        values = listOf(
            tr("Удерживается", "Retained") to tr(
                "суммарный объём заполненной и удерживаемой памяти — фактически достигнутый потолок",
                "total volume of filled and retained memory — the ceiling actually reached",
            ),
            tr("Лимит кучи процесса", "Process heap limit") to tr(
                "оценка верхней границы кучи, доступной приложению (не путать с общей памятью устройства)",
                "estimate of the upper boundary of the heap available to the app (not to be confused with the total memory of the device)",
            ),
            tr("Скорость заполнения (МБ/с)", "Fill speed (MB/s)") to tr(
                "производительность подсистемы памяти: выделять + заполнить + привести к резидентности",
                "performance of the memory subsystem: allocate + fill + make resident",
            ),
            tr("Время чанка", "Chunk time") to tr(
                "длительность заполнения одного чанка 16 МБ; по нему считаются mean/σ/p99",
                "time it takes to fill a single 16 MB chunk; mean/σ/p99 are computed from it",
            ),
            tr("Потолок достигнут", "Ceiling reached") to tr(
                "«да» — следующий чанк не влезает (предел найден); «нет» — память продолжает расти",
                "\"yes\" — the next chunk does not fit (the limit has been found); \"no\" — memory keeps growing",
            ),
            tr("Сброс", "Reset") to tr(
                "освобождает всю удержанную память и обнуляет результаты",
                "frees all retained memory and zeroes the results",
            ),
        ) + statsCommon + thermalCommon,
    )

    BenchMode.COMBO -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Движки CPU и RAM работают одновременно: рабочие потоки наматывают операции, а параллельный поток набивает кучу до потолка. Сравнивайте результаты с одиночными тестами «Стресс CPU» и «Стресс RAM»: падение темпа и рост σ показывают, как насыщение памяти мешает процессору держать частоты (и наоборот).",
            "The CPU and RAM engines work at the same time: worker threads churn operations while a parallel thread fills the heap up to its ceiling. Compare the results with the standalone \"CPU Stress\" and \"RAM Stress\" tests: a drop in rate and a rise in σ show how memory saturation stops the processor from holding its frequencies (and the other way round).",
        ),
        values = listOf(
            tr("Часть CPU", "CPU part") to tr(
                "метрики теста «Стресс CPU» в условиях одновременной нагрузки памятью",
                "metrics of the \"CPU Stress\" test under simultaneous memory load",
            ),
            tr("Часть RAM", "RAM part") to tr(
                "метрики теста «Стресс RAM» в условиях загруженных ядер",
                "metrics of the \"RAM Stress\" test with loaded cores",
            ),
        ) + statsCommon + thermalCommon,
    )

    BenchMode.GPU -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Canvas-сцена из ~220 кругов с радиальными градиентами перерисовывается каждый кадр — постоянная нагрузка на GPU (движок HWUI). Интервалы кадров собираются по меткам vsync, как в тесте «Джиттер кадров». Время GPU-стадии кадра — длительность GPU_DURATION из FrameMetrics (подписка addOnFrameMetricsAvailableListener на Window активности). Если средняя GPU-стадия близка к периоду кадра или превышает его — GPU не успевает, и начнутся пропуски. Если устройство подписку не приняло, остаётся только кадровая метрика под нагрузкой.",
            "A Canvas scene of ~220 circles with radial gradients is redrawn every frame — a constant load on the GPU (the HWUI engine). Frame intervals are collected from vsync timestamps, as in the \"Frame Jitter\" test. The GPU stage of a frame is the GPU_DURATION duration from FrameMetrics (an addOnFrameMetricsAvailableListener subscription on the activity's Window). If the average GPU stage is close to the frame period or exceeds it — the GPU does not keep up and skips will begin. If the device does not accept the subscription, only the frame metric under load remains.",
        ),
        values = listOf(
            tr("Замерено FPS тяжёлой сцены", "Measured FPS of the heavy scene") to tr(
                "фактическая скорость отрисовки под нагрузкой; сравнивается с FPS лёгкого режима «Джиттер кадров» — это и есть «просадка» от GPU-работы",
                "actual rendering speed under load; it is compared with the FPS of the light \"Frame Jitter\" mode — that difference is the dip caused by GPU work",
            ),
            tr("GPU-стадия (mean)", "GPU stage (mean)") to tr(
                "среднее время, которое GPU тратит на отрисовку одного кадра; сравнивайте с периодом кадра (16,7 мс при 60 Гц)",
                "average time the GPU spends drawing a single frame; compare it with the frame period (16700 µs at 60 Hz)",
            ),
            tr("GPU-стадия (p95/p99)", "GPU stage (p95/p99)") to tr(
                "самые тяжёлые кадры — сложные композитные сцены или троттлинг GPU",
                "the heaviest frames — complex composite scenes or GPU throttling",
            ),
            tr("Пропущено кадров", "Frames skipped") to tr(
                "провалы рисования под нагрузкой",
                "drawing failures under load",
            ),
        ) + statsCommon + thermalCommon,
    )

    BenchMode.ROM -> ModeDoc(
        title = tr("Тест: ${mode.label}", "Test: ${mode.label}"),
        tech = tr(
            "Цикл с файлом 64 МБ в cacheDir приложения: последовательная запись кусками по 1 МБ; в конце файла — fsync (сбрасывает данные на физический носитель — без него измерялась бы скорость кэша). Затем файл читается обратно (суммирование прочитанных байтов не даёт «прочитать ноль») и удаляется; цикл повторяется, пока идёт прогон. Каждое время куска — сэмпл; по нему считаются МБ/с и перцентили. Чтение идёт через страничный кэш файловой системы, поэтому отражает потолок кэша, а не «сырую» скорость флеша.",
            "A loop with a 64 MB file in the app's cacheDir: sequential writing in 1 MB chunks; at the end of the file — fsync (it flushes the data to the physical medium — without it the speed of the cache would be measured). Then the file is read back (summing the bytes read does not allow \"reading zero\") and deleted; the loop repeats while the run is going on. Every chunk time is a sample; MB/s and percentiles are computed from it. Reading goes through the filesystem page cache, so it reflects the ceiling of the cache, not the \"raw\" speed of the flash.",
        ),
        values = listOf(
            tr("Запись (МБ/с)", "Write (MB/s)") to tr(
                "скорость записи кусков при fsync на файле — основная сравнительная метрика носителя; медиана и хвосты p95/p99 показывают разброс поведения контроллера флеша",
                "chunk write speed with fsync on the file — the main comparison metric of the medium; the median and the p95/p99 tails show the spread of flash controller behaviour",
            ),
            tr("Чтение (МБ/с)", "Read (MB/s)") to tr(
                "скорость чтения свежезаписанного файла из страничного кэша — показывает кэш, а не носитель",
                "read speed of the freshly written file from the page cache — it shows the cache, not the medium",
            ),
            tr("Проходов завершено", "Passes completed") to tr(
                "число полных циклов запись → чтение → удаление",
                "number of complete write → read → delete cycles",
            ),
            tr("Разброс времени куска (σ)", "Chunk time spread (σ)") to tr(
                "нестабильность записи: фоновые задачи, сборка мусора контроллера флеша, нагрев",
                "write instability: background tasks, garbage collection of the flash controller, heating",
            ),
            tr("Свободное место", "Free space") to tr(
                "при нехватке места на разделе прогон останавливается и помечается предупреждением",
                "when the partition runs out of free space the run stops and is marked with a warning",
            ),
        ) + statsCommon + thermalCommon,
    )
    BenchMode.DEVICE -> ModeDoc(
        title = tr("Раздел: ${mode.label}", "Section: ${mode.label}"),
        tech = tr(
            "Справка об устройстве без нагрузочного теста: идентификация и SoC берутся из системных API (на Android 12+ — производитель/модель чипа), максимумы ядер — из файлов cpufreq в /sys/devices/system/cpu, объём памяти — из системной информации о памяти, состояние раздела данных — из измерителя файловой системы, экран — из параметров дисплея. Температуры и диагностика нагрева — те же источники, что и в стресс-режимах.",
            "Device information without a stress test: identification and SoC come from system APIs (on Android 12+ — the chip vendor/model), core maximums come from the cpufreq files in /sys/devices/system/cpu, memory size comes from system memory information, the state of the data partition comes from the filesystem meter, the screen comes from the display parameters. Temperatures and thermal diagnostics use the same sources as in the stress modes.",
        ),
        values = listOf(
            tr("Ядер CPU и максимумы", "CPU cores and maximums") to tr(
                "количество ядер и их номинальные максимальные частоты (группами); буст и ограничения по энергобюджету во время работы не учитываются; ядра без таких файлов не показываются",
                "the number of cores and their nominal maximum frequencies (in groups); boost and power-budget limits during operation are not taken into account; cores without such files are not shown",
            ),
            tr("Оперативная память (видимая приложению)", "RAM (visible to the app)") to tr(
                "полный объём по данным системы — ориентировочная величина: часть памяти занята системой и приложениям недоступна",
                "the full size as reported by the system — an approximate figure: part of the memory is occupied by the system and is not available to apps",
            ),
            tr("Хранилище (раздел данных)", "Storage (data partition)") to tr(
                "свободно/всего на разделе, где живут данные приложения; это ёмкость раздела, а не скорость носителя",
                "free/total on the partition where the app data lives; this is the capacity of the partition, not the speed of the medium",
            ),
            tr("Экран", "Display") to tr(
                "разрешение в пикселях, плотность dpi, нативная частота обновления дисплея (если доступна системе)",
                "resolution in pixels, density in dpi, native display refresh rate (if available to the system)",
            ),
        ) + thermalCommon,
    )
}
