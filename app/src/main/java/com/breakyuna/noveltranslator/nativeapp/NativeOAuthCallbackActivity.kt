package com.breakyuna.noveltranslator.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.breakyuna.noveltranslator.MainActivity
import com.breakyuna.noveltranslator.TranslatorApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Exported only for the browser redirect to `lnt-oauth://callback`. The Activity
 * accepts no other scheme/host, performs no network request, consumes the matching
 * PKCE state once and hands the bounded result to [MainActivity] through the flow
 * store. A forged or reparsed callback therefore cannot mint a token.
 */
class NativeOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (!isNativeOAuthCallback(uri)) {
            finish()
            return
        }
        val parameters = nativeOAuthCallbackParameters { name -> uri?.getQueryParameter(name) }
        lifecycleScope.launch {
            val accepted = try {
                val graph = (application as TranslatorApplication).nativeGraph
                graph.oauth.acceptCallback(uri?.scheme, uri?.host, parameters)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            try {
                if (accepted) {
                    try {
                        startActivity(Intent(this@NativeOAuthCallbackActivity, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (_: Exception) {
                        // The one-time result stays in the flow store; the next app resume consumes it.
                    }
                }
            } finally {
                finish()
            }
        }
    }
}

/** The manifest filter is not sufficient; check the exact custom scheme, host and authority. */
internal fun isNativeOAuthCallback(uri: Uri?): Boolean =
    uri != null &&
        NativeOAuthConfig.REDIRECT_SCHEME.equals(uri.scheme, ignoreCase = true) &&
        NativeOAuthConfig.REDIRECT_HOST.equals(uri.host, ignoreCase = true) &&
        !uri.isOpaque &&
        uri.port == -1 &&
        uri.userInfo == null &&
        uri.fragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/")
