package dev.android.ide.app

import android.net.Uri
import dev.android.ide.runtime.IdeNodeRuntime
import dev.android.ide.runtime.IdePackageManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Application-owned project preview provider backed by the real Live Server package. */
class ProjectLiveServerService(
    private val runtime: IdeNodeRuntime,
    private val packages: IdePackageManager,
) : Closeable {
    data class Status(
        val running: Boolean,
        val projectRoot: String? = null,
        val url: String? = null,
        val message: String? = null,
        val port: Int? = null,
    )

    private data class LiveProcess(
        val root: File,
        val port: Int,
        val process: Process,
        @Volatile var lastOutput: String = "",
    )

    private val root = File(runtime.root, "preview")
    private val spec = IdePackageManager.PackageSpec(
        id = "preview",
        root = root,
        manifestAsset = "preview/package.json",
        lockfileAsset = null,
        requiredExecutables = setOf("live-server"),
    )
    private val lock = Any()
    @Volatile private var current: LiveProcess? = null

    fun packageStatus(): IdePackageManager.PackageStatus = packages.validate(spec)

    suspend fun install() = run {
        stop()
        packages.install(spec)
    }
    suspend fun update() = run {
        stop()
        packages.update(spec)
    }
    suspend fun remove(): Boolean {
        stop()
        return packages.remove(spec)
    }

    fun start(rootPath: String, targetPath: String): String {
        val projectRoot = File(rootPath).canonicalFile
        require(projectRoot.isDirectory && projectRoot.canRead()) { "The active project root is unavailable or unreadable" }
        val target = File(targetPath).canonicalFile
        require(target.isFile && target.canRead() && target.toPath().startsWith(projectRoot.toPath())) {
            "The preview entry file is unavailable or outside the active project"
        }
        val status = packages.validate(spec)
        if (!status.installed) throw IOException(status.message ?: "Preview server is not installed")
        // Resolve the executable declared by the installed package metadata.
        // This follows npm/pnpm layouts without guessing filenames or passing
        // the generated node_modules/.bin shell shim to Node directly.
        val entrypoint = resolveEntrypoint()
        synchronized(lock) {
            val active = current
            if (active != null && active.root == projectRoot && active.process.isAlive && probe(projectRoot, target, active)) {
                return urlFor(projectRoot, target, active.port)
            }
            stopLocked()
            val port = choosePort()
            val process = runtime.startNode(
                entrypoint,
                listOf("--host=127.0.0.1", "--port=$port", "--no-browser", projectRoot.absolutePath),
                projectRoot,
                packageRoot = root,
                redirectErrorStream = true,
                minimumNodeMajor = 11,
            )
            val started = LiveProcess(projectRoot, port, process)
            current = started
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> started.lastOutput = (started.lastOutput + line + "\n").takeLast(MAX_OUTPUT_CHARS) }
                    }
                }
            }.apply { isDaemon = true; name = "ide-preview-output" }.start()
            val deadline = System.nanoTime() + START_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (!process.isAlive) {
                    val failure = startupFailure(started)
                    stopLocked()
                    throw failure
                }
                if (probe(projectRoot, target, started)) return urlFor(projectRoot, target, port)
                Thread.sleep(PROBE_INTERVAL_MS)
            }
            val failure = startupFailure(started)
            stopLocked()
            throw failure
        }
    }

    fun status(): Status {
        val active = current ?: return Status(false, message = "Preview is stopped")
        if (!active.process.isAlive) {
            synchronized(lock) { if (current === active) current = null }
            return Status(false, active.root.path, message = active.lastOutput.trim().takeLast(500), port = active.port)
        }
        return if (httpResponds(active.port)) {
            Status(true, active.root.path, "http://127.0.0.1:${active.port}/", "Preview is serving the active project", active.port)
        } else {
            Status(false, active.root.path, message = "Preview is running but is not responding", port = active.port)
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    fun stopForRoot(rootPath: String) {
        val expected = runCatching { File(rootPath).canonicalFile }.getOrNull() ?: return
        synchronized(lock) { if (current?.root == expected) stopLocked() }
    }

    override fun close() = stop()

    private fun stopLocked() {
        val active = current ?: return
        current = null
        if (active.process.isAlive) {
            active.process.destroy()
            if (!runCatching { active.process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(true)) {
                active.process.destroyForcibly()
            }
        }
    }

    private fun choosePort(): Int {
        if (portAvailable(PREFERRED_PORT)) return PREFERRED_PORT
        return (PREFERRED_PORT + 1..PREFERRED_PORT + PORT_SCAN_RANGE).firstOrNull(::portAvailable)
            ?: throw IOException("No local preview port is available")
    }

    private fun resolveEntrypoint(): File {
        val packageDirectory = File(root, "node_modules/live-server")
        val manifestFile = File(packageDirectory, "package.json")
        if (!manifestFile.isFile) {
            throw IOException("Installed Live Server package metadata is missing: ${manifestFile.absolutePath}")
        }
        val manifest = runCatching { JSONObject(manifestFile.readText()) }
            .getOrElse { error -> throw IOException("Installed Live Server package metadata is invalid", error) }
        val declaredBin = when (val bin = manifest.opt("bin")) {
            is String -> bin
            is JSONObject -> bin.optString("live-server")
            else -> ""
        }.trim()
        if (declaredBin.isBlank()) {
            throw IOException("Installed Live Server package does not declare a live-server executable")
        }
        val entrypoint = File(packageDirectory, declaredBin).canonicalFile
        val packageRoot = packageDirectory.canonicalFile.toPath()
        if (!entrypoint.isFile || !entrypoint.canRead() || !entrypoint.toPath().startsWith(packageRoot)) {
            throw IOException("Installed Live Server executable is unavailable: $declaredBin")
        }
        return entrypoint
    }

    private fun portAvailable(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun urlFor(root: File, target: File, port: Int): String {
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return "http://127.0.0.1:$port/${Uri.encode(relative, "/-_.~")}"
    }

    private fun probe(root: File, target: File, active: LiveProcess): Boolean {
        if (!active.process.isAlive) return false
        val relative = root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return httpStatus(active.port, "/${Uri.encode(relative, "/-_.~")}") in 200..399
    }

    private fun httpResponds(port: Int): Boolean = httpStatus(port, "/") in 200..599

    private fun httpStatus(port: Int, path: String): Int = runCatching {
        Socket().use { socket ->
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
            socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost:$port\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }.getOrDefault(-1)

    private fun startupFailure(active: LiveProcess): IOException = IOException(
        if (active.lastOutput.isBlank()) "Preview server did not become ready on port ${active.port}"
        else "Preview server failed to start: ${active.lastOutput.trim().takeLast(500)}",
    )

    companion object {
        const val PREFERRED_PORT = 7468
        private const val PORT_SCAN_RANGE = 20
        private const val START_TIMEOUT_MS = 8_000L
        private const val PROBE_TIMEOUT_MS = 500
        private const val PROBE_INTERVAL_MS = 100L
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val MAX_OUTPUT_CHARS = 4_000
    }
}
