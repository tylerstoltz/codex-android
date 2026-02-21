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

    companion object {
        private const val KEY_SERVERS = "servers"
    }
}
