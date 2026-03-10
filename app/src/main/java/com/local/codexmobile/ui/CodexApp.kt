package com.local.codexmobile.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.codexmobile.model.ChatMessage
import com.local.codexmobile.model.ChatRole
import com.local.codexmobile.model.ServerConfig
import com.local.codexmobile.model.ThreadSummary
import com.local.codexmobile.model.VoiceCommandAction
import com.local.codexmobile.model.VoiceControlSettings
import kotlinx.coroutines.delay
import com.local.codexmobile.ui.theme.CodexAssistantBubble
import com.local.codexmobile.ui.theme.CodexBg
import com.local.codexmobile.ui.theme.CodexGreen
import com.local.codexmobile.ui.theme.CodexSystemBubble
import com.local.codexmobile.ui.theme.CodexUserBubble
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexApp(viewModel: CodexViewModel = viewModel()) {
    when (viewModel.currentScreen) {
        Screen.SETUP -> SetupScreen(viewModel)
        Screen.CHAT -> ChatScreen(viewModel)
        Screen.VOICE_SETTINGS -> VoiceSettingsScreen(viewModel)
    }

    viewModel.blockingError?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connection Error") },
            text = { Text(stripMarkdown(message)) },
            confirmButton = {
                Button(onClick = { viewModel.dismissBlockingError() }) {
                    Text("Close")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(viewModel: CodexViewModel) {
    var newServerName by remember { mutableStateOf("") }
    var newServerHost by remember { mutableStateOf("") }
    var newServerPort by remember { mutableStateOf("8390") }
    var cwd by remember { mutableStateOf(viewModel.activeCwd) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Codex Mobile", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = viewModel.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (viewModel.isConnected) CodexGreen else Color.LightGray
                        )
                        AdaptivePathText(path = viewModel.activeCwd)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.navigateToVoiceSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Voice Settings")
                    }
                    if (viewModel.isConnected) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Disconnect")
                        }
                    } else {
                        IconButton(onClick = { viewModel.connect() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Connect")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CodexBg)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServerSection(
                servers = viewModel.servers,
                selectedServerId = viewModel.selectedServerId,
                onSelect = viewModel::selectServer,
                onRemove = viewModel::removeServer,
                newServerName = newServerName,
                onServerNameChange = { newServerName = it },
                newServerHost = newServerHost,
                onServerHostChange = { newServerHost = it },
                newServerPort = newServerPort,
                onServerPortChange = { newServerPort = it },
                onAdd = {
                    val port = newServerPort.toIntOrNull() ?: 8390
                    val name = newServerName.ifBlank { newServerHost }
                    if (newServerHost.isNotBlank()) {
                        viewModel.addServer(name = name, host = newServerHost, port = port)
                        newServerName = ""
                        newServerHost = ""
                        newServerPort = "8390"
                    }
                }
            )

            SessionSection(
                threads = viewModel.threads,
                recentCwds = viewModel.recentCwds,
                cwd = cwd,
                onCwdChange = {
                    cwd = it
                    viewModel.activeCwd = it
                },
                onRefresh = viewModel::refreshThreads,
                onStart = { viewModel.startThread(cwd.ifBlank { "/tmp" }) },
                onResume = viewModel::resumeThread
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(viewModel: CodexViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var inputText by remember { mutableStateOf("") }
    var activeVoiceCaptureMode by remember { mutableStateOf<VoiceCaptureMode?>(null) }
    var voiceInputStatus by remember { mutableStateOf<String?>(null) }
    var isSpeakingResponse by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsPendingCount by remember { mutableStateOf(0) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var previousThinkingForTts by remember { mutableStateOf(viewModel.isThinking) }
    var narrateCurrentTurn by remember { mutableStateOf(false) }
    var narratedAssistantLength by remember { mutableStateOf(0) }
    var pendingSpeechBuffer by remember { mutableStateOf("") }
    var lastAssistantUpdateElapsedMs by remember { mutableStateOf<Long?>(null) }
    var pendingMessageAutoListen by remember { mutableStateOf(false) }
    var pendingAutoSendDraft by remember { mutableStateOf<String?>(null) }
    var pendingAutoSendDeadlineMs by remember { mutableStateOf<Long?>(null) }
    var autoSendStatus by remember { mutableStateOf<String?>(null) }
    var previousThinking by remember { mutableStateOf(viewModel.isThinking) }
    val messageListState = rememberLazyListState()
    val bottomAnchorRequester = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val shouldNarrateResponses =
        viewModel.voiceControlSettings.enabled && viewModel.voiceControlSettings.readResponsesAloud
    val latestAssistantText = viewModel.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.text

    fun clearPendingAutoSend(status: String? = autoSendStatus) {
        if (pendingAutoSendDraft != null) {
            Log.d(VOICE_LOG_TAG, "Auto-send canceled: ${status ?: "cleared"}")
        }
        pendingAutoSendDraft = null
        pendingAutoSendDeadlineMs = null
        autoSendStatus = status
    }

    fun sendDraftImmediately(text: String, reason: String) {
        val trimmed = text.trim()
        clearPendingAutoSend(status = null)
        if (trimmed.isEmpty()) {
            inputText = ""
            return
        }

        if (viewModel.isConnected && !viewModel.isThinking) {
            Log.d(VOICE_LOG_TAG, "Sending draft ($reason, ${trimmed.length} chars)")
            viewModel.sendMessage(trimmed)
            inputText = ""
            autoSendStatus = null
        } else {
            inputText = trimmed
            autoSendStatus = "Auto-send unavailable right now."
        }
    }

    fun scheduleAutoSend(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            clearPendingAutoSend(status = null)
            inputText = ""
            return
        }

        val gracePeriodMs = viewModel.voiceControlSettings.autoSendGracePeriodMs.coerceAtLeast(0L)
        pendingAutoSendDraft = trimmed
        pendingAutoSendDeadlineMs = SystemClock.elapsedRealtime() + gracePeriodMs
        autoSendStatus = formatAutoSendCountdown(gracePeriodMs)
        Log.d(
            VOICE_LOG_TAG,
            "Auto-send scheduled after recognition (${trimmed.length} chars, ${gracePeriodMs}ms grace)"
        )
    }

    fun handleRecognizedMessage(spokenText: String) {
        val command = parseVoiceCommandFromDraft(spokenText, viewModel.voiceControlSettings)
        if (command != null) {
            when (command.action) {
                VoiceCommandAction.SEND -> {
                    sendDraftImmediately(command.payload, reason = "explicit voice send")
                }

                VoiceCommandAction.INTERRUPT -> {
                    clearPendingAutoSend(status = null)
                    inputText = ""
                    if (viewModel.isConnected && viewModel.isThinking) {
                        viewModel.interruptTurn()
                    }
                }

                VoiceCommandAction.CLEAR -> {
                    clearPendingAutoSend(status = "Draft cleared.")
                    inputText = ""
                }
            }
            return
        }

        inputText = spokenText
        if (viewModel.voiceControlSettings.autoSendAfterRecognition) {
            scheduleAutoSend(spokenText)
        } else {
            clearPendingAutoSend(status = "Transcript added to composer.")
        }
    }

    fun stopAssistantSpeechForVoiceInput() {
        if (!shouldNarrateResponses || !isSpeakingResponse) {
            return
        }
        Log.d(VOICE_LOG_TAG, "Stopping TTS so voice capture can resume")
        ttsEngine?.stop()
        ttsPendingCount = 0
        isSpeakingResponse = false
        narrateCurrentTurn = false
        pendingSpeechBuffer = ""
        lastAssistantUpdateElapsedMs = null
    }

    fun applyDraftValue(newValue: String) {
        if (pendingAutoSendDraft != null && newValue.trim() != pendingAutoSendDraft?.trim()) {
            clearPendingAutoSend(status = "Auto-send canceled.")
        }
        val command = parseVoiceCommandFromDraft(newValue, viewModel.voiceControlSettings)
        if (command == null) {
            inputText = newValue
            return
        }

        when (command.action) {
            VoiceCommandAction.SEND -> {
                if (command.payload.isBlank()) {
                    clearPendingAutoSend(status = null)
                    inputText = ""
                } else if (viewModel.isConnected && !viewModel.isThinking) {
                    sendDraftImmediately(command.payload, reason = "manual voice command")
                } else {
                    inputText = command.payload
                }
            }

            VoiceCommandAction.INTERRUPT -> {
                clearPendingAutoSend(status = null)
                if (viewModel.isConnected && viewModel.isThinking) {
                    viewModel.interruptTurn()
                }
                inputText = ""
            }

            VoiceCommandAction.CLEAR -> {
                clearPendingAutoSend(status = "Draft cleared.")
                inputText = ""
            }
        }
    }

    fun enqueueResponseSpeech(text: String) {
        val chunk = text.trim()
        if (chunk.isEmpty() || !shouldNarrateResponses || !ttsReady) {
            return
        }
        val engine = ttsEngine ?: return
        val result = engine.speak(
            chunk,
            TextToSpeech.QUEUE_ADD,
            null,
            "assistant_${System.nanoTime()}"
        )
        if (result == TextToSpeech.SUCCESS) {
            ttsPendingCount += 1
            isSpeakingResponse = true
        }
    }

    fun flushSpeechBuffer(finalFlush: Boolean) {
        while (true) {
            val chunkEnd = findNarrationChunkEnd(pendingSpeechBuffer)
            if (chunkEnd <= 0) {
                break
            }
            val chunk = pendingSpeechBuffer.substring(0, chunkEnd).trim()
            pendingSpeechBuffer = pendingSpeechBuffer.substring(chunkEnd)
            enqueueResponseSpeech(chunk)
        }
        if (finalFlush) {
            val finalChunk = pendingSpeechBuffer.trim()
            pendingSpeechBuffer = ""
            enqueueResponseSpeech(finalChunk)
        }
    }

    DisposableEffect(context) {
        var createdEngine: TextToSpeech? = null
        val initListener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                val engine = createdEngine ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    val languageStatus = engine.setLanguage(Locale.getDefault())
                    if (
                        languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        engine.setLanguage(Locale.US)
                    }
                } else {
                    ttsReady = false
                }
            }
        }
        val engine = TextToSpeech(context.applicationContext, initListener)
        createdEngine = engine

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    isSpeakingResponse = true
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    ttsPendingCount = (ttsPendingCount - 1).coerceAtLeast(0)
                    if (ttsPendingCount == 0) {
                        isSpeakingResponse = false
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone(utteranceId)
            }
        })

        ttsEngine = engine

        onDispose {
            engine.stop()
            engine.shutdown()
            ttsEngine = null
            ttsReady = false
            ttsPendingCount = 0
            isSpeakingResponse = false
            narrateCurrentTurn = false
            narratedAssistantLength = 0
            pendingSpeechBuffer = ""
            lastAssistantUpdateElapsedMs = null
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val captureMode = activeVoiceCaptureMode
        activeVoiceCaptureMode = null
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (captureMode == null) {
            return@rememberLauncherForActivityResult
        }

        if (result.resultCode == Activity.RESULT_OK && spokenText.isNotBlank()) {
            when (captureMode) {
                VoiceCaptureMode.MESSAGE -> {
                    voiceInputStatus = null
                    handleRecognizedMessage(spokenText)
                }

                VoiceCaptureMode.INTERRUPT -> {
                    val command = parseVoiceCommandFromDraft(spokenText, viewModel.voiceControlSettings)
                    if (command?.action == VoiceCommandAction.INTERRUPT) {
                        voiceInputStatus = null
                        viewModel.interruptTurn()
                    } else {
                        voiceInputStatus = "Interrupt phrase not recognized."
                    }
                }
            }
        } else {
            voiceInputStatus = if (captureMode == VoiceCaptureMode.INTERRUPT) {
                "No interrupt phrase captured."
            } else {
                "No speech captured."
            }
        }
    }

    fun startVoiceInput(auto: Boolean, mode: VoiceCaptureMode = VoiceCaptureMode.MESSAGE) {
        if (
            !viewModel.voiceControlSettings.enabled ||
            !viewModel.isConnected ||
            activeVoiceCaptureMode != null
        ) {
            return
        }
        if (mode == VoiceCaptureMode.MESSAGE && viewModel.isThinking) {
            return
        }
        if (mode == VoiceCaptureMode.INTERRUPT && (!viewModel.isThinking || viewModel.activeTurnId == null)) {
            return
        }
        if (mode == VoiceCaptureMode.MESSAGE) {
            pendingMessageAutoListen = false
            clearPendingAutoSend(status = null)
            stopAssistantSpeechForVoiceInput()
        }
        activeVoiceCaptureMode = mode
        voiceInputStatus = when (mode) {
            VoiceCaptureMode.MESSAGE ->
                if (auto) "Listening for next message..." else "Listening..."
            VoiceCaptureMode.INTERRUPT ->
                "Listening for \"${viewModel.voiceControlSettings.interruptCommand}\"..."
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                when (mode) {
                    VoiceCaptureMode.MESSAGE ->
                        if (auto) "Speak your next message" else "Speak your message"
                    VoiceCaptureMode.INTERRUPT ->
                        "Say \"${viewModel.voiceControlSettings.interruptCommand}\" to stop the turn"
                }
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            activeVoiceCaptureMode = null
            voiceInputStatus = "Speech recognizer is not available on this device."
        }
    }

    LaunchedEffect(viewModel.messages.size, latestAssistantText, imeBottom) {
        if (viewModel.messages.isNotEmpty()) {
            bottomAnchorRequester.bringIntoView()
            messageListState.animateScrollBy(Float.MAX_VALUE)
        }
    }

    LaunchedEffect(shouldNarrateResponses) {
        if (!shouldNarrateResponses) {
            ttsEngine?.stop()
            ttsPendingCount = 0
            isSpeakingResponse = false
            narrateCurrentTurn = false
            narratedAssistantLength = latestAssistantText?.length ?: 0
            pendingSpeechBuffer = ""
            lastAssistantUpdateElapsedMs = null
        }
    }

    LaunchedEffect(
        pendingAutoSendDraft,
        pendingAutoSendDeadlineMs,
        viewModel.isConnected,
        viewModel.isThinking
    ) {
        val pendingDraft = pendingAutoSendDraft ?: return@LaunchedEffect
        val deadlineMs = pendingAutoSendDeadlineMs ?: return@LaunchedEffect
        while (pendingAutoSendDraft == pendingDraft && pendingAutoSendDeadlineMs == deadlineMs) {
            if (!viewModel.isConnected) {
                clearPendingAutoSend(status = "Auto-send canceled: disconnected.")
                return@LaunchedEffect
            }
            if (viewModel.isThinking) {
                clearPendingAutoSend(status = null)
                return@LaunchedEffect
            }

            val remainingMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            autoSendStatus = formatAutoSendCountdown(remainingMs)
            if (remainingMs == 0L) {
                sendDraftImmediately(pendingDraft, reason = "post-recognition grace elapsed")
                return@LaunchedEffect
            }
            delay(minOf(100L, remainingMs))
        }
    }

    LaunchedEffect(viewModel.isThinking, shouldNarrateResponses, latestAssistantText) {
        if (!shouldNarrateResponses) {
            previousThinkingForTts = viewModel.isThinking
            return@LaunchedEffect
        }

        if (!previousThinkingForTts && viewModel.isThinking) {
            val assistantAlreadyStarted = viewModel.messages.lastOrNull()?.role == ChatRole.ASSISTANT
            narrateCurrentTurn = true
            narratedAssistantLength = if (assistantAlreadyStarted) 0 else latestAssistantText?.length ?: 0
            pendingSpeechBuffer = ""
            lastAssistantUpdateElapsedMs = if (assistantAlreadyStarted) {
                SystemClock.elapsedRealtime()
            } else {
                null
            }
            ttsEngine?.stop()
            ttsPendingCount = 0
            isSpeakingResponse = false
        }

        previousThinkingForTts = viewModel.isThinking
    }

    LaunchedEffect(
        latestAssistantText,
        narrateCurrentTurn,
        shouldNarrateResponses,
        viewModel.isThinking,
        ttsReady
    ) {
        if (!shouldNarrateResponses || !narrateCurrentTurn || !ttsReady) {
            return@LaunchedEffect
        }

        val text = latestAssistantText.orEmpty()
        if (text.length < narratedAssistantLength) {
            narratedAssistantLength = 0
            pendingSpeechBuffer = ""
            lastAssistantUpdateElapsedMs = null
        }
        if (text.length > narratedAssistantLength) {
            val delta = text.substring(narratedAssistantLength)
            pendingSpeechBuffer += delta
            narratedAssistantLength = text.length
            lastAssistantUpdateElapsedMs = SystemClock.elapsedRealtime()
        }

        flushSpeechBuffer(finalFlush = false)
    }

    LaunchedEffect(
        pendingSpeechBuffer,
        lastAssistantUpdateElapsedMs,
        narrateCurrentTurn,
        shouldNarrateResponses,
        viewModel.isThinking,
        ttsReady
    ) {
        if (
            !shouldNarrateResponses ||
            !narrateCurrentTurn ||
            !viewModel.isThinking ||
            !ttsReady ||
            pendingSpeechBuffer.isBlank()
        ) {
            return@LaunchedEffect
        }

        val lastUpdateMs = lastAssistantUpdateElapsedMs ?: return@LaunchedEffect
        val bufferSnapshot = pendingSpeechBuffer
        val remainingMs = (
            ASSISTANT_SPEECH_IDLE_FLUSH_MS -
                (SystemClock.elapsedRealtime() - lastUpdateMs)
            ).coerceAtLeast(0L)
        if (remainingMs > 0L) {
            delay(remainingMs)
        }

        if (
            shouldNarrateResponses &&
            narrateCurrentTurn &&
            viewModel.isThinking &&
            ttsReady &&
            pendingSpeechBuffer == bufferSnapshot &&
            lastAssistantUpdateElapsedMs == lastUpdateMs
        ) {
            while (true) {
                val chunkEnd = findNarrationChunkEnd(pendingSpeechBuffer, allowPartialFlush = true)
                if (chunkEnd <= 0) {
                    break
                }
                val chunk = pendingSpeechBuffer.substring(0, chunkEnd).trim()
                pendingSpeechBuffer = pendingSpeechBuffer.substring(chunkEnd)
                enqueueResponseSpeech(chunk)
            }
        }
    }

    LaunchedEffect(
        pendingSpeechBuffer,
        lastAssistantUpdateElapsedMs,
        narrateCurrentTurn,
        shouldNarrateResponses,
        viewModel.isThinking,
        ttsReady,
        ttsPendingCount
    ) {
        if (
            !shouldNarrateResponses ||
            !narrateCurrentTurn ||
            viewModel.isThinking ||
            !ttsReady
        ) {
            return@LaunchedEffect
        }

        val lastUpdateMs = lastAssistantUpdateElapsedMs
        val remainingMs = if (lastUpdateMs == null) {
            0L
        } else {
            (
                ASSISTANT_TURN_SETTLE_MS -
                    (SystemClock.elapsedRealtime() - lastUpdateMs)
                ).coerceAtLeast(0L)
        }
        if (remainingMs > 0L) {
            delay(remainingMs)
        }

        if (
            shouldNarrateResponses &&
            narrateCurrentTurn &&
            !viewModel.isThinking &&
            ttsReady &&
            lastAssistantUpdateElapsedMs == lastUpdateMs
        ) {
            flushSpeechBuffer(finalFlush = true)
            if (pendingSpeechBuffer.isBlank() && ttsPendingCount == 0) {
                narrateCurrentTurn = false
                lastAssistantUpdateElapsedMs = null
            }
        }
    }

    LaunchedEffect(
        ttsPendingCount,
        viewModel.isThinking,
        pendingSpeechBuffer,
        narrateCurrentTurn,
        shouldNarrateResponses,
        lastAssistantUpdateElapsedMs
    ) {
        val lastUpdateMs = lastAssistantUpdateElapsedMs
        if (
            shouldNarrateResponses &&
            narrateCurrentTurn &&
            !viewModel.isThinking &&
            pendingSpeechBuffer.isBlank() &&
            ttsPendingCount == 0 &&
            (
                lastUpdateMs == null ||
                    SystemClock.elapsedRealtime() - lastUpdateMs >= ASSISTANT_TURN_SETTLE_MS
                )
        ) {
            narrateCurrentTurn = false
            lastAssistantUpdateElapsedMs = null
        }
    }

    LaunchedEffect(viewModel.voiceControlSettings.enabled) {
        if (!viewModel.voiceControlSettings.enabled) {
            pendingMessageAutoListen = false
            clearPendingAutoSend(status = null)
            voiceInputStatus = null
            return@LaunchedEffect
        }
        if (viewModel.isConnected && !viewModel.isThinking && inputText.isBlank()) {
            pendingMessageAutoListen = true
        }
    }

    LaunchedEffect(
        viewModel.voiceControlSettings.enabled,
        viewModel.voiceControlSettings.autoSendAfterRecognition
    ) {
        if (!viewModel.voiceControlSettings.enabled || !viewModel.voiceControlSettings.autoSendAfterRecognition) {
            clearPendingAutoSend(status = null)
        }
    }

    LaunchedEffect(viewModel.isThinking, viewModel.activeTurnId, viewModel.voiceControlSettings.enabled) {
        if (!viewModel.voiceControlSettings.enabled) {
            previousThinking = viewModel.isThinking
            pendingMessageAutoListen = false
            return@LaunchedEffect
        }
        if (previousThinking && !viewModel.isThinking) {
            pendingMessageAutoListen = true
        }
        previousThinking = viewModel.isThinking
    }

    LaunchedEffect(
        pendingMessageAutoListen,
        viewModel.voiceControlSettings.enabled,
        viewModel.isConnected,
        viewModel.isThinking,
        activeVoiceCaptureMode,
        shouldNarrateResponses,
        isSpeakingResponse
    ) {
        if (
            pendingMessageAutoListen &&
            viewModel.voiceControlSettings.enabled &&
            viewModel.isConnected &&
            !viewModel.isThinking &&
            activeVoiceCaptureMode == null &&
            (
                !shouldNarrateResponses ||
                    (
                        !isSpeakingResponse &&
                            pendingSpeechBuffer.isBlank() &&
                            !narrateCurrentTurn
                        )
                )
        ) {
            pendingMessageAutoListen = false
            startVoiceInput(auto = true, mode = VoiceCaptureMode.MESSAGE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateToSetup() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = viewModel.activeThreadId?.take(12) ?: "New Session",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = viewModel.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (viewModel.isConnected) CodexGreen else Color.LightGray
                        )
                        AdaptivePathText(path = viewModel.activeCwd)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.navigateToVoiceSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Voice Settings")
                    }
                    if (viewModel.isConnected) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Disconnect")
                        }
                    } else {
                        IconButton(onClick = { viewModel.connect() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Connect")
                        }
                    }
                    if (viewModel.isThinking) {
                        IconButton(onClick = { viewModel.interruptTurn() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Interrupt")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CodexBg)
                .imePadding()
        ) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(viewModel.messages) { message ->
                    MessageBubble(message = message)
                }
                item {
                    Spacer(
                        modifier = Modifier
                            .height(1.dp)
                            .bringIntoViewRequester(bottomAnchorRequester)
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                InputSection(
                    value = inputText,
                    onValueChange = ::applyDraftValue,
                    onSend = {
                        sendDraftImmediately(inputText, reason = "manual send button")
                    },
                    onInterrupt = viewModel::interruptTurn,
                    onStartVoiceInput = { startVoiceInput(auto = false, mode = VoiceCaptureMode.MESSAGE) },
                    isThinking = viewModel.isThinking,
                    canInterrupt = viewModel.activeTurnId != null,
                    enabled = viewModel.isConnected,
                    voiceControlSettings = viewModel.voiceControlSettings,
                    isListeningForVoiceInput = activeVoiceCaptureMode != null,
                    voiceInputStatus = voiceInputStatus,
                    autoSendStatus = autoSendStatus
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsScreen(viewModel: CodexViewModel) {
    val settings = viewModel.voiceControlSettings
    var captureTarget by remember { mutableStateOf<VoiceCommandAction?>(null) }
    var captureStatus by remember { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = captureTarget
        captureTarget = null
        if (target == null) {
            return@rememberLauncherForActivityResult
        }

        val phrase = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (result.resultCode == Activity.RESULT_OK && phrase.isNotBlank()) {
            viewModel.updateVoiceCommand(target, phrase)
            captureStatus = "Saved command phrase: \"$phrase\""
        } else {
            captureStatus = "No phrase captured. Try recording again."
        }
    }

    fun recordVoiceCommand(action: VoiceCommandAction, prompt: String) {
        captureStatus = null
        captureTarget = action
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            captureTarget = null
            captureStatus = "Speech recognizer is not available on this device."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBackFromVoiceSettings() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Voice Control", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(CodexBg)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable voice control", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Auto-listen for turns and keep spoken commands as fallbacks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = viewModel::setVoiceControlEnabled
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Read model responses aloud", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Uses Android text-to-speech while voice control is enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                    Switch(
                        checked = settings.readResponsesAloud,
                        onCheckedChange = viewModel::setReadResponsesAloud,
                        enabled = settings.enabled
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-send recognized speech", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "After speech recognition finishes, queue the message to send automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                    Switch(
                        checked = settings.autoSendAfterRecognition,
                        onCheckedChange = viewModel::setAutoSendAfterRecognition,
                        enabled = settings.enabled
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Auto-send grace period", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keeps the recognized transcript visible briefly before it is sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500L, 800L, 1200L).forEach { graceMs ->
                            AssistChip(
                                onClick = { viewModel.setAutoSendGracePeriodMs(graceMs) },
                                enabled = settings.enabled && settings.autoSendAfterRecognition,
                                label = { Text(formatGracePeriodLabel(graceMs)) },
                                colors = if (settings.autoSendGracePeriodMs == graceMs) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0x3324C29A),
                                        labelColor = Color.White
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                }
                            )
                        }
                    }
                }
            }

            if (captureTarget != null) {
                Text("Listening for command phrase...", color = CodexGreen)
            }
            captureStatus?.let { message ->
                Text(message, color = Color.LightGray)
            }

            VoiceCommandRow(
                title = "Send command",
                description = "Fallback spoken phrase that sends immediately.",
                phrase = settings.sendCommand,
                onRecord = {
                    recordVoiceCommand(
                        action = VoiceCommandAction.SEND,
                        prompt = "Speak your Send command phrase"
                    )
                }
            )

            VoiceCommandRow(
                title = "Interrupt command",
                description = "Spoken phrase that interrupts the active model turn.",
                phrase = settings.interruptCommand,
                onRecord = {
                    recordVoiceCommand(
                        action = VoiceCommandAction.INTERRUPT,
                        prompt = "Speak your Interrupt command phrase"
                    )
                }
            )

            VoiceCommandRow(
                title = "Clear command",
                description = "Spoken phrase that clears the unsent draft.",
                phrase = settings.clearCommand,
                onRecord = {
                    recordVoiceCommand(
                        action = VoiceCommandAction.CLEAR,
                        prompt = "Speak your Clear command phrase"
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { viewModel.resetVoiceCommandsToDefaults() }) {
                    Text("Reset defaults")
                }
            }
        }
    }
}

@Composable
private fun VoiceCommandRow(
    title: String,
    description: String,
    phrase: String,
    onRecord: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                Text("Current: \"$phrase\"", style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onRecord) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Record")
            }
        }
    }
}

@Composable
private fun ServerSection(
    servers: List<ServerConfig>,
    selectedServerId: String?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    newServerName: String,
    onServerNameChange: (String) -> Unit,
    newServerHost: String,
    onServerHostChange: (String) -> Unit,
    newServerPort: String,
    onServerPortChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Servers", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = newServerName,
                    onValueChange = onServerNameChange,
                    label = { Text("name") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1.2f),
                    value = newServerHost,
                    onValueChange = onServerHostChange,
                    label = { Text("host") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.weight(0.7f),
                    value = newServerPort,
                    onValueChange = onServerPortChange,
                    label = { Text("port") },
                    singleLine = true
                )
                IconButton(onClick = onAdd, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }

            if (servers.isEmpty()) {
                Text("No servers yet. Add a Tailnet/LAN host.", color = Color.LightGray)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                servers.forEach { server ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AssistChip(
                            onClick = { onSelect(server.id) },
                            label = {
                                Text(
                                    "${server.name} (${server.host}:${server.port})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = if (server.id == selectedServerId) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = Color(0x3324C29A),
                                    labelColor = Color.White
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            }
                        )
                        IconButton(onClick = { onRemove(server.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSection(
    threads: List<ThreadSummary>,
    recentCwds: List<String>,
    cwd: String,
    onCwdChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onResume: (ThreadSummary) -> Unit
) {
    var cwdDropdownExpanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0x221E2A2C))) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = cwdDropdownExpanded,
                    onExpandedChange = {
                        if (recentCwds.isNotEmpty()) cwdDropdownExpanded = it
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        value = cwd,
                        onValueChange = onCwdChange,
                        label = { Text("cwd") },
                        singleLine = true,
                        trailingIcon = {
                            if (recentCwds.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = cwdDropdownExpanded)
                            }
                        }
                    )
                    if (recentCwds.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = cwdDropdownExpanded,
                            onDismissRequest = { cwdDropdownExpanded = false }
                        ) {
                            recentCwds.forEach { recent ->
                                DropdownMenuItem(
                                    text = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        onCwdChange(recent)
                                        cwdDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                Button(onClick = onStart) {
                    Text("New")
                }
            }

            if (threads.isEmpty()) {
                Text("No threads loaded yet", color = Color.LightGray)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    threads.take(8).forEach { thread ->
                        AssistChip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onResume(thread) },
                            label = {
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    AdaptivePathText(
                                        path = thread.cwd,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = if (thread.preview.isBlank()) thread.id else thread.preview,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val (align, color) = when (message.role) {
        ChatRole.USER -> Alignment.CenterEnd to CodexUserBubble
        ChatRole.ASSISTANT -> Alignment.CenterStart to CodexAssistantBubble
        ChatRole.SYSTEM -> Alignment.CenterStart to CodexSystemBubble
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Surface(
            color = color,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Text(
                text = stripMarkdown(message.text),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("```[a-zA-Z]*\\n?"), "")   // opening code fences
        .replace("```", "")                         // closing code fences
        .replace(Regex("`([^`]+)`"), "$1")          // inline code
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // bold
}

@Composable
private fun AdaptivePathText(
    path: String,
    modifier: Modifier = Modifier,
    color: Color = Color.LightGray
) {
    val textStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        Text(
            text = fitPathToWidth(
                path = path,
                maxWidthPx = maxWidthPx,
                style = textStyle,
                textMeasurer = textMeasurer
            ),
            style = textStyle,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

private fun fitPathToWidth(
    path: String,
    maxWidthPx: Int,
    style: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || maxWidthPx <= 0) {
        return trimmed
    }
    if (fitsInWidth(trimmed, maxWidthPx, style, textMeasurer)) {
        return trimmed
    }

    var low = 1
    var high = trimmed.length
    var best = "..."
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = "..." + trimmed.takeLast(mid)
        if (fitsInWidth(candidate, maxWidthPx, style, textMeasurer)) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun fitsInWidth(
    text: String,
    maxWidthPx: Int,
    style: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
): Boolean {
    val result = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = maxWidthPx)
    )
    return result.lineCount <= 1 && result.hasVisualOverflow.not()
}

@Composable
private fun InputSection(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onStartVoiceInput: () -> Unit,
    isThinking: Boolean,
    canInterrupt: Boolean,
    enabled: Boolean,
    voiceControlSettings: VoiceControlSettings,
    isListeningForVoiceInput: Boolean,
    voiceInputStatus: String?,
    autoSendStatus: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (voiceControlSettings.enabled) {
            Text(
                text = "Voice control on: \"${voiceControlSettings.sendCommand}\" / " +
                    "\"${voiceControlSettings.interruptCommand}\" / " +
                    "\"${voiceControlSettings.clearCommand}\"",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )
            voiceInputStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray
                )
            }
            autoSendStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray
                )
            }
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            label = { Text("Message") },
            enabled = enabled,
            minLines = 2,
            maxLines = 6
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = enabled && value.isNotBlank() && !isThinking) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Send")
            }
            Button(onClick = onInterrupt, enabled = enabled && isThinking && canInterrupt) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Interrupt")
            }
            if (voiceControlSettings.enabled) {
                Button(
                    onClick = onStartVoiceInput,
                    enabled = enabled && !isThinking && !isListeningForVoiceInput
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(if (isListeningForVoiceInput) "Listening" else "Speak")
                }
            }
        }
    }
}

private fun findNarrationChunkEnd(buffer: String, allowPartialFlush: Boolean = false): Int {
    if (buffer.isBlank()) {
        return -1
    }

    for (index in buffer.indices) {
        val c = buffer[index]
        if (c == '\n') {
            return index + 1
        }
        if ((c == '.' || c == '!' || c == '?') && index + 1 < buffer.length && buffer[index + 1].isWhitespace()) {
            return index + 1
        }
        if ((c == ',' || c == ';' || c == ':') && index > 20 && index + 1 < buffer.length && buffer[index + 1].isWhitespace()) {
            return index + 1
        }
    }

    if (buffer.length >= 140) {
        val searchEnd = minOf(buffer.length - 1, 180)
        val splitAt = buffer.lastIndexOf(' ', startIndex = searchEnd)
        if (splitAt > 20) {
            return splitAt + 1
        }
    }

    if (allowPartialFlush && buffer.length >= 32) {
        val searchEnd = minOf(buffer.length - 1, 120)
        val splitAt = buffer.lastIndexOf(' ', startIndex = searchEnd)
        if (splitAt > 20) {
            return splitAt + 1
        }
    }

    return -1
}

private data class VoiceCommandMatch(
    val action: VoiceCommandAction,
    val payload: String
)

private enum class VoiceCaptureMode {
    MESSAGE,
    INTERRUPT
}

private data class DraftToken(
    val normalized: String,
    val start: Int
)

private fun parseVoiceCommandFromDraft(
    draft: String,
    settings: VoiceControlSettings
): VoiceCommandMatch? {
    if (!settings.enabled) {
        return null
    }

    val tokens = tokenizeDraft(draft)
    if (tokens.isEmpty()) {
        return null
    }

    val clearWords = normalizeCommandWords(settings.clearCommand)
    if (tokensMatchExact(tokens, clearWords)) {
        return VoiceCommandMatch(VoiceCommandAction.CLEAR, payload = "")
    }

    val interruptWords = normalizeCommandWords(settings.interruptCommand)
    if (tokensMatchExact(tokens, interruptWords)) {
        return VoiceCommandMatch(VoiceCommandAction.INTERRUPT, payload = "")
    }

    val sendWords = normalizeCommandWords(settings.sendCommand)
    val sendStart = suffixStart(tokens, sendWords) ?: return null
    val commandStartOffset = tokens[sendStart].start
    val messagePayload = draft.substring(0, commandStartOffset)
        .trim()
        .trimEnd(',', ';', ':')
        .trimEnd()
    return VoiceCommandMatch(VoiceCommandAction.SEND, payload = messagePayload)
}

private fun tokenizeDraft(text: String): List<DraftToken> {
    return Regex("\\S+").findAll(text).mapNotNull { match ->
        val normalized = normalizeToken(match.value)
        if (normalized.isEmpty()) {
            null
        } else {
            DraftToken(normalized = normalized, start = match.range.first)
        }
    }.toList()
}

private fun normalizeCommandWords(command: String): List<String> {
    return command
        .split(Regex("\\s+"))
        .map { normalizeToken(it) }
        .filter { it.isNotEmpty() }
}

private fun normalizeToken(token: String): String {
    return token
        .lowercase()
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+$"), "")
}

private fun tokensMatchExact(tokens: List<DraftToken>, commandWords: List<String>): Boolean {
    if (commandWords.isEmpty() || tokens.size != commandWords.size) {
        return false
    }
    for (index in commandWords.indices) {
        if (tokens[index].normalized != commandWords[index]) {
            return false
        }
    }
    return true
}

private fun suffixStart(tokens: List<DraftToken>, commandWords: List<String>): Int? {
    if (commandWords.isEmpty() || tokens.size < commandWords.size) {
        return null
    }
    val start = tokens.size - commandWords.size
    for (index in commandWords.indices) {
        if (tokens[start + index].normalized != commandWords[index]) {
            return null
        }
    }
    return start
}

private fun formatAutoSendCountdown(remainingMs: Long): String {
    if (remainingMs <= 0L) {
        return "Sending..."
    }
    return "Sending in %.1fs".format(Locale.US, remainingMs / 1000.0)
}

private fun formatGracePeriodLabel(graceMs: Long): String {
    return "%.1fs".format(Locale.US, graceMs / 1000.0)
}

private const val ASSISTANT_SPEECH_IDLE_FLUSH_MS = 250L
private const val ASSISTANT_TURN_SETTLE_MS = 250L
private const val VOICE_LOG_TAG = "CodexVoice"
