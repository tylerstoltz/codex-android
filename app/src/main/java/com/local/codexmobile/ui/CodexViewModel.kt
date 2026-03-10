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
import com.local.codexmobile.model.DEFAULT_CLEAR_COMMAND
import com.local.codexmobile.model.DEFAULT_INTERRUPT_COMMAND
import com.local.codexmobile.model.DEFAULT_SEND_COMMAND
import com.local.codexmobile.model.ServerConfig
import com.local.codexmobile.model.ThreadSummary
import com.local.codexmobile.model.VoiceCommandAction
import com.local.codexmobile.model.VoiceControlSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { SETUP, CHAT, VOICE_SETTINGS }

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
    var activeTurnId by mutableStateOf<String?>(null)
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
    var voiceControlSettings by mutableStateOf(VoiceControlSettings())
        private set

    private var client: CodexAppServerClient? = null
    private var lastScreenBeforeVoiceSettings = Screen.SETUP
    private val connectionMutex = Mutex()
    private var reconnectJob: Job? = null
    private var allowAutoReconnect = false

    init {
        val loaded = serverStore.loadServers()
        servers.addAll(loaded)
        selectedServerId = loaded.firstOrNull()?.id
        recentCwds.addAll(serverStore.loadRecentCwds())
        voiceControlSettings = serverStore.loadVoiceControlSettings()
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
            activeTurnId = null
        }
    }

    fun connect() {
        allowAutoReconnect = true
        reconnectJob?.cancel()
        // Clear active thread so we connect to the server cleanly.
        // The user will pick a session from the thread list or start a new one.
        activeThreadId = null
        activeTurnId = null
        messages.clear()
        viewModelScope.launch {
            connectInternal(showBlockingError = true)
        }
    }

    /** Reconnect preserving the current active thread (used from the chat screen). */
    fun reconnect() {
        allowAutoReconnect = true
        reconnectJob?.cancel()
        viewModelScope.launch {
            connectInternal(showBlockingError = true)
        }
    }

    fun disconnect() {
        allowAutoReconnect = false
        reconnectJob?.cancel()
        client?.disconnect()
        client = null
        isConnected = false
        isThinking = false
        activeTurnId = null
        status = "Disconnected"
    }

    fun refreshThreads(updateStatus: Boolean = currentScreen != Screen.CHAT) {
        val rpc = client ?: return
        viewModelScope.launch {
            try {
                refreshThreadsInternal(rpc, updateStatus)
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
                activeTurnId = null
                activeCwd = cwd
                messages.clear()
                status = "Thread started"
                recordCwd(cwd)
                currentScreen = Screen.CHAT
                refreshThreads(updateStatus = false)
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
                activeTurnId = null
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
                    refreshThreads(updateStatus = false)
                }

                messages.add(ChatMessage(role = ChatRole.USER, text = trimmed))
                isThinking = true
                activeTurnId = null
                status = "Running turn"
                activeTurnId = rpc.sendTurn(threadId = threadId, text = trimmed)
            } catch (t: Throwable) {
                isThinking = false
                activeTurnId = null
                val err = "turn/start failed: ${t.message ?: "unknown"}"
                status = err
                blockingError = err
            }
        }
    }

    fun interruptTurn() {
        val rpc = client ?: return
        val threadId = activeThreadId ?: return
        val turnId = activeTurnId ?: run {
            status = "Waiting for turn to start"
            return
        }
        viewModelScope.launch {
            try {
                rpc.interrupt(threadId, turnId)
                status = "Interrupt requested"
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

    fun navigateToVoiceSettings() {
        if (currentScreen != Screen.VOICE_SETTINGS) {
            lastScreenBeforeVoiceSettings = currentScreen
        }
        currentScreen = Screen.VOICE_SETTINGS
    }

    fun navigateBackFromVoiceSettings() {
        currentScreen = if (lastScreenBeforeVoiceSettings == Screen.VOICE_SETTINGS) {
            Screen.SETUP
        } else {
            lastScreenBeforeVoiceSettings
        }
    }

    fun setVoiceControlEnabled(enabled: Boolean) {
        voiceControlSettings = voiceControlSettings.copy(enabled = enabled)
        serverStore.saveVoiceControlSettings(voiceControlSettings)
    }

    fun setReadResponsesAloud(enabled: Boolean) {
        voiceControlSettings = voiceControlSettings.copy(readResponsesAloud = enabled)
        serverStore.saveVoiceControlSettings(voiceControlSettings)
    }

    fun setAutoSendAfterRecognition(enabled: Boolean) {
        voiceControlSettings = voiceControlSettings.copy(autoSendAfterRecognition = enabled)
        serverStore.saveVoiceControlSettings(voiceControlSettings)
    }

    fun setAutoSendGracePeriodMs(gracePeriodMs: Long) {
        voiceControlSettings = voiceControlSettings.copy(autoSendGracePeriodMs = gracePeriodMs.coerceIn(0L, 3_000L))
        serverStore.saveVoiceControlSettings(voiceControlSettings)
    }

    fun updateVoiceCommand(action: VoiceCommandAction, phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) {
            return
        }
        voiceControlSettings = when (action) {
            VoiceCommandAction.SEND -> voiceControlSettings.copy(sendCommand = trimmed)
            VoiceCommandAction.INTERRUPT -> voiceControlSettings.copy(interruptCommand = trimmed)
            VoiceCommandAction.CLEAR -> voiceControlSettings.copy(clearCommand = trimmed)
        }
        serverStore.saveVoiceControlSettings(voiceControlSettings)
    }

    fun resetVoiceCommandsToDefaults() {
        voiceControlSettings = voiceControlSettings.copy(
            sendCommand = DEFAULT_SEND_COMMAND,
            interruptCommand = DEFAULT_INTERRUPT_COMMAND,
            clearCommand = DEFAULT_CLEAR_COMMAND
        )
        serverStore.saveVoiceControlSettings(voiceControlSettings)
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

    private suspend fun refreshThreadsInternal(rpc: CodexAppServerClient, updateStatus: Boolean) {
        val list = rpc.listThreads()
        threads.clear()
        threads.addAll(list.sortedByDescending { it.updatedAt })
        if (updateStatus) {
            status = "Loaded ${threads.size} thread(s)"
        }
    }

    private suspend fun reattachActiveThreadIfNeeded(rpc: CodexAppServerClient) {
        val threadId = activeThreadId ?: run {
            status = "Connected"
            return
        }
        try {
            val response = rpc.resumeThread(threadId, activeCwd.ifBlank { "/tmp" })
            messages.clear()
            messages.addAll(restoreMessagesFromResume(response))
            activeTurnId = null
            isThinking = false
            status = "Resumed ${threadId.take(10)}"
        } catch (_: Throwable) {
            // Thread no longer exists on this server; clear and stay connected
            activeThreadId = null
            activeTurnId = null
            isThinking = false
            status = "Connected"
        }
    }

    private suspend fun connectInternal(showBlockingError: Boolean): Boolean = connectionMutex.withLock {
        val server = selectedServer() ?: run {
            status = "Select a server first"
            if (showBlockingError) {
                blockingError = "Select a server first."
            }
            return false
        }

        return try {
            status = "Connecting to ${server.host}:${server.port}"
            lateinit var rpc: CodexAppServerClient
            rpc = CodexAppServerClient(
                onNotification = { method, params ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (client !== rpc) {
                            return@launch
                        }
                        try {
                            handleNotification(method, params)
                        } catch (_: Throwable) { }
                    }
                },
                onConnectionChanged = { connected ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (client !== rpc) {
                            return@launch
                        }
                        isConnected = connected
                        if (connected) {
                            reconnectJob?.cancel()
                            status = "Connected"
                        } else {
                            isThinking = false
                            activeTurnId = null
                            status = if (allowAutoReconnect) {
                                "Disconnected, reconnecting..."
                            } else {
                                "Disconnected"
                            }
                            if (allowAutoReconnect) {
                                scheduleReconnect()
                            }
                        }
                    }
                },
                onError = { message ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (client !== rpc) {
                            return@launch
                        }
                        val err = "Error: $message"
                        status = if (allowAutoReconnect) {
                            "Disconnected, reconnecting..."
                        } else {
                            err
                        }
                        if (!allowAutoReconnect && showBlockingError) {
                            blockingError = err
                        }
                    }
                }
            )

            client?.disconnect()
            client = rpc
            rpc.connect(server.host, server.port)
            rpc.initialize()
            isConnected = true
            refreshThreadsInternal(rpc, updateStatus = activeThreadId == null && currentScreen != Screen.CHAT)
            reattachActiveThreadIfNeeded(rpc)
            true
        } catch (t: Throwable) {
            client?.disconnect()
            client = null
            isConnected = false
            val err = "Connect failed: ${t.message ?: "unknown"}"
            status = if (allowAutoReconnect && !showBlockingError) {
                "Reconnect failed, retrying..."
            } else {
                err
            }
            if (showBlockingError) {
                blockingError = err
            }
            false
        }
    }

    private fun scheduleReconnect() {
        if (!allowAutoReconnect || reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (allowAutoReconnect && !isConnected) {
                attempt += 1
                val delayMs = when (attempt) {
                    1 -> 1_000L
                    2 -> 2_000L
                    3 -> 5_000L
                    else -> 10_000L
                }
                delay(delayMs)
                if (!allowAutoReconnect || isConnected) {
                    break
                }
                status = "Reconnecting (attempt $attempt)..."
                val connected = connectInternal(showBlockingError = false)
                if (connected) {
                    break
                }
            }
        }
    }

    private fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "turn/started" -> {
                val threadId = params?.get("threadId")?.jsonPrimitive?.contentOrNull
                val turnId = params?.get("turn")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                if (threadId == null || threadId == activeThreadId) {
                    activeTurnId = turnId ?: activeTurnId
                    isThinking = true
                    status = "Thinking"
                }
            }

            "item/agentMessage/delta" -> {
                val delta = params?.get("delta")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    appendAssistantDelta(delta)
                }
            }

            "turn/completed", "codex/event/task_complete" -> {
                val threadId = params?.get("threadId")?.jsonPrimitive?.contentOrNull
                val turnId = params?.get("turn")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                if (threadId == null || threadId == activeThreadId) {
                    if (turnId == null || turnId == activeTurnId) {
                        activeTurnId = null
                        isThinking = false
                        status = "Ready"
                    }
                    refreshThreads(updateStatus = false)
                }
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
                val cmdElement = item["command"]
                val command = when {
                    cmdElement is JsonArray -> cmdElement.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }.joinToString(" ")
                    cmdElement is JsonPrimitive -> cmdElement.contentOrNull.orEmpty()
                    else -> ""
                }
                val exitCode = (item["exitCode"] as? JsonPrimitive)?.contentOrNull ?: "?"
                "Command finished (exit $exitCode): $command"
            }

            "fileChange" -> {
                val fileStatus = (item["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                "File change: $fileStatus"
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
