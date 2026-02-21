package com.local.codexmobile.data

import android.content.Context
import com.local.codexmobile.model.ServerConfig
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

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_RECENT_CWDS = "recent_cwds"
    }
}
