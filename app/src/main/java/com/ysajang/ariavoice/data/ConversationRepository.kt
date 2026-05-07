package com.ysajang.ariavoice.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ConversationEntry(
    val id: Long = System.currentTimeMillis(),
    val query: String,
    val answer: String,
    val confidence: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val pendingConfirmation: Boolean = false,
    val confirmationId: String? = null
)

class ConversationRepository {

    companion object {
        private const val MAX_HISTORY = 50
    }

    private val _conversations = MutableStateFlow<List<ConversationEntry>>(emptyList())
    val conversations: StateFlow<List<ConversationEntry>> = _conversations.asStateFlow()

    fun addEntry(entry: ConversationEntry) {
        val current = _conversations.value.toMutableList()
        current.add(0, entry)
        if (current.size > MAX_HISTORY) {
            _conversations.value = current.take(MAX_HISTORY)
        } else {
            _conversations.value = current
        }
    }

    fun updateConfirmation(confirmationId: String, confirmed: Boolean) {
        _conversations.value = _conversations.value.map { entry ->
            if (entry.confirmationId == confirmationId) {
                entry.copy(
                    pendingConfirmation = false,
                    answer = if (confirmed) "${entry.answer} ✓" else "${entry.answer} ✗"
                )
            } else entry
        }
    }

    fun clear() {
        _conversations.value = emptyList()
    }
}
