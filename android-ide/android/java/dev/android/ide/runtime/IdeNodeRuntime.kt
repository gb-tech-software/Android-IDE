package dev.android.ide.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Application-owned JavaScript runtime. Terminal is one consumer of this
 * runtime, not the owner of editor services.
 */
class IdeNodeRuntime(
    private val context: Context,
    private val bundledRuntime: BundledTermuxRuntimeInstaller,
) {
    val root: File get() = File(context.filesDir, "ide-services")
    val node: File get() = File(bundledRuntime.prefix(), "bin/node")
    val npm: File get() = File(bundledRuntime.prefix(), "bin/npm")
    val shell: File get() = File(bundledRuntime.prefix(), "bin/sh")
    val home: File get() = bundledRuntime.home()
    val prefix: File get() = bundledRuntime.prefix()

    fun ensureReady(): Unit {
        val report = bundledRuntime.initialize()
        if (report.outcome != dev.android.ide.contracts.OperationOutcome.COMPLETE) {
            throw IOException(report.message.ifBlank { "The Android IDE JavaScript runtime is unavailable" })
        }
        if (!node.canExecute()) throw IOException("Android IDE Node.js runtime is unavailable")
        if (!npm.canExecute()) throw IOException("Android IDE npm runtime is unavailable")
        if (!shell.canExecute()) throw IOException("Android IDE shell runtime is unavailable")
        val version = nodeVersion()
            ?: throw IOException("Unable to determine Node.js version")
        val parts = version.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2 || parts[0] != REQUIRED_NODE_MAJOR) {
            throw IOException("Android IDE services require Node.js $REQUIRED_NODE_MAJOR.x; available version is $version")
        }
    }

    fun nodeVersion(): String? = runCatching {
        ProcessBuilder(node.absolutePath, "-p", "process.versions.node")
                .redirectErrorStream(true)
                .start()
                .also { process ->
                    if (!process.waitFor(NODE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        throw IOException("Node.js version check timed out")
                    }
                    if (process.exitValue() != 0) throw IOException("Node.js version check failed")
                }
                .inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun environment(packageRoot: File? = null): MutableMap<String, String> = mutableMapOf<String, String>().apply {
        bundledRuntime.sessionEnvironment(shell.absolutePath).forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
        }
        put("HOME", home.absolutePath)
        put("PREFIX", prefix.absolutePath)
        put("PATH", buildString {
            packageRoot?.let { append(File(it, "node_modules/.bin").absolutePath).append(':') }
            append(File(prefix, "lib/android-ide/bin").absolutePath).append(':')
            append(File(prefix, "bin").absolutePath).append(":/system/bin:/system/xbin")
        })
        put("TMPDIR", File(prefix, "tmp").absolutePath)
        put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
    }

    fun startNode(
        entrypoint: File,
        arguments: List<String>,
        workingDirectory: File,
        packageRoot: File? = null,
        redirectErrorStream: Boolean = false,
    ): Process {
        ensureReady()
        if (!entrypoint.isFile) throw IOException("Node entrypoint is missing: ${entrypoint.absolutePath}")
        return ProcessBuilder(listOf(node.absolutePath, entrypoint.absolutePath) + arguments)
            .directory(workingDirectory)
            .redirectErrorStream(redirectErrorStream)
            .apply { environment().putAll(this@IdeNodeRuntime.environment(packageRoot)) }
            .start()
    }

    private companion object {
        const val REQUIRED_NODE_MAJOR = 20
        const val NODE_CHECK_TIMEOUT_SECONDS = 5L
    }

}
