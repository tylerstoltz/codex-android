package com.local.codexmobile.model

data class VoiceControlSettings(
    val enabled: Boolean = false,
    val readResponsesAloud: Boolean = false,
    val autoSendAfterRecognition: Boolean = true,
    val autoSendGracePeriodMs: Long = DEFAULT_AUTO_SEND_GRACE_PERIOD_MS,
    val sendCommand: String = DEFAULT_SEND_COMMAND,
    val interruptCommand: String = DEFAULT_INTERRUPT_COMMAND,
    val clearCommand: String = DEFAULT_CLEAR_COMMAND
)

enum class VoiceCommandAction {
    SEND,
    INTERRUPT,
    CLEAR
}

const val DEFAULT_SEND_COMMAND = "send"
const val DEFAULT_INTERRUPT_COMMAND = "interrupt"
const val DEFAULT_CLEAR_COMMAND = "clear"
const val DEFAULT_AUTO_SEND_GRACE_PERIOD_MS = 800L
