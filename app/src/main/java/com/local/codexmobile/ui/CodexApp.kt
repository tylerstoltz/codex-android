package com.local.codexmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.codexmobile.model.ChatMessage
import com.local.codexmobile.model.ChatRole
import com.local.codexmobile.model.ServerConfig
import com.local.codexmobile.model.ThreadSummary
import com.local.codexmobile.ui.theme.CodexAssistantBubble
import com.local.codexmobile.ui.theme.CodexBg
import com.local.codexmobile.ui.theme.CodexGreen
import com.local.codexmobile.ui.theme.CodexSystemBubble
import com.local.codexmobile.ui.theme.CodexUserBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexApp(viewModel: CodexViewModel = viewModel()) {
    when (viewModel.currentScreen) {
        Screen.SETUP -> SetupScreen(viewModel)
        Screen.CHAT -> ChatScreen(viewModel)
    }

    viewModel.blockingError?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connection Error") },
            text = { Text(message) },
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
                    }
                },
                actions = {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(viewModel: CodexViewModel) {
    var inputText by remember { mutableStateOf("") }
    val messageListState = rememberLazyListState()

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            messageListState.animateScrollToItem(viewModel.messages.lastIndex)
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
                    }
                },
                actions = {
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
            }

            Column(modifier = Modifier.padding(12.dp)) {
                InputSection(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    onInterrupt = viewModel::interruptTurn,
                    isThinking = viewModel.isThinking,
                    enabled = viewModel.isConnected
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    threads.take(8).forEach { thread ->
                        AssistChip(
                            onClick = { onResume(thread) },
                            label = {
                                Text(
                                    if (thread.preview.isBlank()) thread.id else thread.preview,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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
private fun InputSection(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    isThinking: Boolean,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Button(onClick = onInterrupt, enabled = enabled && isThinking) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Interrupt")
            }
        }
    }
}
