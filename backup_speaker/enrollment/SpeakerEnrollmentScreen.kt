package com.ysajang.ariavoice.ui.enrollment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SpeakerEnrollmentScreen(
    viewModel: SpeakerEnrollmentViewModel,
    onEnrollmentComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Auto-navigate on success after short delay
    LaunchedEffect(state) {
        if (state is SpeakerEnrollmentViewModel.State.Success) {
            kotlinx.coroutines.delay(1800)
            onEnrollmentComplete()
        }
    }

    LaunchedEffect(Unit) { viewModel.initialize() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {

        // ── Title ──────────────────────────────────────────────────────────
        Text(
            text  = "화자 등록",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text  = "\"아리아\"라고 또렷하게 말씀해주세요\n(최소 3회, 최대 5회)",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        // ── Progress dots ──────────────────────────────────────────────────
        when (val s = state) {
            is SpeakerEnrollmentViewModel.State.ReadyToRecord  -> ProgressDots(s.count, viewModel.maxSamples)
            is SpeakerEnrollmentViewModel.State.Recording      -> ProgressDots(s.count - 1, viewModel.maxSamples)
            is SpeakerEnrollmentViewModel.State.Processing     -> ProgressDots(s.count, viewModel.maxSamples)
            is SpeakerEnrollmentViewModel.State.Success        -> ProgressDots(s.sampleCount, viewModel.maxSamples)
            else -> ProgressDots(0, viewModel.maxSamples)
        }

        Spacer(Modifier.height(12.dp))

        // ── Central state widget ───────────────────────────────────────────
        when (val s = state) {
            is SpeakerEnrollmentViewModel.State.Idle,
            is SpeakerEnrollmentViewModel.State.Initializing -> {
                CircularProgressIndicator()
                Text("모델 로딩 중...", style = MaterialTheme.typography.bodySmall)
            }

            is SpeakerEnrollmentViewModel.State.ReadyToRecord -> {
                val label = if (s.count == 0) "녹음 시작" else "${s.count + 1}번째 녹음"
                MicButton(label = label, enabled = true) { viewModel.recordUtterance() }

                if (s.count >= viewModel.targetSamples) {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick  = { viewModel.finalize() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("등록 완료 (${s.count}회 수집)")
                    }
                }
            }

            is SpeakerEnrollmentViewModel.State.Recording -> {
                PulsingMic()
                Text(
                    "${s.count}번째 녹음 중...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            is SpeakerEnrollmentViewModel.State.Processing -> {
                CircularProgressIndicator()
                Text("처리 중...", style = MaterialTheme.typography.bodySmall)
            }

            is SpeakerEnrollmentViewModel.State.Success -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    "등록 완료! (${s.sampleCount}회)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is SpeakerEnrollmentViewModel.State.Error -> {
                Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { viewModel.reset() }) { Text("다시 시도") }
            }
        }

        // ── Back / Skip ────────────────────────────────────────────────────
        AnimatedVisibility(state !is SpeakerEnrollmentViewModel.State.Recording
                && state !is SpeakerEnrollmentViewModel.State.Processing) {
            TextButton(onClick = onBack) {
                Text("취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun ProgressDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until total) {
            Box(
                modifier = Modifier
                    .size(if (i < filled) 14.dp else 10.dp)
                    .background(
                        color  = if (i < filled) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.surfaceVariant,
                        shape  = CircleShape,
                    )
            )
        }
    }
}

@Composable
private fun MicButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick   = onClick,
        enabled   = enabled,
        shape     = CircleShape,
        modifier  = Modifier.size(120.dp),
        colors    = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(label, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PulsingMic() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue   = 1f,
        targetValue    = 1.2f,
        animationSpec  = infiniteRepeatable(
            animation    = tween(600, easing = FastOutSlowInEasing),
            repeatMode   = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector      = Icons.Default.Mic,
            contentDescription = null,
            tint             = MaterialTheme.colorScheme.error,
            modifier         = Modifier.size(48.dp),
        )
    }
}
