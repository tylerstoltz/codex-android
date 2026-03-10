package com.local.codexmobile.data

import android.content.Context
import com.local.codexmobile.model.DEFAULT_CLEAR_COMMAND
import com.local.codexmobile.model.DEFAULT_AUTO_SEND_GRACE_PERIOD_MS
import com.local.codexmobile.model.DEFAULT_INTERRUPT_COMMAND
import com.local.codexmobile.model.DEFAULT_SEND_COMMAND
import com.local.codexmobile.model.ServerConfig
import com.local.codexmobile.model.VoiceControlSettings
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("codex_mobile", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadServers(): List<ServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerConfig>>(raw) }
            .getOrDefault(emptyList())
    }

    fun saveServers(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, json.encodeToString(servers)).apply()
    }

    fun createServer(name: String, host: String, port: Int): ServerConfig {
        return ServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port
        )
    }

    fun loadRecentCwds(): List<String> {
        val raw = prefs.getString(KEY_RECENT_CWDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    fun addRecentCwd(cwd: String) {
        val trimmed = cwd.trim()
        if (trimmed.isEmpty()) return
        val current = loadRecentCwds().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val capped = current.take(10)
        prefs.edit().putString(KEY_RECENT_CWDS, json.encodeToString(capped)).apply()
    }

    fun loadVoiceControlSettings(): VoiceControlSettings {
        return VoiceControlSettings(
            enabled = prefs.getBoolean(KEY_VOICE_CONTROL_ENABLED, false),
            readResponsesAloud = prefs.getBoolean(KEY_VOICE_READ_RESPONSES_ALOUD, false),
            autoSendAfterRecognition = prefs.getBoolean(KEY_VOICE_AUTO_SEND_AFTER_RECOGNITION, true),
            autoSendGracePeriodMs = prefs.getLong(
                KEY_VOICE_AUTO_SEND_GRACE_PERIOD_MS,
                DEFAULT_AUTO_SEND_GRACE_PERIOD_MS
            ).coerceIn(0L, 3_000L),
            sendCommand = prefs.getString(KEY_VOICE_COMMAND_SEND, DEFAULT_SEND_COMMAND)
                .orEmpty()
                .ifBlank { DEFAULT_SEND_COMMAND },
            interruptCommand = prefs.getString(KEY_VOICE_COMMAND_INTERRUPT, DEFAULT_INTERRUPT_COMMAND)
                .orEmpty()
                .ifBlank { DEFAULT_INTERRUPT_COMMAND },
            clearCommand = prefs.getString(KEY_VOICE_COMMAND_CLEAR, DEFAULT_CLEAR_COMMAND)
                .orEmpty()
                .ifBlank { DEFAULT_CLEAR_COMMAND }
        )
    }

    fun saveVoiceControlSettings(settings: VoiceControlSettings) {
        prefs.edit()
            .putBoolean(KEY_VOICE_CONTROL_ENABLED, settings.enabled)
            .putBoolean(KEY_VOICE_READ_RESPONSES_ALOUD, settings.readResponsesAloud)
            .putBoolean(KEY_VOICE_AUTO_SEND_AFTER_RECOGNITION, settings.autoSendAfterRecognition)
            .putLong(
                KEY_VOICE_AUTO_SEND_GRACE_PERIOD_MS,
                settings.autoSendGracePeriodMs.coerceIn(0L, 3_000L)
            )
            .putString(KEY_VOICE_COMMAND_SEND, settings.sendCommand.trim().ifBlank { DEFAULT_SEND_COMMAND })
            .putString(
                KEY_VOICE_COMMAND_INTERRUPT,
                settings.interruptCommand.trim().ifBlank { DEFAULT_INTERRUPT_COMMAND }
            )
            .putString(KEY_VOICE_COMMAND_CLEAR, settings.clearCommand.trim().ifBlank { DEFAULT_CLEAR_COMMAND })
            .apply()
    }

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_RECENT_CWDS = "recent_cwds"
        private const val KEY_VOICE_CONTROL_ENABLED = "voice_control_enabled"
        private const val KEY_VOICE_READ_RESPONSES_ALOUD = "voice_read_responses_aloud"
        private const val KEY_VOICE_AUTO_SEND_AFTER_RECOGNITION = "voice_auto_send_after_recognition"
        private const val KEY_VOICE_AUTO_SEND_GRACE_PERIOD_MS = "voice_auto_send_grace_period_ms"
        private const val KEY_VOICE_COMMAND_SEND = "voice_command_send"
        private const val KEY_VOICE_COMMAND_INTERRUPT = "voice_command_interrupt"
        private const val KEY_VOICE_COMMAND_CLEAR = "voice_command_clear"
    }
}
