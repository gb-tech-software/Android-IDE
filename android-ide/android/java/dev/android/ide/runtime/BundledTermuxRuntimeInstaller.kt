package dev.android.ide.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Installs the pinned Termux bootstrap into Android IDE's private runtime directory. */
class BundledTermuxRuntimeInstaller(private val context: Context) {
    private val prefix = File(context.filesDir, "usr")
    private val userFiles = File(context.filesDir, "home")
    private val home get() = userFiles
    private val defaultPackagesMarker = File(context.filesDir, ".android-ide-default-packages-2026.09.30-r1")

    /** Runtime packages are installed through the terminal's own package manager; no second UI is needed. */
    val defaultPackagePlan: List<String> = listOf(
        "curl", "git", "openssh", "ca-certificates", "procps", "nodejs", "python", "zip", "unzip", "live-server",
        "typescript-language-server", "pyright", "vscode-langservers-extracted",
    )

    fun initialize(onProgress: (String) -> Unit = {}): OperationReport {
        val assetName = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86" -> "i686"
            "x86_64" -> "x86_64"
            else -> null
        } ?: return unavailable("This device architecture is not supported by the bundled terminal runtime")

        val marker = File(prefix, ".android-ide-bootstrap-2026.09.13-r1-apt.android-7")
        return runCatching {
            onProgress("Preparing the private terminal home and workspace…")
            ensureDirectory(home, PRIVATE_DIRECTORY_MODE, "Terminal home")
            ensureDirectory(userFiles, PRIVATE_DIRECTORY_MODE, "Android IDE storage")
            ensureDirectory(File(prefix, "tmp"), PRIVATE_DIRECTORY_MODE, "Termux temporary directory")
            onProgress("Checking the bundled Termux bootstrap…")
            if (!marker.isFile) installBootstrap(assetName, marker)

            onProgress("Checking shell and package-manager permissions…")
            if (!repairRuntimeExecutables(includeNpm = false)) {
                throw IOException("The bundled shell or package manager is missing or not executable")
            }
            shellStartupFailure()?.let { throw IOException(it) }
            OperationReport(OperationOutcome.COMPLETE, "Bundled Termux runtime initialized")
        }.getOrElse { error ->
            unavailable("The bundled terminal runtime could not be initialized: ${error.message ?: "unknown error"}")
        }
    }

    fun prefix(): File = prefix
    fun home(): File = home
    fun userFilesRoot(): File = userFiles

    /** The same environment is used by the preflight probe and the interactive PTY. */
    fun sessionEnvironment(): Array<String> = arrayOf(
        "HOME=${home.absolutePath}",
        "PREFIX=${prefix.absolutePath}",
        "PATH=${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
        "SHELL=${File(prefix, "bin/sh").absolutePath}",
        "LD_LIBRARY_PATH=${File(prefix, "lib").absolutePath}",
        "TMPDIR=${File(prefix, "tmp").absolutePath}",
        "TMP=${File(prefix, "tmp").absolutePath}",
        "TEMP=${File(prefix, "tmp").absolutePath}",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        // The bundled shell is launched as an interactive, non-login shell and
        // the private Termux home does not contain a user-created profile on a
        // fresh install. Use POSIX command substitutions rather than Bash-only
        // \u/\h/\w escapes so the bundled shell renders the real user and cwd.
        "PS1=$(id -un)@android-ide:$(pwd)\\$ ",
    )

    /** Run the exact shell/cwd combination used by a new terminal session before advertising readiness. */
    fun shellStartupFailure(): String? = runCatching {
        ensureDirectory(home, PRIVATE_DIRECTORY_MODE, "Terminal home")
        if (!repairRuntimeExecutables(includeNpm = false)) {
            throw IOException("${File(prefix, "bin/sh").absolutePath} is not executable")
        }
        val process = ProcessBuilder(File(prefix, "bin/sh").absolutePath, "-c", "exit 0")
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                sessionEnvironment().forEach { entry ->
                    val separator = entry.indexOf('=')
                    environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
                }
            }
            .start()
        val output = readOutputTail(process.inputStream)
        val exitCode = process.waitFor()
        if (exitCode == 0) null else {
            "Bundled shell exited with code $exitCode${output.trim().takeLast(800).takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}"
        }
    }.getOrElse { error ->
        "Bundled shell could not start in ${home.absolutePath}: ${error.message ?: error.javaClass.simpleName}"
    }

    fun ensureDefaultPackages(onProgress: (String) -> Unit = {}): OperationReport {
        val shell = File(prefix, "bin/sh")
        val pkg = File(prefix, "bin/pkg")
        val npm = File(prefix, "bin/npm")
        return runCatching {
            ensureDirectory(home, PRIVATE_DIRECTORY_MODE, "Terminal home")
            ensureDirectory(userFiles, PRIVATE_DIRECTORY_MODE, "Android IDE storage")
            ensureDirectory(File(prefix, "tmp"), PRIVATE_DIRECTORY_MODE, "Termux temporary directory")
            if (!repairRuntimeExecutables(includeNpm = false)) {
                return OperationReport(
                    OperationOutcome.BLOCKED,
                    "Default terminal packages cannot be installed because the bundled shell or package manager is not executable",
                    ErrorCategory.UNAVAILABLE_RUNTIME,
                    recoveryHint = "Retry Terminal initialization after the bundled runtime has been repaired.",
                )
            }
            if (defaultPackagesMarker.isFile && repairRuntimeExecutables(includeNpm = true) && baselinePackagesAvailable() && shellStartupFailure() == null) {
                return OperationReport(OperationOutcome.COMPLETE, "Default terminal packages are available")
            }

            onProgress("Installing baseline shell, Git, SSH, certificate, archive, process, and language-runtime packages…")
            val packageInstall = runCommand(shell, "pkg install -y curl git openssh ca-certificates procps nodejs python zip unzip")
            if (packageInstall.exitCode != 0) {
                return OperationReport(
                    OperationOutcome.PARTIAL,
                    commandFailure("The baseline terminal packages could not be installed", packageInstall),
                    ErrorCategory.PACKAGE_FAILURE,
                    recoveryHint = "Use the terminal's normal pkg commands after the shell runtime is available.",
                )
            }
            if (!repairRuntimeExecutables(includeNpm = true) || !npm.canExecute() || !baselinePackagesAvailable()) {
                return OperationReport(
                    OperationOutcome.PARTIAL,
                    "The default packages were installed, but npm is missing or not executable.",
                    ErrorCategory.PACKAGE_FAILURE,
                    recoveryHint = "Run `pkg install nodejs` in the terminal after restoring the shell.",
                )
            }

            onProgress("Installing the default Node.js language and preview tools…")
            val globalInstall = runCommand(
                shell,
                "npm install -g live-server typescript-language-server pyright vscode-langservers-extracted",
            )
            if (globalInstall.exitCode != 0) {
                return OperationReport(
                    OperationOutcome.PARTIAL,
                    commandFailure("Language tools and live-server could not be installed", globalInstall),
                    ErrorCategory.PACKAGE_FAILURE,
                    recoveryHint = "Retry the npm install command from the terminal when network access is available.",
                )
            }
            shellStartupFailure()?.let {
                return OperationReport(OperationOutcome.PARTIAL, it, ErrorCategory.UNAVAILABLE_RUNTIME)
            }
            defaultPackagesMarker.writeText(defaultPackagePlan.joinToString("\n"))
            OperationReport(OperationOutcome.COMPLETE, "Default terminal packages installed")
        }.getOrElse { error ->
            OperationReport(
                OperationOutcome.PARTIAL,
                "Default terminal package initialization failed: ${error.message ?: error.javaClass.simpleName}",
                ErrorCategory.PACKAGE_FAILURE,
                recoveryHint = "Retry package initialization from the terminal when the shell is available.",
            )
        }
    }

    private fun baselinePackagesAvailable(): Boolean =
        listOf("curl", "git", "ssh", "python", "zip", "unzip", "ps").all { name ->
            File(prefix, "bin/$name").canExecute()
        } && File(prefix, "etc/tls/cert.pem").isFile

    private fun installBootstrap(assetName: String, marker: File) {
        val assetPath = "termux/bootstrap-$assetName.zip"
        val staging = File(context.filesDir, "usr.staging")
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Unable to clear the previous bootstrap staging directory")
        }
        ensureDirectory(staging, PRIVATE_DIRECTORY_MODE, "Bootstrap staging")
        try {
            context.assets.open(assetPath).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.removePrefix("usr/").trimStart('/')
                        if (relative.isBlank() || relative.contains("..") || relative.startsWith("/")) continue
                        val target = File(staging, relative).canonicalFile
                        if (!target.toPath().startsWith(staging.canonicalFile.toPath())) continue
                        if (entry.isDirectory) {
                            ensureDirectory(target, PREFIX_DIRECTORY_MODE, "Bootstrap directory")
                        } else {
                            target.parentFile?.let { ensureDirectory(it, PREFIX_DIRECTORY_MODE, "Bootstrap parent directory") }
                            target.outputStream().use { output -> zip.copyTo(output) }
                            setMode(target, EXECUTABLE_FILE_MODE)
                        }
                        zip.closeEntry()
                    }
                }
            }
            installBootstrapSymlinks(staging)
            if (prefix.exists() && !prefix.deleteRecursively()) {
                throw IOException("Unable to replace the previous bundled runtime directory")
            }
            if (!staging.renameTo(prefix)) throw IOException("Unable to install the bundled runtime directory")
            ensureDirectory(prefix, PREFIX_DIRECTORY_MODE, "Termux prefix")
            ensureDirectory(File(prefix, "bin"), PREFIX_DIRECTORY_MODE, "Termux binary directory")
            if (!repairRuntimeExecutables(includeNpm = false)) {
                throw IOException("The unpacked shell or package manager is not executable")
            }
            marker.writeText(assetName)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun installBootstrapSymlinks(staging: File) {
        val linksFile = File(staging, "SYMLINKS.txt")
        if (!linksFile.isFile) return
        linksFile.readLines().forEach { line ->
            val parts = line.split("←./", limit = 2)
            if (parts.size != 2) return@forEach
            val rawTarget = parts[0]
            val link = File(staging, parts[1]).canonicalFile
            if (!link.toPath().startsWith(staging.canonicalFile.toPath())) return@forEach
            val target = if (rawTarget.startsWith("/data/data/com.termux/files/usr/")) {
                File(staging, rawTarget.removePrefix("/data/data/com.termux/files/usr/")).canonicalFile
            } else {
                File(link.parentFile, rawTarget).canonicalFile
            }
            if (!target.toPath().startsWith(staging.canonicalFile.toPath())) return@forEach
            link.delete()
            link.parentFile?.let { ensureDirectory(it, PREFIX_DIRECTORY_MODE, "Bootstrap symlink parent") }
            Files.createSymbolicLink(link.toPath(), link.parentFile.toPath().relativize(target.toPath()))
        }
    }

    private fun repairRuntimeExecutables(includeNpm: Boolean): Boolean = runCatching {
        ensureDirectory(prefix, PREFIX_DIRECTORY_MODE, "Termux prefix")
        val bin = File(prefix, "bin")
        ensureDirectory(bin, PREFIX_DIRECTORY_MODE, "Termux binary directory")
        val required = if (includeNpm) listOf("sh", "pkg", "npm") else listOf("sh", "pkg")
        required.forEach { name ->
            val executable = File(bin, name)
            if (!executable.exists()) throw IOException("${executable.absolutePath} is missing")
            setMode(executable, EXECUTABLE_FILE_MODE)
            if (!executable.canExecute()) throw IOException("${executable.absolutePath} is not executable")
        }
        // ZIP extraction does not reliably preserve mode bits on Android.
        // Repair every command wrapper and symlink target in bin, including
        // pkg's apt wrapper and utilities installed by later bootstrap updates.
        bin.listFiles()
            ?.filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            ?.forEach { setMode(it, EXECUTABLE_FILE_MODE) }
    }.isSuccess

    private fun ensureDirectory(directory: File, mode: Int, label: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create $label at ${directory.absolutePath}")
        }
        if (!directory.isDirectory) throw IOException("$label is not a directory: ${directory.absolutePath}")
        try {
            Os.chmod(directory.absolutePath, mode)
        } catch (error: Exception) {
            throw IOException("Unable to set permissions on $label at ${directory.absolutePath}: ${error.message}", error)
        }
        if (!directory.canRead() || !directory.canWrite() || !directory.canExecute()) {
            throw IOException("$label is not readable, writable, and searchable: ${directory.absolutePath}")
        }
    }

    private fun setMode(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (error: Exception) {
            throw IOException("Unable to set executable permissions on ${file.absolutePath}: ${error.message}", error)
        }
    }

    private data class CommandResult(val exitCode: Int?, val output: String = "", val failure: String? = null)

    private fun runCommand(shell: File, command: String): CommandResult = runCatching {
        val process = ProcessBuilder(shell.absolutePath, "-c", command)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                sessionEnvironment().forEach { entry ->
                    val separator = entry.indexOf('=')
                    environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
                }
            }
            .start()
        val output = readOutputTail(process.inputStream)
        CommandResult(process.waitFor(), output)
    }.getOrElse { error -> CommandResult(null, failure = error.message ?: error.javaClass.simpleName) }

    private fun commandFailure(summary: String, result: CommandResult): String = buildString {
        append(summary)
        result.exitCode?.let { append(" (exit $it)") }
        result.failure?.let { append(": ").append(it) }
        result.output.trim().takeLast(800).takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
    }

    private fun readOutputTail(input: InputStream, maxChars: Int = 8_000): String {
        val tail = StringBuilder(maxChars)
        val buffer = CharArray(2_048)
        input.reader().use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                tail.append(buffer, 0, count)
                if (tail.length > maxChars) tail.delete(0, tail.length - maxChars)
            }
        }
        return tail.toString()
    }

    private fun unavailable(message: String) =
        OperationReport(OperationOutcome.BLOCKED, message, ErrorCategory.UNAVAILABLE_RUNTIME)

    private companion object {
        const val PRIVATE_DIRECTORY_MODE = 0b111000000 // 0700
        const val PREFIX_DIRECTORY_MODE = 0b111101101 // 0755
        const val EXECUTABLE_FILE_MODE = 0b111101101 // 0755
    }
}
