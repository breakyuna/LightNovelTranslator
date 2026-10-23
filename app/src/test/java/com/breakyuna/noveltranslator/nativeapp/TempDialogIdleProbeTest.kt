package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.espresso.IdlingPolicies
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TEMPORARY diagnostic probe for the AppNotIdle failures in NativeWorkspaceScreenshotTest.
 * Each case renders one increment of the settings form; remove after the trigger is known.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class TempDialogIdleProbeTest {
    @get:Rule val compose = createComposeRule()

    @Before fun shorterIdleTimeout() {
        IdlingPolicies.setMasterPolicyTimeout(5, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(5, TimeUnit.SECONDS)
    }

    @Test fun a_dialogText() {
        compose.setContent { NativeTheme { Dialog({}) { Surface { Text("probe") } } } }
    }

    @Test fun b_dialogPlainField() {
        compose.setContent { NativeTheme { Dialog({}) { Surface {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) { OutlinedTextField("", {}) }
        } } } }
    }

    @Test fun c_dialogSingleLineUri() {
        compose.setContent { NativeTheme { Dialog({}) { Surface {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) {
                OutlinedTextField("", {}, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        } } } }
    }

    @Test fun d_dialogPassword() {
        compose.setContent { NativeTheme { Dialog({}) { Surface {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) {
                OutlinedTextField("", {}, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        } } } }
    }

    @Test fun e_dialogSaveable() {
        compose.setContent { NativeTheme { Dialog({}) { Surface {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) {
                val value = rememberSaveable { mutableStateOf("") }
                OutlinedTextField(value.value, { value.value = it })
            }
        } } } }
    }

    @Test fun f_dialogScrollThreeFields() {
        compose.setContent { NativeTheme { Dialog({}) { Surface {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        } } } }
    }

    @Test fun g_fixedWindowScrollThreeFields() {
        compose.setContent { NativeTheme { Dialog({}, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField("", {}, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } } }
    }

    @Test fun h_fullModelForm() {
        compose.setContent { NativeTheme {
            NativeModelDialog(null, false, null, false, {}, { _, _, _, _ -> }, { _, _, _, _ -> }, {})
        } }
    }
}
