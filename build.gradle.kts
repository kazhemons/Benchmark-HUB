// Корневой проект. AGP 9 содержит встроенную поддержку Kotlin:
// org.jetbrains.kotlin.android здесь НЕ объявляется (несовместим с новым DSL AGP 9).
// KGP поднимается до 2.4.21 через buildscript classpath (документированный способ AGP 9):
// нужен Kotlin 2.4.x, чтобы читать метаданные библиотек Compose 1.12.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
