package com.laoke.weshirt.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laoke.weshirt.ui.user.UserFeatureSession

@Composable
internal fun AssistantComposer(
    input: String,
    onInput: (String) -> Unit,
    photo: AssistantPhoto?,
    onPhoto: (AssistantPhoto?) -> Unit,
    deepThinkingAvailable: Boolean,
    deepThink: Boolean,
    onDeepThink: () -> Unit,
    canSend: Boolean,
    streaming: Boolean,
    onSend: () -> Unit,
    safetyNotice: String?,
    session: UserFeatureSession,
    conversationId: String,
) {
    val colors = MaterialTheme.colorScheme
    val controlsEnabled = !session.busy && !streaming
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (safetyNotice ?: session.error)?.let { notice ->
            Surface(shape = RoundedCornerShape(12.dp), color = colors.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(notice, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
            }
        }
        AssistantVoiceInput(session = session, enabled = controlsEnabled, onText = { recognized ->
            onInput(listOf(input.trimEnd(), recognized).filter(String::isNotBlank).joinToString(" ").take(MAX_QUESTION_LENGTH))
        }) { voiceButton, voiceFeedback ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("assistant-composer"),
                shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column {
                    photo?.let {
                        Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                            AssistantPhotoPreview(it, enabled = controlsEnabled) { onPhoto(null) }
                        }
                    }
                    BasicTextField(
                        value = input, onValueChange = { onInput(it.take(MAX_QUESTION_LENGTH)) },
                        enabled = controlsEnabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
                            .testTag("assistant-chat-input").semantics { contentDescription = "想问什么？" },
                        decorationBox = { editor ->
                            Box {
                                if (input.isEmpty()) Text("想问什么？", color = colors.onSurfaceVariant)
                                editor()
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantPhotoInput(session, conversationId, enabled = controlsEnabled, onPhoto = onPhoto)
                        voiceButton()
                        if (deepThinkingAvailable) {
                            FilterChip(selected = deepThink, onClick = onDeepThink, enabled = controlsEnabled,
                                label = { Text("深度思考", maxLines = 1) })
                        }
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(
                            onClick = onSend, enabled = canSend,
                            modifier = Modifier.testTag("assistant-chat-send"),
                        ) { Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
                    }
                    voiceFeedback()
                }
            }
        }
    }
}

private const val MAX_QUESTION_LENGTH = 1000
