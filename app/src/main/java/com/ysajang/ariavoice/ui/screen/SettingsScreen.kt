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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ysajang.ariavoice.ui.enrollment.SpeakerEnrollmentViewModel
import com.ysajang.ariavoice.ui.enrollment.SpeakerEnrollmentViewModel.Phase
import com.ysajang.ariavoice.ui.theme.AriaOnSurfaceDim

@Composable
fun SettingsScreen(
    serverUrl: String,
    apiKey: String,
    sensitivity: Float,
    wakeWordModel: String,
    onServerUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSensitivityChange: (Float) -> Unit
) {
    var localUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var localApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var localSensitivity by remember(sensitivity) { mutableFloatStateOf(sensitivity) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "설정",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Server URL
        Text(
            text = "ARIA 서버 URL",
            style = MaterialTheme.typography.labelLarge,
            color = AriaOnSurfaceDim
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = localUrl,
            onValueChange = {
                localUrl = it
                onServerUrlChange(it)
            },
            placeholder = { Text("http://10.0.2.2:8100") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "에뮬레이터: http://10.0.2.2:8100\n실기기 (같은 WiFi): http://PC_IP:8100",
            style = MaterialTheme.typography.labelSmall,
            color = AriaOnSurfaceDim
        )

        Spacer(modifier = Modifier.height(20.dp))

        // API Key
        Text(
            text = "ARIA API Key",
            style = MaterialTheme.typography.labelLarge,
            color = AriaOnSurfaceDim
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = localApiKey,
            onValueChange = {
                localApiKey = it
                onApiKeyChange(it)
            },
            placeholder = { Text("X-API-Key") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Wake word sensitivity
        Text(
            text = "웨이크워드 민감도: ${"%.2f".format(localSensitivity)}",
            style = MaterialTheme.typography.labelLarge,
            color = AriaOnSurfaceDim
        )
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = localSensitivity,
            onValueChange = { localSensitivity = it },
            onValueChangeFinished = { onSensitivityChange(localSensitivity) },
            valueRange = 0.01f..1.0f,
            steps = 98,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Text(
            text = "낮음 (많이 반응) ← → 높음 (정확할 때만 반응)",
            style = MaterialTheme.typography.labelSmall,
            color = AriaOnSurfaceDim
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Wake word model info
        Text(
            text = "현재 웨이크워드 모델",
            style = MaterialTheme.typography.labelLarge,
            color = AriaOnSurfaceDim
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = wakeWordModel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "커스텀 모델은 assets 폴더에 .onnx 파일 추가 후 앱 재빌드",
            style = MaterialTheme.typography.labelSmall,
            color = AriaOnSurfaceDim
        )

        Spacer(modifier = Modifier.height(32.dp))

        HorizontalDivider(color = AriaOnSurfaceDim.copy(alpha = 0.2f))

        Spacer(modifier = Modifier.height(24.dp))

        // ── Speaker Enrollment ────────────────────────────────────────
        SpeakerEnrollmentSection()

        Spacer(modifier = Modifier.height(32.dp))

        // Version info
        Text(
            text = "ARIA Voice v0.1.0",
            style = MaterialTheme.typography.labelSmall,
            color = AriaOnSurfaceDim
        )
    }
}

// ── Speaker Enrollment Section ────────────────────────────────────────────

@Composable
private fun SpeakerEnrollmentSection(
    viewModel: SpeakerEnrollmentViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column {
        Text(
            text = "화자 인증",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status
        val statusText = if (state.isEnrolled) {
            "등록됨 (${state.enrolledCount}개 샘플)"
        } else {
            "미등록 — 등록하면 본인 목소리만 인식합니다"
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = AriaOnSurfaceDim
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Recording progress
        AnimatedVisibility(visible = state.phase != Phase.IDLE && state.phase != Phase.DONE && state.phase != Phase.ERROR) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.phase == Phase.RECORDING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.error,
                        strokeWidth = 3.dp
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Result message
        AnimatedVisibility(visible = state.phase == Phase.DONE || state.phase == Phase.ERROR) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.phase == Phase.DONE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isRecording = state.phase != Phase.IDLE &&
                    state.phase != Phase.DONE &&
                    state.phase != Phase.ERROR

            if (isRecording) {
                OutlinedButton(
                    onClick = { viewModel.cancelEnrollment() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("취소")
                }
            } else {
                Button(
                    onClick = {
                        if (state.phase == Phase.DONE || state.phase == Phase.ERROR) {
                            viewModel.resetToIdle()
                        }
                        viewModel.startEnrollment()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (state.isEnrolled) "다시 등록" else "화자 등록하기")
                }

                if (state.isEnrolled) {
                    OutlinedButton(
                        onClick = { viewModel.clearEnrollment() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("등록 초기화")
                    }
                }
            }
        }

        // Threshold slider (only when enrolled)
        if (state.isEnrolled) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "인증 임계값: ${"%.2f".format(state.threshold)}",
                style = MaterialTheme.typography.labelLarge,
                color = AriaOnSurfaceDim
            )
            Spacer(modifier = Modifier.height(4.dp))

            var localThreshold by remember(state.threshold) { mutableFloatStateOf(state.threshold) }
            Slider(
                value = localThreshold,
                onValueChange = { localThreshold = it },
                onValueChangeFinished = { viewModel.updateThreshold(localThreshold) },
                valueRange = 0.3f..0.95f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = "낮음 (쉽게 통과) ← → 높음 (엄격한 검증)",
                style = MaterialTheme.typography.labelSmall,
                color = AriaOnSurfaceDim
            )
        }
    }
}
