package com.local.codexmobile.data

import com.local.codexmobile.model.ThreadSummary
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CodexAppServerClient(
    private val onNotification: (method: String, params: JsonObject?) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val idMutex = Mutex()
    private val writeLock = Any()
    private val random = SecureRandom()
    private var requestId = 0L

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var readerThread: Thread? = null

    @Volatile
    private var closing: Boolean = false

    @Volatile
    var isConnected: Boolean = false
        private set

    suspend fun connect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            disconnect()
            val candidates = candidateHosts(host)
            if (candidates.isEmpty()) {
                error("Invalid URL. raw='$host' normalized=")
            }

            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    connectOne(candidate, port)
                    return@withContext
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            val suffix = lastError?.message?.let { ": $it" }.orEmpty()
            error("Connect failed.  raw='$host'\nnormalized=${candidates.joinToString("|")}$suffix")
        }
    }

    private fun connectOne(host: String, port: Int) {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), 6000)
        sock.soTimeout = 0

        val inStream = BufferedInputStream(sock.getInputStream())
        val outStream = BufferedOutputStream(sock.getOutputStream())

        performHandshake(host, port, inStream, outStream)

        socket = sock
        input = inStream
        output = outStream
        isConnected = true
        closing = false
        onConnectionChanged(true)
        startReaderLoop()
    }

    private fun performHandshake(
        host: String,
        port: Int,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val wsKey = ByteArray(16).also(random::nextBytes)
        val wsKeyBase64 = Base64.getEncoder().encodeToString(wsKey)
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKeyBase64\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }

        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()

        val headerBytes = readHttpHeaders(input)
        val headerText = headerBytes.toString(Charsets.UTF_8)
        val lines = headerText.split("\r\n")
        val status = lines.firstOrNull().orEmpty()
        if (!status.contains(" 101 ")) {
            error("Handshake failed: $status")
        }

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }

        val expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                "$wsKeyBase64$WS_MAGIC_GUID".toByteArray(Charsets.UTF_8)
            )
        )
        val actualAccept = headers["sec-websocket-accept"]
        if (actualAccept != expectedAccept) {
            error("Handshake failed: invalid Sec-WebSocket-Accept")
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 64 * 1024) {
            val b = input.read()
            if (b == -1) error("Handshake failed: unexpected end of stream")
            out.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else 0
                else -> 0
            }
            if (state == 4) {
                return out.toByteArray()
            }
        }
        error("Handshake failed: response headers too large")
    }

    private fun startReaderLoop() {
        val inStream = input ?: return
        readerThread = thread(start = true, name = "CodexWsReader") {
            try {
                while (true) {
                    val frame = readFrame(inStream) ?: break
                    when (frame.opcode) {
                        0x1 -> handleIncoming(frame.payload.toString(Charsets.UTF_8))
                        0x8 -> break
                        0x9 -> sendControlFrame(0xA, frame.payload)
                        else -> Unit
                    }
                }
                if (!closing) {
                    handleTransportFailure("Connection closed")
                }
            } catch (t: Throwable) {
                if (!closing) {
                    handleTransportFailure(t.message ?: "WebSocket failure")
                }
            }
        }
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: BufferedInputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        var payloadLen = b1 and 0x7F
        val masked = (b1 and 0x80) != 0

        if (payloadLen == 126) {
            val ext = readExact(input, 2)
            payloadLen = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        } else if (payloadLen == 127) {
            val ext = readExact(input, 8)
            var longLen = 0L
            for (byte in ext) {
                longLen = (longLen shl 8) or (byte.toLong() and 0xFF)
            }
            if (longLen > Int.MAX_VALUE) error("Frame too large")
            payloadLen = longLen.toInt()
        }

        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, payloadLen)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
        }

        return WsFrame(opcode = opcode, payload = payload)
    }

    private fun readExact(input: BufferedInputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(data, read, size - read)
            if (n <= 0) error("Unexpected end of stream")
            read += n
        }
        return data
    }

    private fun sendControlFrame(opcode: Int, payload: ByteArray = ByteArray(0)) {
        sendFrame(opcode, payload)
    }

    private fun sendText(text: String): Boolean {
        return sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                out.write(0x80 or (opcode and 0x0F))

                val maskBit = 0x80
                when {
                    payload.size < 126 -> out.write(maskBit or payload.size)
                    payload.size <= 0xFFFF -> {
                        out.write(maskBit or 126)
                        out.write((payload.size ushr 8) and 0xFF)
                        out.write(payload.size and 0xFF)
                    }
                    else -> {
                        out.write(maskBit or 127)
                        val len = payload.size.toLong()
                        for (shift in 56 downTo 0 step 8) {
                            out.write(((len ushr shift) and 0xFF).toInt())
                        }
                    }
                }

                val mask = ByteArray(4).also(random::nextBytes)
                out.write(mask)
                for (i in payload.indices) {
                    out.write((payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)) and 0xFF)
                }
                out.flush()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleTransportFailure(message: String) {
        isConnected = false
        onConnectionChanged(false)
        onError(message)
        failAllPending(message)
        disconnectInternal()
    }

    private fun disconnectInternal() {
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        readerThread = null
    }

    private fun candidateHosts(raw: String): List<String> {
        var host = raw.trim()
        host = host.removePrefix("ws://")
        host = host.removePrefix("wss://")
        host = host.removePrefix("WS://")
        host = host.removePrefix("WSS://")
        host = host.removePrefix("\"")
        host = host.removeSuffix("\"")
        host = host.removePrefix("'")
        host = host.removeSuffix("'")
        if (host.contains("/")) {
            host = host.substringBefore("/")
        }
        if (host.startsWith("[") && host.contains("]")) {
            host = host.substringAfter("[").substringBefore("]")
        } else if (host.count { it == ':' } == 1 && host.contains(".")) {
            host = host.substringBeforeLast(":")
        }

        val cleaned = host
            .replace(Regex("[\\u0000-\\u001F\\u007F\\u200B\\uFEFF]"), "")
            .trim()
        val unspaced = cleaned.replace(" ", "")
        val dottedSpaces = cleaned.replace(Regex("\\s+"), ".")

        val out = linkedSetOf<String>()
        if (cleaned.isNotEmpty()) out += cleaned
        if (unspaced.isNotEmpty()) out += unspaced
        if (dottedSpaces.isNotEmpty()) out += dottedSpaces
        return out.toList()
    }

    fun disconnect() {
        closing = true
        sendControlFrame(0x8)
        disconnectInternal()
        isConnected = false
        onConnectionChanged(false)
        failAllPending("Disconnected")
        closing = false
    }

    suspend fun initialize(clientName: String = "codex_mobile", version: String = "0.1.0") {
        sendRequest(
            method = "initialize",
            params = json.encodeToJsonElement(
                InitializeParams(
                    clientInfo = ClientInfo(
                        name = clientName,
                        version = version,
                        title = "Codex Mobile"
                    )
                )
            )
        )
        withContext(Dispatchers.IO) {
            sendNotification("initialized", buildJsonObject {})
        }
    }

    suspend fun listThreads(cwd: String? = null): List<ThreadSummary> {
        val result = sendRequest(
            method = "thread/list",
            params = json.encodeToJsonElement(ThreadListParams(cwd = cwd))
        ).jsonObject

        return result["data"]?.jsonArray?.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ThreadSummary(
                id = id,
                preview = obj["preview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: "/tmp",
                updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            )
        }.orEmpty()
    }

    suspend fun startThread(cwd: String, model: String? = null): String {
        val result = sendRequest(
            method = "thread/start",
            params = json.encodeToJsonElement(ThreadStartParams(cwd = cwd, model = model))
        ).jsonObject

        return result["thread"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: error("Missing thread id")
    }

    suspend fun resumeThread(threadId: String, cwd: String): JsonObject {
        val result = sendRequest(
            method = "thread/resume",
            params = json.encodeToJsonElement(ThreadResumeParams(threadId = threadId, cwd = cwd))
        )
        return result.jsonObject
    }

    suspend fun sendTurn(threadId: String, text: String, model: String? = null, effort: String? = null) {
        sendRequest(
            method = "turn/start",
            params = json.encodeToJsonElement(
                TurnStartParams(
                    threadId = threadId,
                    input = listOf(UserInput(text = text)),
                    model = model,
                    effort = effort
                )
            )
        )
    }

    suspend fun interrupt(threadId: String) {
        sendRequest(
            method = "turn/interrupt",
            params = json.encodeToJsonElement(TurnInterruptParams(threadId = threadId))
        )
    }

    private suspend fun nextRequestId(): String = idMutex.withLock {
        requestId += 1
        requestId.toString()
    }

    private suspend fun sendRequest(method: String, params: JsonElement?): JsonElement {
        val id = nextRequestId()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred

        val payload = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            if (params != null) {
                put("params", params)
            }
        }

        val sent = withContext(Dispatchers.IO) {
            sendText(json.encodeToString(payload))
        }
        if (!sent) {
            pending.remove(id)
            error("Failed to send request: socket not connected")
        }

        return deferred.await()
    }

    private fun sendNotification(method: String, params: JsonElement?) {
        val payload = buildJsonObject {
            put("method", JsonPrimitive(method))
            if (params != null) {
                put("params", params)
            }
        }
        sendText(json.encodeToString(payload))
    }

    private fun sendResult(id: JsonElement, result: JsonElement) {
        val payload = buildJsonObject {
            put("id", id)
            put("result", result)
        }
        sendText(json.encodeToString(payload))
    }

    private fun handleIncoming(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val hasId = obj.containsKey("id")
        val hasMethod = obj["method"] is JsonPrimitive
        val hasResult = obj.containsKey("result")
        val hasError = obj.containsKey("error")

        if (hasId && hasMethod && !hasResult && !hasError) {
            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
            val idValue = obj["id"] ?: return
            when (method) {
                "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                    sendResult(idValue, buildJsonObject { put("decision", JsonPrimitive("accept")) })
                }
                else -> sendResult(idValue, buildJsonObject {})
            }
            return
        }

        if (hasId && (hasResult || hasError)) {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
            val deferred = pending.remove(id) ?: return
            val err = obj["error"]?.jsonObject
            if (err != null) {
                val message = err["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown server error"
                deferred.completeExceptionally(IllegalStateException(message))
            } else {
                deferred.complete(obj["result"] ?: JsonObject(emptyMap()))
            }
            return
        }

        if (hasMethod && !hasId) {
            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
            val params = obj["params"] as? JsonObject
            onNotification(method, params)
        }
    }

    private fun failAllPending(reason: String) {
        val exception = IllegalStateException(reason)
        pending.values.forEach { it.completeExceptionally(exception) }
        pending.clear()
    }

    private companion object {
        const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
