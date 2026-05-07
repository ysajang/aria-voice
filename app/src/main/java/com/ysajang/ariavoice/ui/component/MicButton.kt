package com.ysajang.ariavoice.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.ysajang.ariavoice.service.AriaState
import com.ysajang.ariavoice.ui.theme.AriaBlue
import com.ysajang.ariavoice.ui.theme.AriaError
import com.ysajang.ariavoice.ui.theme.AriaSuccess
import com.ysajang.ariavoice.ui.theme.AriaSurfaceVariant
import com.ysajang.ariavoice.ui.theme.AriaWarning

@Composable
fun MicButton(
    state: AriaState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AriaState.LISTENING_SPEECH) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val bgColor by animateColorAsState(
        targetValue = when (state) {
            AriaState.IDLE -> AriaSurfaceVariant
            AriaState.LISTENING_WAKE_WORD -> AriaBlue.copy(alpha = 0.2f)
            AriaState.LISTENING_SPEECH -> AriaSuccess.copy(alpha = 0.3f)
            AriaState.PROCESSING -> AriaWarning.copy(alpha = 0.3f)
            AriaState.SPEAKING -> AriaBlue.copy(alpha = 0.3f)
            AriaState.ERROR -> AriaError.copy(alpha = 0.3f)
        },
        animationSpec = tween(300),
        label = "bg"
    )

    val iconColor by animateColorAsState(
        targetValue = when (state) {
            AriaState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            AriaState.LISTENING_WAKE_WORD -> AriaBlue
            AriaState.LISTENING_SPEECH -> AriaSuccess
            AriaState.PROCESSING -> AriaWarning
            AriaState.SPEAKING -> AriaBlue
            AriaState.ERROR -> AriaError
        },
        animationSpec = tween(300),
        label = "icon"
    )

    Box(
        modifier = modifier
            .size(120.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (state) {
                AriaState.IDLE -> Icons.Default.MicOff
                AriaState.PROCESSING -> Icons.Default.Sync
                else -> Icons.Default.Mic
            },
            contentDescription = "마이크",
            tint = iconColor,
            modifier = Modifier.size(48.dp)
        )
    }
}
