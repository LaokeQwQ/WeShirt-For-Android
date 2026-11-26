package com.laoke.weshirt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskTakeDialog(
    title: String,
    detail: String,
    cancelLabel: String,
    confirmLabel: String,
    busy: Boolean,
    close: () -> Unit,
    submit: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!submitting) close() },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(detail)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        dismissButton = { TextButton(enabled = !submitting, onClick = close) { Text(cancelLabel) } },
        confirmButton = { TextButton(enabled = !busy && !submitting, onClick = {
            submitting = true
            error = null
            submit({ submitting = false; close() }, { message -> error = message; submitting = false })
        }) { Text(if (submitting) "正在确认…" else confirmLabel) } },
    )
}
