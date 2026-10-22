plugins {
    id("com.android.application")
    // Compose-компилятор обязателен и при built-in Kotlin (AGP 9 сам его не подключает)
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.majestic.benchjitter"
    // Compose 1.12 (BOM 2026.09.00) требует compileSdk 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.majestic.benchjitter"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "1.5.1"
    }

    buildTypes {
        release {
            // Минимизация: R8 + удаление неиспользуемых ресурсов.
            // Подпись release-ключом bench-jitter-release.jks (в git не попадает).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseKey = rootProject.file("bench-jitter-release.jks")
            if (releaseKey.exists()) {
                signingConfig = signingConfigs.create("releaseKey") {
                    storeFile = releaseKey
                    storePassword = "benchjitter"
                    keyAlias = "benchjitter"
                    keyPassword = "benchjitter"
                }
            }
        }
    }

    packaging {
        // AGP хранит classes.dex в APK без сжатия; для ручной установки сжатие выгоднее
        dex { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Встроенный Kotlin AGP 9: jvmTarget задаётся через kotlin { compilerOptions { } }
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
}
