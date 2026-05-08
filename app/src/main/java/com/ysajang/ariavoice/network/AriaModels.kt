package com.ysajang.ariavoice.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AriaRequest(
    val query: String,
    val scope: String = "global"
)

@Serializable
data class AriaResponse(
    val answer: String = "",
    val confidence: Float = 0f,
    @SerialName("tool_calls_made")
    val toolCallsMade: Int = 0,
    @SerialName("pending_confirmation")
    val pendingConfirmation: Boolean = false,
    @SerialName("confirmation_id")
    val confirmationId: String? = null
)

@Serializable
data class AriaConfirmRequest(
    @SerialName("confirmation_id")
    val confirmationId: String,
    val confirmed: Boolean
)

@Serializable
data class TtsRequest(
    val text: String,
    val emotion: String = "neutral"
)
