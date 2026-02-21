package com.local.codexmobile.model

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val role: ChatRole,
    val text: String
)
