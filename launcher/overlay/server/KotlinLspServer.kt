// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

package overlay.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Composition-server entry point for a kotlin-lsp distribution enhanced by kotlin-lsp-dev.
 *
 * This process owns the client connection and starts the shipped server as a child over stdio.
 * Most messages pass through untouched; the point of owning the boundary is the ones that cannot
 * be handled in-process at all:
 *
 *  - **Unadvertised capabilities.** The shipped server registers a working
 *    `textDocument/rangeFormatting` handler but omits `documentRangeFormattingProvider` from its
 *    initialize result, so no conformant client ever sends the request. Adding an in-process
 *    provider cannot fix that -- the dispatcher throws on a second `LSFormattingProvider`. Here we
 *    just set the missing flag on the way out.
 *
 *  - **Operations with no provider API at all.** `263.2689.0` ships no `LSDocumentHighlightProvider`
 *    and never advertises `documentHighlightProvider`, so there is nothing to register against.
 *    We advertise it and answer the request ourselves, out of the child's own
 *    `textDocument/references` -- real Kotlin analysis, not a textual approximation.
 *
 * Everything additive still runs inside the child through the normal `LanguageServerExtension`
 * path; this layer is only for what that path cannot reach.
 *
 * Two transports, matching the two ways the official VS Code extension starts a server:
 *
 *   --stdio            own this process's stdin/stdout (most editors, and our smoke harness)
 *   --socket <port>    listen on 127.0.0.1:<port> and serve the client that connects
 *
 * `--socket 0` binds an ephemeral port and announces it on stdout in the launcher's own format,
 * so this is a drop-in for `bin/intellij-server` wherever that is spawned. A fixed port is what
 * the extension's `intellij.dev.serverPort` setting dials.
 */
object KotlinLspServer {
    private const val RANGE_FORMATTING_CAPABILITY = "documentRangeFormattingProvider"
    private const val DOCUMENT_HIGHLIGHT_CAPABILITY = "documentHighlightProvider"
    private const val DOCUMENT_HIGHLIGHT = "textDocument/documentHighlight"
    private const val REFERENCES = "textDocument/references"
    private const val LOOPBACK = "127.0.0.1"

    @JvmStatic
    fun main(args: Array<String>) {
        val launcher = serverHome().resolve("bin/intellij-server")
        require(Files.isRegularFile(launcher)) { "kotlin-lsp launcher not found: $launcher" }

        // Everything except our own transport selection is forwarded to the child untouched --
        // notably --system-path, which the extension uses to give each workspace its own caches.
        val childArgs = passThroughArgs(args)
        when (val transport = transportOf(args)) {
            is Transport.Stdio -> serve(System.`in`, System.out, launcher, childArgs)
            is Transport.Socket -> serveSocket(transport.port, launcher, childArgs)
        }
    }

    private sealed interface Transport {
        object Stdio : Transport
        data class Socket(val port: Int) : Transport
    }

    private fun transportOf(args: Array<String>): Transport {
        args.forEachIndexed { index, arg ->
            when (arg) {
                "--stdio" -> return Transport.Stdio
                // --port is our own spelling; --socket is the launcher's. Accept both.
                "--socket", "--port" -> {
                    val value = args.getOrNull(index + 1)
                        ?: error("$arg requires a port number (0 for an ephemeral port)")
                    val port = value.toIntOrNull()
                        ?: error("$arg expects a port number, got: $value")
                    require(port in 0..65535) { "port out of range: $port" }
                    return Transport.Socket(port)
                }
            }
        }
        error("no transport selected; pass --stdio or --socket <port>")
    }

    private fun passThroughArgs(args: Array<String>): List<String> {
        val kept = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--stdio" -> index++
                "--socket", "--port" -> index += 2
                else -> kept += args[index++]
            }
        }
        return kept
    }

    private fun serveSocket(port: Int, launcher: Path, childArgs: List<String>) {
        ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).use { server ->
            // Same wording the shipped launcher uses, because the VS Code extension scrapes this
            // line off stdout to learn which port to dial.
            println("Server is listening on $LOOPBACK:${server.localPort}")
            System.out.flush()
            server.accept().use { socket ->
                socket.tcpNoDelay = true
                serve(socket.getInputStream(), socket.getOutputStream(), launcher, childArgs)
            }
        }
    }

    /** Run the shipped server as a stdio child and route frames between it and [input]/[output]. */
    private fun serve(input: InputStream, output: OutputStream, launcher: Path, childArgs: List<String>) {
        val child = ProcessBuilder(listOf(launcher.toString(), "--stdio") + childArgs)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val session = Session(toClient = output, toChild = child.outputStream)
            thread(name = "kotlin-lsp-dev-client-to-child", isDaemon = true) {
                try {
                    session.pumpClient(input)
                } finally {
                    // Closing the child's stdin is how the shipped server learns to exit.
                    runCatching { child.outputStream.close() }
                }
            }
            child.inputStream.use { session.pumpChild(it) }
            check(child.waitFor() == 0) { "kotlin-lsp child exited unsuccessfully" }
        } finally {
            child.destroy()
        }
    }

    /**
     * One client connection and its child server.
     *
     * Both pumps write to the client, so every client write goes through [emitToClient]. Requests
     * this layer originates get string ids under [LOCAL_ID_PREFIX], which cannot collide with the
     * client's own ids: the client owns the integer/string id space it chose, and we never reuse a
     * value from it.
     */
    private class Session(private val toClient: OutputStream, private val toChild: OutputStream) {
        private val initializeId = AtomicReference<JsonElement?>()
        private val localIds = AtomicLong()
        private val pending = ConcurrentHashMap<String, (JsonObject) -> Unit>()
        private val clientLock = Any()
        private val childLock = Any()

        fun pumpClient(input: InputStream) {
            forwardFrames(input) { message, raw ->
                when {
                    message.get("method")?.asString == "initialize" -> {
                        initializeId.set(message.get("id")?.deepCopy())
                        emitToChild(raw)
                    }
                    isRequest(message) && message.get("method")?.asString == DOCUMENT_HIGHLIGHT ->
                        answerDocumentHighlight(message)
                    else -> emitToChild(raw)
                }
            }
        }

        fun pumpChild(input: InputStream) {
            forwardFrames(input) { message, raw ->
                val id = message.get("id")
                val handler = if (id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString) {
                    pending.remove(id.asString)
                } else {
                    null
                }
                when {
                    handler != null -> handler(message)
                    // Only the initialize response is rewritten; everything else -- including
                    // large semantic-token and diagnostic payloads -- is relayed byte for byte
                    // rather than parsed and re-serialised on the way through.
                    patchInitializeResponse(message) -> emitToClient(message)
                    else -> emitToClient(raw)
                }
            }
        }

        /**
         * `textDocument/documentHighlight` has no handler and no provider API in the shipped
         * server, so it is answered here: ask the child for the references of the symbol under the
         * cursor and keep the ones in this document. That reuses the server's real resolution
         * rather than approximating it by matching text.
         */
        private fun answerDocumentHighlight(request: JsonObject) {
            val requestId = request.get("id")
            val params = request.getAsJsonObject("params")
            val uri = params?.getAsJsonObject("textDocument")?.get("uri")?.asString
            val position = params?.getAsJsonObject("position")
            if (uri == null || position == null) {
                respondToClient(requestId, JsonArray())
                return
            }

            val referenceParams = JsonObject().apply {
                add("textDocument", params.getAsJsonObject("textDocument").deepCopy())
                add("position", position.deepCopy())
                // The declaration is part of what an editor highlights, so ask for it too.
                add("context", JsonObject().apply { addProperty("includeDeclaration", true) })
            }
            askChild(REFERENCES, referenceParams) { response ->
                if (response.has("error")) {
                    // A failure to resolve is not a protocol error for highlighting -- an editor
                    // asks on every cursor move, and an error popup per keystroke is worse than
                    // no highlight.
                    respondToClient(requestId, JsonArray())
                    return@askChild
                }
                val highlights = JsonArray()
                val locations = response.get("result") as? JsonArray ?: JsonArray()
                for (location in locations) {
                    val obj = location as? JsonObject ?: continue
                    if (obj.get("uri")?.asString != uri) continue
                    val range = obj.getAsJsonObject("range") ?: continue
                    // `kind` is optional; we cannot tell reads from writes here, and guessing
                    // wrong colours the highlight incorrectly in editors that distinguish them.
                    highlights.add(JsonObject().apply { add("range", range.deepCopy()) })
                }
                respondToClient(requestId, highlights)
            }
        }

        /** Send a request of our own to the child and run [onResponse] when it answers. */
        private fun askChild(method: String, params: JsonObject, onResponse: (JsonObject) -> Unit) {
            val id = LOCAL_ID_PREFIX + localIds.incrementAndGet()
            pending[id] = onResponse
            emitToChild(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            })
        }

        private fun respondToClient(id: JsonElement?, result: JsonElement) {
            emitToClient(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id ?: JsonPrimitive(0))
                add("result", result)
            })
        }

        /**
         * Advertise what the shipped server implements but omits, plus what we answer ourselves.
         * Returns true when [message] was modified and must be re-encoded.
         */
        private fun patchInitializeResponse(message: JsonObject): Boolean {
            val expected = initializeId.get() ?: return false
            if (message.get("id") != expected || message.has("error")) return false
            val capabilities = message.getAsJsonObject("result")?.getAsJsonObject("capabilities")
                ?: return false
            capabilities.addProperty(RANGE_FORMATTING_CAPABILITY, true)
            capabilities.addProperty(DOCUMENT_HIGHLIGHT_CAPABILITY, true)
            System.err.println(
                "[kotlin-lsp-dev] advertised $RANGE_FORMATTING_CAPABILITY, $DOCUMENT_HIGHLIGHT_CAPABILITY"
            )
            return true
        }

        private fun emitToClient(message: JsonObject) = emitToClient(encode(message))

        private fun emitToClient(raw: ByteArray) =
            synchronized(clientLock) { writeFrame(toClient, raw) }

        private fun emitToChild(message: JsonObject) = emitToChild(encode(message))

        private fun emitToChild(raw: ByteArray) =
            synchronized(childLock) { writeFrame(toChild, raw) }

        private fun isRequest(message: JsonObject) = message.has("id") && message.has("method")

        private companion object {
            const val LOCAL_ID_PREFIX = "kotlin-lsp-dev/"
        }
    }

    /**
     * Read complete LSP frames from [input] and hand each to [onMessage] as both a parsed message
     * and its original bytes, so a relayed frame can be written back without re-encoding.
     */
    private fun forwardFrames(input: InputStream, onMessage: (JsonObject, ByteArray) -> Unit) {
        while (true) {
            val frame = readFrame(input) ?: return
            onMessage(JsonParser.parseString(frame.toString(StandardCharsets.UTF_8)).asJsonObject, frame)
        }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        var contentLength: Int? = null
        var sawHeader = false
        while (true) {
            val line = readLine(input)
                ?: return if (!sawHeader) null else throw EOFException("truncated LSP headers")
            sawHeader = true
            if (line.contentEquals(CRLF) || line.contentEquals(LF)) break
            val text = line.toString(StandardCharsets.US_ASCII).trimEnd('\r', '\n')
            val separator = text.indexOf(':')
            require(separator > 0) { "invalid LSP header: $text" }
            if (text.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                contentLength = text.substring(separator + 1).trim().toInt()
                require(contentLength >= 0) { "negative Content-Length" }
            }
        }
        val length = requireNotNull(contentLength) { "LSP frame has no Content-Length header" }
        val body = input.readNBytes(length)
        if (body.size != length) throw EOFException("truncated LSP body")
        return body
    }

    private fun readLine(input: InputStream): ByteArray? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toByteArray()
            line.write(byte)
            if (byte == '\n'.code) return line.toByteArray()
        }
    }

    private fun encode(message: JsonObject): ByteArray =
        message.toString().toByteArray(StandardCharsets.UTF_8)

    private fun writeFrame(output: OutputStream, body: ByteArray) {
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun serverHome(): Path {
        val configured = requireNotNull(System.getProperty("kotlin.lsp.server.home")) {
            "kotlin.lsp.server.home is not set; launch via bin/enhanced-server"
        }
        return Path.of(configured).toAbsolutePath().normalize()
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val LF = byteArrayOf('\n'.code.toByte())
}
