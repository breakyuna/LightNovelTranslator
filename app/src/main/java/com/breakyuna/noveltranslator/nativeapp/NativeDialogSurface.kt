package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Content only: the caller keeps one Dialog window alive while changing its active page. */
@Composable
internal fun NativeDialogSurface(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
            }
            Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
