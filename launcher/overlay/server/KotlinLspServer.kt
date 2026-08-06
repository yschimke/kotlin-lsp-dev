// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

package overlay.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Composition-server entry point for a kotlin-lsp distribution enhanced by kotlin-lsp-dev.
 *
 * This process owns the client connection and starts the shipped server as a child over stdio.
 * Messages pass through unchanged except for explicit overlay repairs -- operations that cannot
 * safely compose as in-process providers because the shipped dispatcher admits only one provider,
 * or because the capability is never advertised so no conformant client would send the request.
 * Additive features still run inside the child through the normal extension API.
 *
 * Two transports, matching the two ways the official VS Code extension starts a server:
 *
 *   --stdio            own this process's stdin/stdout (most editors, and our smoke harness)
 *   --socket <port>    listen on 127.0.0.1:<port> and serve the client that connects
 *
 * `--socket 0` binds an ephemeral port and announces it on stdout in the launcher's own format,
 * so this is a drop-in for `bin/intellij-server` wherever that is spawned. A fixed port is what
 * the extension's `intellij.dev.serverPort` setting dials.
 *
 * The installed `bin/enhanced-server` script supplies the server home and a small runtime class path.
 */
object KotlinLspServer {
    private const val RANGE_FORMATTING_CAPABILITY = "documentRangeFormattingProvider"
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

    /** Run the shipped server as a stdio child and pump frames between it and [input]/[output]. */
    private fun serve(input: InputStream, output: OutputStream, launcher: Path, childArgs: List<String>) {
        val child = ProcessBuilder(listOf(launcher.toString(), "--stdio") + childArgs)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val initializeId = AtomicReference<JsonElement?>()

        thread(name = "kotlin-lsp-dev-client-to-child", isDaemon = true) {
            child.outputStream.use { childInput ->
                forwardFrames(input, childInput) { message ->
                    if (message.get("method")?.asString == "initialize") {
                        initializeId.set(message.get("id")?.deepCopy())
                    }
                    false
                }
            }
        }
        try {
            child.inputStream.use { childOutput ->
                forwardFrames(childOutput, output) { message ->
                    patchInitializeResponse(message, initializeId.get())
                }
            }
            check(child.waitFor() == 0) { "kotlin-lsp child exited unsuccessfully" }
        } finally {
            child.destroy()
        }
    }

    /** Forward complete LSP frames, re-encoding only a message changed by [transform]. */
    private fun forwardFrames(
        input: InputStream,
        output: OutputStream,
        transform: (JsonObject) -> Boolean,
    ) {
        while (true) {
            val frame = readFrame(input) ?: return
            val message = JsonParser.parseString(frame.body.toString(StandardCharsets.UTF_8)).asJsonObject
            if (transform(message)) {
                writeFrame(output, message.toString().toByteArray(StandardCharsets.UTF_8))
            } else {
                output.write(frame.raw)
            }
            output.flush()
        }
    }

    private fun patchInitializeResponse(message: JsonObject, initializeId: JsonElement?): Boolean {
        if (initializeId == null || message.get("id") != initializeId || message.has("error")) return false
        val capabilities = message.getAsJsonObject("result")?.getAsJsonObject("capabilities") ?: return false
        capabilities.addProperty(RANGE_FORMATTING_CAPABILITY, true)
        System.err.println("[kotlin-lsp-dev] advertised $RANGE_FORMATTING_CAPABILITY=true")
        return true
    }

    private fun readFrame(input: InputStream): Frame? {
        val header = ByteArrayOutputStream()
        var contentLength: Int? = null
        while (true) {
            val line = readLine(input)
                ?: return if (header.size() == 0) null else throw EOFException("truncated LSP headers")
            header.write(line)
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
        return Frame(header.toByteArray() + body, body)
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

    private fun writeFrame(output: OutputStream, body: ByteArray) {
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
    }

    private fun serverHome(): Path {
        val configured = requireNotNull(System.getProperty("kotlin.lsp.server.home")) {
            "kotlin.lsp.server.home is not set; launch via bin/enhanced-server"
        }
        return Path.of(configured).toAbsolutePath().normalize()
    }

    private data class Frame(val raw: ByteArray, val body: ByteArray)

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val LF = byteArrayOf('\n'.code.toByte())
}
