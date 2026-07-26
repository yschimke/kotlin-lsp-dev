// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

package overlay.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Composition-server entry point for a kotlin-lsp distribution enhanced by kotlin-lsp-dev.
 *
 * In stdio mode this process owns the client connection and starts the shipped server as a child.
 * Messages pass through unchanged except for explicit overlay fixes. The installed
 * `bin/enhanced-server` script supplies the server home and a small runtime class path.
 */
object KotlinLspServer {
    private const val RANGE_FORMATTING_CAPABILITY = "documentRangeFormattingProvider"

    @JvmStatic
    fun main(args: Array<String>) {
        val launcher = serverHome().resolve("bin/intellij-server")
        require(Files.isRegularFile(launcher)) { "kotlin-lsp launcher not found: $launcher" }
        if ("--stdio" in args) runProxy(launcher, args) else runPassThrough(launcher, args)
    }

    private fun runProxy(launcher: Path, args: Array<String>) {
        val child = ProcessBuilder(listOf(launcher.toString()) + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val initializeId = AtomicReference<JsonElement?>()

        thread(name = "kotlin-lsp-dev-client-to-child", isDaemon = true) {
            child.outputStream.use { childInput ->
                forwardFrames(System.`in`, childInput) { message ->
                    if (message.get("method")?.asString == "initialize") {
                        initializeId.set(message.get("id")?.deepCopy())
                    }
                    false
                }
            }
        }
        try {
            child.inputStream.use { childOutput ->
                forwardFrames(childOutput, System.out) { message ->
                    patchInitializeResponse(message, initializeId.get())
                }
            }
            check(child.waitFor() == 0) { "kotlin-lsp child exited unsuccessfully" }
        } finally {
            child.destroy()
        }
    }

    private fun runPassThrough(launcher: Path, args: Array<String>) {
        val exitCode = ProcessBuilder(listOf(launcher.toString()) + args).inheritIO().start().waitFor()
        check(exitCode == 0) { "kotlin-lsp child exited with status $exitCode" }
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
            val line = readLine(input) ?: return if (header.size() == 0) null else throw EOFException("truncated LSP headers")
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
