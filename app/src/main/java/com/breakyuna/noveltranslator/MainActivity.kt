package com.breakyuna.noveltranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.breakyuna.noveltranslator.nativeapp.NativeProjectApp
import com.breakyuna.noveltranslator.nativeapp.NativeProjectViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: NativeProjectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NativeProjectApp(viewModel)
        }
    }
}

