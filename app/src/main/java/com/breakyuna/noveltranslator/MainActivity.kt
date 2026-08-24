package com.breakyuna.noveltranslator

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
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.navigation.AppNavigation
import com.breakyuna.noveltranslator.ui.theme.NovelTranslatorTheme
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val strings by viewModel.currentStrings.collectAsState()
            val themeMode by viewModel.themeMode.collectAsState()
            CompositionLocalProvider(LocalAppStrings provides strings) {
                NovelTranslatorTheme(themeMode = themeMode) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

