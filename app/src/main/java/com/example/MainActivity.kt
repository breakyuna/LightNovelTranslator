package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.NovelTranslatorTheme
import com.example.ui.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val strings by viewModel.currentStrings.collectAsState()
            CompositionLocalProvider(LocalAppStrings provides strings) {
                NovelTranslatorTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

