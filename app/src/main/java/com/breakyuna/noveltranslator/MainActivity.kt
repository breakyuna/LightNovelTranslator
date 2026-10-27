package com.breakyuna.noveltranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.breakyuna.noveltranslator.nativeapp.NativeAppSettingsStore
import com.breakyuna.noveltranslator.nativeapp.NativeProjectApp
import com.breakyuna.noveltranslator.nativeapp.NativeProjectViewModel
import com.breakyuna.noveltranslator.nativeapp.localizedContext

class MainActivity : ComponentActivity() {
    private val viewModel: NativeProjectViewModel by viewModels()

    /**
     * Applies the persisted interface language before any Activity resource is created.
     * API 17+ configuration contexts keep this independent of AppCompat and minSdk 24.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, NativeAppSettingsStore(newBase).language()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NativeProjectApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.consumeOAuthCallback()
    }

    override fun onResume() {
        super.onResume()
        // The callback Activity stores a one-time result and brings this task forward.
        // resume also covers a process that was recreated while the browser was open.
        viewModel.consumeOAuthCallback()
    }
}

