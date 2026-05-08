package com.ysajang.ariavoice.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ysajang.ariavoice.data.ConversationEntry
import com.ysajang.ariavoice.service.AriaState
import com.ysajang.ariavoice.ui.component.MicButton
import com.ysajang.ariavoice.ui.theme.AriaCard
import com.ysajang.ariavoice.ui.theme.AriaError
import com.ysajang.ariavoice.ui.theme.AriaOnSurfaceDim
import com.ysajang.ariavoice.ui.theme.AriaSuccess

@Composable
fun MainScreen(
    state: AriaState,
    isServiceRunning: Boolean,
    lastError: String?,
    pendingConfirmation: ConversationEntry?,
    lastConversation: ConversationEntry?,
    wakeWordScore: Float = 0f,
    onMicClick: () -> Unit,
    onToggleService: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title
        Text(
            text = "ARIA",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status text
        Text(
            text = when (state) {
                AriaState.IDLE -> "서비스 꺼짐"
                AriaState.LISTENING_WAKE_WORD -> "\"아리아\" 대기 중..."
                AriaState.LISTENING_SPEECH -> "듣는 중..."
                AriaState.PROCESSING -> "처리 중..."
                AriaState.SPEAKING -> "응답 중..."
                AriaState.ERROR -> "오류 발생"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = AriaOnSurfaceDim
        )

        // Real-time wake word score (diagnostic)
        AnimatedVisibility(visible = state == AriaState.LISTENING_WAKE_WORD && wakeWordScore > 0.001f) {
            Text(
                text = "score: ${"%.4f".format(wakeWordScore)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (wakeWordScore > 0.5f) AriaSuccess else AriaOnSurfaceDim
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Mic button
        MicButton(
            state = state,
            onClick = onMicClick
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Service toggle
        Button(
            onClick = onToggleService,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isServiceRunning) "서비스 중지" else "서비스 시작",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // HITL Confirmation card
        AnimatedVisibility(visible = pendingConfirmation != null) {
            pendingConfirmation?.let { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AriaCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = entry.answer,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    entry.confirmationId?.let { onConfirm(it, false) }
                                }
                            ) {
                                Text("거부")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    entry.confirmationId?.let { onConfirm(it, true) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AriaSuccess
                                )
                            ) {
                                Text("확인")
                            }
                        }
                    }
                }
            }
        }

        // Last conversation preview
        AnimatedVisibility(
            visible = pendingConfirmation == null && lastConversation != null
        ) {
            lastConversation?.let { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AriaCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = entry.query,
                            style = MaterialTheme.typography.labelLarge,
                            color = AriaOnSurfaceDim
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entry.answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3
                        )
                    }
                }
            }
        }

        // Error display
        AnimatedVisibility(visible = lastError != null) {
            lastError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AriaError,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
