package dev.android.ide.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Installs packages in Android IDE-owned roots, never in the terminal prefix. */
class IdePackageManager(
    private val context: Context,
    private val runtime: IdeNodeRuntime,
) {
    private val mutationLock = Mutex()

    data class PackageSpec(
        val id: String,
        val root: File,
        val manifestAsset: String,
        val lockfileAsset: String? = null,
        val requiredExecutables: Set<String> = emptySet(),
    )

    data class PackageStatus(
        val id: String,
        val installed: Boolean,
        val version: String? = null,
        val message: String? = null,
    )

    suspend fun install(spec: PackageSpec): PackageStatus = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            require(spec.id.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid package identifier" }
            runtime.ensureReady()
            val staging = File(runtime.root, "${spec.id}.staging")
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Unable to create package staging directory")
            copyAsset(spec.manifestAsset, File(staging, "package.json"))
            spec.lockfileAsset?.let { copyAsset(it, File(staging, "package-lock.json")) }
            val command = if (spec.lockfileAsset != null) {
                "npm ci --prefix ${quote(staging.absolutePath)} --omit=dev --ignore-scripts --no-audit --no-fund"
            } else {
                "npm install --prefix ${quote(staging.absolutePath)} --omit=dev --ignore-scripts --no-audit --no-fund"
            }
            runCommand(command, staging)
            validate(spec.copy(root = staging)).also { status ->
                if (!status.installed) throw IOException(status.message ?: "Package validation failed")
            }
            activate(spec, staging)
        }
    }

    suspend fun update(spec: PackageSpec): PackageStatus = install(spec)

    suspend fun remove(spec: PackageSpec): Boolean = mutationLock.withLock {
        withContext(Dispatchers.IO) {
            !spec.root.exists() || spec.root.deleteRecursively()
        }
    }

    private fun activate(spec: PackageSpec, staging: File): PackageStatus {
        spec.root.parentFile?.mkdirs()
        val backup = File(runtime.root, "${spec.id}.backup")
        backup.deleteRecursively()
        val hadExisting = spec.root.exists()
        if (hadExisting && !spec.root.renameTo(backup)) {
            throw IOException("Unable to stage replacement for ${spec.id} package")
        }
        return try {
            if (!staging.renameTo(spec.root)) throw IOException("Unable to activate ${spec.id} package")
            val status = validate(spec)
            if (!status.installed) throw IOException(status.message ?: "Activated package validation failed")
            backup.deleteRecursively()
            status
        } catch (error: Throwable) {
            spec.root.deleteRecursively()
            if (hadExisting) backup.renameTo(spec.root)
            throw error
        } finally {
            staging.deleteRecursively()
            backup.deleteRecursively()
        }
    }

    fun validate(spec: PackageSpec): PackageStatus {
        return runCatching {
            val manifest = JSONObject(context.assets.open(spec.manifestAsset).bufferedReader().use { it.readText() })
            val installed = File(spec.root, "package.json")
            if (!installed.isFile) return PackageStatus(spec.id, false, message = "Package is not installed")
            val installedJson = JSONObject(installed.readText())
            val expectedDependencies = manifest.optJSONObject("dependencies")
            val installedDependencies = installedJson.optJSONObject("dependencies")
            if (expectedDependencies != null && installedDependencies == null) {
                return PackageStatus(spec.id, false, message = "Package manifest is incomplete")
            }
            expectedDependencies?.keys()?.forEach { name ->
                val expected = expectedDependencies.optString(name)
                val actual = installedDependencies?.optString(name)
                val moduleManifest = File(spec.root, "node_modules/$name/package.json")
                if (!moduleManifest.isFile) throw IOException("Missing dependency: $name")
                val actualVersion = JSONObject(moduleManifest.readText()).optString("version")
                if (expected.matches(Regex("\\d+\\.\\d+\\.\\d+")) && actualVersion != expected) {
                    throw IOException("Unexpected $name version: $actualVersion")
                }
                if (actual.isNullOrBlank()) throw IOException("Missing manifest dependency: $name")
            }
            spec.requiredExecutables.forEach { executable ->
                val file = File(spec.root, "node_modules/.bin/$executable")
                if (!file.isFile) throw IOException("Missing package entrypoint: $executable")
            }
            PackageStatus(spec.id, true, installedJson.optString("version").takeIf { it.isNotBlank() })
        }.getOrElse { PackageStatus(spec.id, false, message = it.message ?: "Package validation failed") }
    }

    private fun runCommand(command: String, directory: File) {
        val log = File(context.cacheDir, "ide-package-${directory.name}.log")
        val process = ProcessBuilder(runtime.shell.absolutePath, "-c", command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { environment().putAll(runtime.environment(directory)) }
            .start()
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("Package installation timed out")
        }
        if (process.exitValue() != 0) {
            val detail = if (log.isFile) log.readText().takeLast(2_000) else ""
            throw IOException("Package installation failed (exit ${process.exitValue()})${detail.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}")
        }
    }

    private fun copyAsset(asset: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(asset).buffered().use { input -> destination.outputStream().buffered().use(input::copyTo) }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
