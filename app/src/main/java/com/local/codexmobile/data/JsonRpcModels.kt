package com.local.codexmobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InitializeParams(
    @SerialName("clientInfo") val clientInfo: ClientInfo
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
    val title: String? = null
)

@Serializable
data class ThreadListParams(
    val cursor: String? = null,
    val limit: Int? = 50,
    @SerialName("sortKey") val sortKey: String = "updated_at",
    val cwd: String? = null
)

@Serializable
data class ThreadStartParams(
    val model: String? = null,
    val cwd: String,
    @SerialName("approvalPolicy") val approvalPolicy: String = "never",
    val sandbox: String = "workspace-write"
)

@Serializable
data class ThreadResumeParams(
    @SerialName("threadId") val threadId: String,
    val cwd: String,
    @SerialName("approvalPolicy") val approvalPolicy: String = "never",
    val sandbox: String = "workspace-write"
)

@Serializable
data class TurnStartParams(
    @SerialName("threadId") val threadId: String,
    val input: List<UserInput>,
    val model: String? = null,
    val effort: String? = null
)

@Serializable
data class UserInput(
    val type: String = "text",
    val text: String
)

@Serializable
data class TurnInterruptParams(
    @SerialName("threadId") val threadId: String
)
