package dev.android.ide.app

import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Owns the single Live Server process for the currently active editor workspace. */
class ProjectLiveServerService(
    private val prefix: File,
    private val home: File,
    private val runtimeEnvironment: Array<String>,
) : Closeable {
    data class Status(
        val running: Boolean,
        val projectRoot: String? = null,
        val url: String? = null,
        val message: String? = null,
    )

    private data class LiveProcess(
        val root: File,
        val process: Process,
        @Volatile var lastOutput: String = "",
    )

    private val lock = Any()
    @Volatile private var current: LiveProcess? = null

    /** Starts (or reuses) Live Server at the active project's root and returns its entry URL. */
    fun start(rootPath: String, targetPath: String): String {
        val root = File(rootPath).canonicalFile
        require(root.isDirectory && root.canRead()) { "The active project root is unavailable or unreadable" }
        val target = File(targetPath).canonicalFile
        require(target.isFile && target.canRead() && target.toPath().startsWith(root.toPath())) {
            "The preview entry file is unavailable or outside the active project"
        }
        val localExecutable = File(root, "node_modules/.bin/live-server")
        val globalExecutable = File(prefix, "bin/live-server")
        val executable = localExecutable.takeIf { it.canExecute() }
            ?: globalExecutable.takeIf { it.canExecute() }
            ?: throw IOException("Live Server is not installed. Run: ide tools install web-preview (global npm by default)")

        synchronized(lock) {
            val active = current
            if (active != null && active.root == root && active.process.isAlive) {
                if (probe(root, target, active.process)) return urlFor(root, target, active.process)
            }
            stopLocked()
            val process = ProcessBuilder(
                executable.absolutePath,
                "--host=127.0.0.1",
                "--port=$PORT",
                "--no-browser",
            ).directory(root).redirectErrorStream(true).apply {
                runtimeEnvironment.forEach { assignment ->
                    val separator = assignment.indexOf('=')
                    if (separator > 0) {
                        val key = assignment.substring(0, separator)
                        if (key !in PRIVATE_ENVIRONMENT_KEYS) environment()[key] = assignment.substring(separator + 1)
                    }
                }
                environment()["HOME"] = home.absolutePath
                environment()["PREFIX"] = prefix.absolutePath
                environment()["PATH"] = "${File(root, "node_modules/.bin").absolutePath}:${File(prefix, "bin").absolutePath}:${File(prefix, "lib/android-ide/bin").absolutePath}:/system/bin:/system/xbin"
                environment()["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath
                environment()["TMPDIR"] = File(prefix, "tmp").absolutePath
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
            }.start()
            val started = LiveProcess(root, process)
            current = started
            Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            started.lastOutput = (started.lastOutput + line + "\n").takeLast(MAX_OUTPUT_CHARS)
                        }
                    }
                }
            }, "ide-live-server-output").apply { isDaemon = true }.start()

            val deadline = System.nanoTime() + START_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (!process.isAlive) {
                    val message = started.lastOutput.trim().takeLast(500)
                    current = null
                    throw IOException(if (message.isBlank()) "Live Server exited during startup" else "Live Server failed to start: $message")
                }
                if (probe(root, target, process)) {
                    Thread.sleep(250)
                    if (!process.isAlive) {
                        val message = started.lastOutput.trim().takeLast(500)
                        current = null
                        throw IOException(if (message.isBlank()) "Live Server exited during startup" else "Live Server failed to start: $message")
                    }
                    if (started.lastOutput.contains("EADDRINUSE", ignoreCase = true)) {
                        stopLocked()
                        throw IOException("localhost:$PORT is already in use; stop the other server and retry")
                    }
                    return urlFor(root, target, process)
                }
                Thread.sleep(PROBE_INTERVAL_MS)
            }
            val message = started.lastOutput.trim().takeLast(500)
            stopLocked()
            throw IOException(if (message.isBlank()) "Live Server did not become ready on localhost:$PORT" else "Live Server did not become ready: $message")
        }
    }

    fun status(): Status {
        val active = current ?: return Status(false, message = "Live Server is stopped")
        if (!active.process.isAlive) {
            synchronized(lock) { if (current === active) current = null }
            return Status(false, active.root.path, message = "Live Server process exited: ${active.lastOutput.trim().takeLast(300)}")
        }
        if (!httpResponds(active.process)) return Status(false, active.root.path, message = "Live Server process is running but localhost:$PORT is not responding")
        return Status(true, active.root.path, "http://localhost:$PORT/", "Live Server is serving the active project")
    }

    /** Stops the active workspace server; safe when no project server is running. */
    fun stop() = synchronized(lock) { stopLocked() }

    /** Stop only when the changed workspace is the one currently being served. */
    fun stopForRoot(rootPath: String) {
        val expected = runCatching { File(rootPath).canonicalFile }.getOrNull() ?: File(rootPath).absoluteFile
        synchronized(lock) {
            if (current?.root == expected) stopLocked()
        }
    }

    override fun close() = stop()

    private fun stopLocked() {
        val active = current ?: return
        current = null
        if (active.process.isAlive) {
            runCatching { active.process.destroy() }
            if (!runCatching { active.process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(true)) {
                runCatching { active.process.destroyForcibly() }
                runCatching { active.process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun urlFor(root: File, target: File, process: Process): String {
        if (!process.isAlive) throw IOException("Live Server exited before the preview URL could be opened")
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return "http://localhost:$PORT/${Uri.encode(relative, "/-_.~")}" 
    }

    private fun probe(root: File, target: File, process: Process): Boolean {
        if (!process.isAlive) return false
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        val requestPath = "/${Uri.encode(relative, "/-_.~")}" 
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.connect(java.net.InetSocketAddress("127.0.0.1", PORT), PROBE_TIMEOUT_MS)
                socket.getOutputStream().write(
                    "GET $requestPath HTTP/1.0\r\nHost: localhost:$PORT\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                val status = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty()
                process.isAlive && status.startsWith("HTTP/") && status.contains(" 200 ")
            }
        }.getOrDefault(false)
    }

    private fun httpResponds(process: Process): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.connect(java.net.InetSocketAddress("127.0.0.1", PORT), PROBE_TIMEOUT_MS)
            socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost:$PORT\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            process.isAlive && socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty().startsWith("HTTP/")
        }
    }.getOrDefault(false)

    companion object {
        const val PORT = 7468
        private const val START_TIMEOUT_MS = 8_000L
        private const val PROBE_TIMEOUT_MS = 500
        private const val PROBE_INTERVAL_MS = 100L
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val MAX_OUTPUT_CHARS = 4_000
        private val PRIVATE_ENVIRONMENT_KEYS = setOf(
            "IDE_TOOLS_AUTH_TOKEN",
            "IDE_RECEIPT_DIRECTORY",
            "IDE_TOOLS_RECEIPT_DIRECTORY",
        )
    }
}
