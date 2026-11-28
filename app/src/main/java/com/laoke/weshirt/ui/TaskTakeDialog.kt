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
    onBackgroundFailure: (String) -> Unit = {},
    submit: (onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var background by remember { mutableStateOf(false) }
    val dismiss = { background = submitting; close() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(detail)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        dismissButton = { TextButton(onClick = dismiss) { Text(if (submitting) "后台确认" else cancelLabel) } },
        confirmButton = { TextButton(enabled = !busy && !submitting, onClick = {
            submitting = true
            error = null
            submit({ submitting = false; if (!background) close() }, { message ->
                submitting = false
                if (background) onBackgroundFailure(message) else error = message
            })
        }) { Text(if (submitting) "正在确认…" else confirmLabel) } },
    )
}
