package dev.android.ide.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A minimal, app-owned static HTTP server. It only binds loopback and only serves beneath a project root. */
class ProjectStaticPreviewServer : Closeable {
    private data class RunningServer(
        val root: File,
        val socket: ServerSocket,
        val clients: ExecutorService,
        val accepting: AtomicBoolean,
        val capability: String,
    ) {
        val port: Int get() = socket.localPort
    }

    private val servers = linkedMapOf<String, RunningServer>()

    suspend fun urlFor(rootPath: String, targetPath: String): String = withContext(Dispatchers.IO) {
        val root = File(rootPath).canonicalFile
        require(root.isDirectory && root.canRead()) { "The project root is not a readable directory" }
        val target = File(targetPath).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "The preview file is outside the project root" }
        require(target.isFile && target.canRead()) { "The preview file is not readable" }

        val server = synchronized(servers) {
            servers[root.path]?.takeIf { it.socket.isBound && !it.socket.isClosed }
                ?: startServer(root).also { servers[root.path] = it }
        }
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        val encoded = Uri.encode(relative, "/-_.~")
        "http://127.0.0.1:${server.port}/$CAPABILITY_ROUTE/${server.capability}/$encoded"
    }

    private fun startServer(root: File): RunningServer {
        val socket = ServerSocket()
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
        val clients = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "ide-preview-http-client").apply { isDaemon = true }
        }
        val capabilityBytes = ByteArray(24)
        SecureRandom().nextBytes(capabilityBytes)
        val capability = capabilityBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val running = RunningServer(root, socket, clients, AtomicBoolean(true), capability)
        Thread({
            while (running.accepting.get()) {
                try {
                    val client = socket.accept()
                    clients.execute { serveClient(client, running) }
                } catch (_: Exception) {
                    if (running.accepting.get()) continue
                }
            }
        }, "ide-preview-http-${socket.localPort}").apply { isDaemon = true }.start()
        return running
    }

    private fun serveClient(client: java.net.Socket, server: RunningServer) {
        val root = server.root
        client.use { socket ->
            runCatching {
                socket.soTimeout = 5_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val requestLine = readLine(input, 8_192) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size != 3) return writeResponse(output, 400, "Bad Request", "text/plain", ByteArray(0), "GET")
                val method = parts[0].uppercase(Locale.ROOT)
                val target = parts[1]
                if (method != "GET" && method != "HEAD") return writeResponse(output, 405, "Method Not Allowed", "text/plain", ByteArray(0), method, mapOf("Allow" to "GET, HEAD"))
                val headers = linkedMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = readLine(input, 8_192) ?: break
                    headerBytes += line.length
                    if (headerBytes > 32_768) return writeResponse(output, 431, "Request Header Fields Too Large", "text/plain", ByteArray(0), method)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
                }

                val rawPath = runCatching { java.net.URI(target).rawPath }.getOrNull()
                    ?.takeIf { it.startsWith('/') }
                    ?: return writeResponse(output, 400, "Bad Request", "text/plain", ByteArray(0), method)
                val tokenPrefix = "/$CAPABILITY_ROUTE/${server.capability}/"
                val tokenPathRequest = rawPath.startsWith(tokenPrefix)
                val cookieName = "$COOKIE_NAME_PREFIX${server.capability}"
                val cookieAuthorized = headers["cookie"]?.split(';')
                    ?.any { it.trim() == "$cookieName=${server.capability}" } == true
                if (!tokenPathRequest && !cookieAuthorized) {
                    return writeResponse(output, 403, "Forbidden", "text/plain", ByteArray(0), method)
                }
                val requestPath = if (tokenPathRequest) rawPath.removePrefix(tokenPrefix) else rawPath.removePrefix("/")
                val decodedPath = runCatching { Uri.decode(requestPath) }.getOrNull()
                    ?: return writeResponse(output, 400, "Bad Request", "text/plain", ByteArray(0), method)
                if (decodedPath.contains('\u0000') || decodedPath.contains('\\')) {
                    return writeResponse(output, 400, "Bad Request", "text/plain", ByteArray(0), method)
                }
                var file = File(root, decodedPath).canonicalFile
                if (!file.toPath().startsWith(root.toPath())) return writeResponse(output, 403, "Forbidden", "text/plain", ByteArray(0), method)
                if (file.isDirectory) file = File(file, "index.html").canonicalFile
                if (!file.toPath().startsWith(root.toPath())) return writeResponse(output, 403, "Forbidden", "text/plain", ByteArray(0), method)
                if (!file.isFile || !file.canRead()) return writeResponse(output, 404, "Not Found", "text/plain", ByteArray(0), method)

                val fileLength = file.length()
                val range = headers["range"]?.let { parseRange(it, fileLength) }
                if (headers.containsKey("range") && range == null) {
                    writeResponse(output, 416, "Range Not Satisfiable", mimeType(file), ByteArray(0), method, mapOf("Content-Range" to "bytes */$fileLength"))
                    return
                }
                val (start, end) = range ?: (0L to (fileLength - 1L).coerceAtLeast(0L))
                val length = if (fileLength == 0L) 0L else end - start + 1L
                val status = if (range == null) 200 else 206
                val reason = if (status == 200) "OK" else "Partial Content"
                val extra = buildMap {
                    if (range != null) put("Content-Range", "bytes $start-$end/$fileLength")
                    if (tokenPathRequest) put("Set-Cookie", "$cookieName=${server.capability}; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400")
                }
                writeHeaders(output, status, reason, mimeType(file), length, extra)
                if (method == "GET" && length > 0L) {
                    FileInputStream(file).use { stream ->
                        var skipped = 0L
                        while (skipped < start) {
                            val amount = stream.skip(start - skipped)
                            if (amount <= 0L) break
                            skipped += amount
                        }
                        var remaining = length
                        val buffer = ByteArray(16 * 1024)
                        while (remaining > 0L) {
                            val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
                output.flush()
            }.onFailure {
                runCatching { socket.getOutputStream().write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
            }
        }
    }

    private fun readLine(input: BufferedInputStream, maximum: Int): String? {
        val line = StringBuilder()
        while (line.length <= maximum) {
            val value = input.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) line.append(value.toChar())
        }
        return if (line.length > maximum) null else line.toString()
    }

    private fun parseRange(value: String, length: Long): Pair<Long, Long>? {
        if (length <= 0L || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").trim()
        if (',' in spec) return null
        val startText = spec.substringBefore('-', "")
        val endText = spec.substringAfter('-', "")
        return runCatching {
            if (startText.isBlank()) {
                val suffix = endText.toLong()
                require(suffix > 0)
                (length - suffix.coerceAtMost(length)) to (length - 1)
            } else {
                val start = startText.toLong()
                val end = if (endText.isBlank()) length - 1 else endText.toLong()
                require(start >= 0 && start < length && end >= start)
                start to end.coerceAtMost(length - 1)
            }
        }.getOrNull()
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "txt", "md", "markdown", "log" -> "text/plain; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "ogv" -> "video/ogg"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        mime: String,
        body: ByteArray,
        method: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(output, status, reason, mime, body.size.toLong(), extra)
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: BufferedOutputStream, status: Int, reason: String, mime: String, length: Long, extra: Map<String, String>) {
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n")
            extra.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
    }

    override fun close() {
        synchronized(servers) {
            servers.values.forEach { server ->
                server.accepting.set(false)
                runCatching { server.socket.close() }
                server.clients.shutdownNow()
            }
            servers.clear()
        }
    }

    private companion object {
        const val CAPABILITY_ROUTE = "__android_ide_preview"
        const val COOKIE_NAME_PREFIX = "ide_preview_"
    }
}
