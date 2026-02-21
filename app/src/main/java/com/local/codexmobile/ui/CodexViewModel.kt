package com.local.codexmobile.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.codexmobile.data.CodexAppServerClient
import com.local.codexmobile.data.ServerStore
import com.local.codexmobile.model.ChatMessage
import com.local.codexmobile.model.ChatRole
import com.local.codexmobile.model.ServerConfig
import com.local.codexmobile.model.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { SETUP, CHAT }

class CodexViewModel(application: Application) : AndroidViewModel(application) {
    private val serverStore = ServerStore(application)

    val servers = mutableStateListOf<ServerConfig>()
    val threads = mutableStateListOf<ThreadSummary>()
    val messages = mutableStateListOf<ChatMessage>()
    val recentCwds = mutableStateListOf<String>()

    var selectedServerId by mutableStateOf<String?>(null)
        private set
    var activeThreadId by mutableStateOf<String?>(null)
        private set
    var activeCwd by mutableStateOf("/tmp")
    var status by mutableStateOf("Disconnected")
        private set
    var blockingError by mutableStateOf<String?>(null)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var isThinking by mutableStateOf(false)
        private set
    var currentScreen by mutableStateOf(Screen.SETUP)
        private set

    private var client: CodexAppServerClient? = null

    init {
        val loaded = serverStore.loadServers()
        servers.addAll(loaded)
        selectedServerId = loaded.firstOrNull()?.id
        recentCwds.addAll(serverStore.loadRecentCwds())
    }

    fun selectServer(id: String) {
        selectedServerId = id
    }

    fun addServer(name: String, host: String, port: Int) {
        val server = serverStore.createServer(name = name, host = host, port = port)
        servers.add(server)
        serverStore.saveServers(servers)
        if (selectedServerId == null) {
            selectedServerId = server.id
        }
    }

    fun removeServer(id: String) {
        val wasSelected = selectedServerId == id
        servers.removeAll { it.id == id }
        serverStore.saveServers(servers)
        if (wasSelected) {
            selectedServerId = servers.firstOrNull()?.id
            disconnect()
            threads.clear()
            messages.clear()
            activeThreadId = null
        }
    }

    fun connect() {
        val server = selectedServer() ?: run {
            status = "Select a server first"
            blockingError = "Select a server first."
            return
        }

        viewModelScope.launch {
            try {
                status = "Connecting to ${server.host}:${server.port}"
                val rpc = CodexAppServerClient(
                    onNotification = { method, params ->
                        viewModelScope.launch(Dispatchers.Main) {
                            handleNotification(method, params)
                        }
                    },
                    onConnectionChanged = { connected ->
                        viewModelScope.launch(Dispatchers.Main) {
                            isConnected = connected
                            if (!connected) {
                                isThinking = false
                                status = "Disconnected"
                            }
                        }
                    },
                    onError = { message ->
                        viewModelScope.launch(Dispatchers.Main) {
                            val err = "Error: $message"
                            status = err
                            blockingError = err
                        }
                    }
                )

                client = rpc
                rpc.connect(server.host, server.port)
                rpc.initialize()
                status = "Connected"
                refreshThreads()
            } catch (t: Throwable) {
                val err = "Connect failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
                isConnected = false
            }
        }
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        isConnected = false
        isThinking = false
        status = "Disconnected"
    }

    fun refreshThreads() {
        val rpc = client ?: return
        viewModelScope.launch {
            try {
                val list = rpc.listThreads()
                threads.clear()
                threads.addAll(list.sortedByDescending { it.updatedAt })
                status = "Loaded ${threads.size} thread(s)"
            } catch (t: Throwable) {
                val err = "thread/list failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun startThread(cwd: String) {
        val rpc = client ?: return
        viewModelScope.launch {
            try {
                val threadId = rpc.startThread(cwd = cwd)
                activeThreadId = threadId
                activeCwd = cwd
                messages.clear()
                status = "Thread started"
                recordCwd(cwd)
                currentScreen = Screen.CHAT
                refreshThreads()
            } catch (t: Throwable) {
                val err = "thread/start failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun resumeThread(summary: ThreadSummary) {
        val rpc = client ?: return
        viewModelScope.launch {
            try {
                val response = rpc.resumeThread(summary.id, summary.cwd.ifBlank { "/tmp" })
                activeThreadId = summary.id
                activeCwd = summary.cwd.ifBlank { "/tmp" }
                messages.clear()
                messages.addAll(restoreMessagesFromResume(response))
                status = "Resumed ${summary.id.take(10)}"
                currentScreen = Screen.CHAT
            } catch (t: Throwable) {
                val err = "thread/resume failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun sendMessage(text: String) {
        val rpc = client ?: return
        if (!isConnected) {
            status = "Not connected"
            blockingError = "Not connected."
            return
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                val threadId = activeThreadId ?: rpc.startThread(cwd = activeCwd).also {
                    activeThreadId = it
                    recordCwd(activeCwd)
                    refreshThreads()
                }

                messages.add(ChatMessage(role = ChatRole.USER, text = trimmed))
                isThinking = true
                status = "Running turn"
                rpc.sendTurn(threadId = threadId, text = trimmed)
            } catch (t: Throwable) {
                isThinking = false
                val err = "turn/start failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun interruptTurn() {
        val rpc = client ?: return
        val threadId = activeThreadId ?: return
        viewModelScope.launch {
            try {
                rpc.interrupt(threadId)
                isThinking = false
                status = "Turn interrupted"
            } catch (t: Throwable) {
                val err = "interrupt failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun navigateToSetup() {
        currentScreen = Screen.SETUP
    }

    fun dismissBlockingError() {
        blockingError = null
    }

    private fun recordCwd(cwd: String) {
        serverStore.addRecentCwd(cwd)
        recentCwds.clear()
        recentCwds.addAll(serverStore.loadRecentCwds())
    }

    private fun selectedServer(): ServerConfig? {
        val id = selectedServerId ?: return null
        return servers.firstOrNull { it.id == id }
    }

    private fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "turn/started" -> {
                isThinking = true
                status = "Thinking"
            }

            "item/agentMessage/delta" -> {
                val delta = params?.get("delta")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    appendAssistantDelta(delta)
                }
            }

            "turn/completed", "codex/event/task_complete" -> {
                isThinking = false
                status = "Ready"
                refreshThreads()
            }

            "item/completed" -> {
                val item = params?.get("item")?.jsonObject ?: return
                val type = item["type"]?.jsonPrimitive?.contentOrNull ?: return
                if (type == "agentMessage" || type == "userMessage") {
                    return
                }
                renderSystemItem(type, item)
            }
        }
    }

    private fun appendAssistantDelta(delta: String) {
        val last = messages.lastOrNull()
        if (last?.role == ChatRole.ASSISTANT) {
            messages[messages.lastIndex] = last.copy(text = last.text + delta)
        } else {
            messages.add(ChatMessage(role = ChatRole.ASSISTANT, text = delta))
        }
    }

    private fun renderSystemItem(type: String, item: JsonObject) {
        val text = when (type) {
            "commandExecution" -> {
                val command = item["command"]?.jsonArray?.joinToString(" ") {
                    it.jsonPrimitive.contentOrNull.orEmpty()
                }.orEmpty()
                val exitCode = item["exitCode"]?.jsonPrimitive?.contentOrNull ?: "?"
                "Command finished (exit $exitCode): $command"
            }

            "fileChange" -> {
                val status = item["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                "File change: $status"
            }

            "reasoning" -> "Reasoning updated"
            else -> "Event: $type"
        }

        messages.add(ChatMessage(role = ChatRole.SYSTEM, text = text))
    }

    private fun restoreMessagesFromResume(response: JsonObject): List<ChatMessage> {
        val thread = response["thread"]?.jsonObject ?: return emptyList()
        val turns = thread["turns"]?.jsonArray ?: return emptyList()

        val restored = mutableListOf<ChatMessage>()
        turns.forEach { turn ->
            val items = turn.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
            items.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "userMessage" -> {
                        val content = obj["content"]?.jsonArray ?: JsonArray(emptyList())
                        val text = extractTextFromContent(content)
                        if (text.isNotBlank()) {
                            restored.add(ChatMessage(ChatRole.USER, text))
                        }
                    }

                    "agentMessage" -> {
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull
                            ?: extractTextFromContent(obj["content"]?.jsonArray ?: JsonArray(emptyList()))
                        if (text.isNotBlank()) {
                            restored.add(ChatMessage(ChatRole.ASSISTANT, text))
                        }
                    }

                    else -> Unit
                }
            }
        }

        return restored
    }

    private fun extractTextFromContent(content: JsonArray): String {
        val chunks = content.mapNotNull { part ->
            val p = part.jsonObject
            val type = p["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                p["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }
        return chunks.joinToString("")
    }
}
