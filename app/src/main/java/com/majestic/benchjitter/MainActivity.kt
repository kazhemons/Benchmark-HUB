package com.majestic.benchjitter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.majestic.benchjitter.ui.BenchJitterApp

/** Единственный экран. Без разрешений, аккаунтов и настроек. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BenchJitterApp() }
    }
}
