package com.majestic.benchjitter.i18n

import java.util.Locale

enum class AppLang { RU, EN }

/**
 * Язык интерфейса: русский, если системная локаль начинается с «ru», иначе
 * английский. На живом устройстве меняется перезапуском приложения (Android
 * пересоздаёт процесс при смене локали); модульные тесты могут переопределить
 * значение в @Before, чтобы проверки были детерминированными.
 */
var appLang: AppLang = run {
    val tag = Locale.getDefault().language.lowercase()
    if (tag.startsWith("ru")) AppLang.RU else AppLang.EN
}

/** Пара языковых вариантов одной строки: русский / английский. */
fun tr(ru: String, en: String): String = if (appLang == AppLang.RU) ru else en
