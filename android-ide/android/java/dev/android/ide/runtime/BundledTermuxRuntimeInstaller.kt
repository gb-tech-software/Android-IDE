package dev.android.ide.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import dev.android.ide.contracts.OpenUrlRequestProtocol
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.TerminalStorageSetupProtocol
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
    private val storageBridge = TermuxStorageBridge(context, home)
    private val storageSetupResultChannel = TerminalStorageSetupResultChannel(home)
    @Synchronized
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
            removeObsoleteBundledTools()

            installInteractiveShellProfile()
            installStorageSetupCommand()
            installUrlNavigationCommand()
            installIdeToolsPackage()
            shellStartupFailure()?.let { throw IOException(it) }
            OperationReport(OperationOutcome.COMPLETE, "Default Termux bootstrap is ready")
        }.getOrElse { error ->
            unavailable("The bundled terminal runtime could not be initialized: ${error.message ?: "unknown error"}")
        }
    }

    fun prefix(): File = prefix
    fun home(): File = home
    fun userFilesRoot(): File = userFiles
    fun rebuildStorageLinks(): OperationReport = storageBridge.rebuildLinks()
    fun acknowledgeStorageSetupRequest(requestId: String) =
        storageSetupResultChannel.publishAccepted(requestId)

    fun publishStorageSetupResult(requestId: String, report: OperationReport) =
        storageSetupResultChannel.publish(requestId, report)

    private fun removeObsoleteBundledTools() {
        File(context.filesDir, "termux-runtime-bundle.staging").deleteRecursively()
        File(context.filesDir, ".android-ide-offline-runtime-2026.10.09-r1").delete()
        File(prefix, "lib/android-ide/tools/2026.10.09-r1").deleteRecursively()
        listOf(
            "live-server", "pyright-langserver", "tsc", "tsserver", "typescript-language-server",
            "vscode-css-language-server", "vscode-html-language-server", "vscode-json-language-server",
        ).forEach { File(prefix, "lib/android-ide/bin/$it").delete() }
        File(prefix, "lib/android-ide/tools/android-ide-tools").deleteRecursively()
    }

    /** The same environment is used by the preflight probe and the interactive PTY. */
    fun sessionEnvironment(shellPath: String = File(prefix, "bin/sh").absolutePath): Array<String> = arrayOf(
        "HOME=${home.absolutePath}",
        "PREFIX=${prefix.absolutePath}",
        "PATH=${File(prefix, "lib/android-ide/bin").absolutePath}:${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
        "SHELL=$shellPath",
        // Dev servers invoke BROWSER as a URL-taking executable. Keep this at
        // the runtime boundary so Vite, Live Server, and other tools route
        // through the Android IDE Browser domain without tool-specific hooks.
        "BROWSER=${File(prefix, "lib/android-ide/bin/android-ide-open-url").absolutePath}",
        "IDE_PACKAGE=${context.packageName}",
        "IDE_RECEIPT_DIRECTORY=${File(context.filesDir, "browser-navigation-receipts").absolutePath}",
        "IDE_TOOLS_RECEIPT_DIRECTORY=${File(context.filesDir, "ide-tools-receipts").absolutePath}",
        "IDE_TOOLS_AUTH_TOKEN=${dev.android.ide.contracts.IdeToolsCommandProtocol.Receipts(context).authToken}",
        "IDE_LOG_TAG=AndroidIde",
        "LD_LIBRARY_PATH=${File(prefix, "lib").absolutePath}",
        "TMPDIR=${File(prefix, "tmp").absolutePath}",
        "TMP=${File(prefix, "tmp").absolutePath}",
        "TEMP=${File(prefix, "tmp").absolutePath}",
        "ENV=${File(home, ".android-ide-termuxrc").absolutePath}",
        "CDPATH=",
        "PAGER=cat",
        "GIT_PAGER=cat",
        "MANPAGER=cat",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
    )

    private fun installInteractiveShellProfile() {
        val protectedRuntimeRoot = prefix.canonicalPath
        val profile = File(home, ".android-ide-termuxrc")
        val promptGreen = "\u001B[1;32m"
        val promptReset = "\u001B[0m"
        val promptNonPrintingStart = if (File(prefix, "bin/bash").canExecute()) "\\[" else ""
        val promptNonPrintingEnd = if (File(prefix, "bin/bash").canExecute()) "\\]" else ""
        profile.writeText(
            """# Android IDE terminal is global; only interactive navigation into its bundled runtime is restricted.
_android_ide_protected_runtime_root='$protectedRuntimeRoot'
_android_ide_navigation_allowed() {
    _android_ide_physical_path=${'$'}(command pwd -P 2>/dev/null) || return 1
    case "${'$'}_android_ide_physical_path" in
        "${'$'}_android_ide_protected_runtime_root"|"${'$'}_android_ide_protected_runtime_root"/*) return 1 ;;
        *) return 0 ;;
    esac
}
cd() {
    if [ "${'$'}#" -eq 0 ]; then set -- "${'$'}HOME"; fi
    command cd "${'$'}@" || return ${'$'}?
    if _android_ide_navigation_allowed; then return 0; fi
    command cd "${'$'}HOME"
    printf '%s\n' 'Direct navigation into the bundled runtime is restricted; commands may still use its files when needed.' >&2
    return 1
}
_android_ide_prompt_path() {
    _android_ide_physical_path=${'$'}(command pwd -P 2>/dev/null) || _android_ide_physical_path=${'$'}PWD
    case "${'$'}_android_ide_physical_path" in
        "${'$'}HOME") _android_ide_shown='~' ;;
        "${'$'}HOME"/*)
            _android_ide_shown="~/${'$'}{_android_ide_physical_path#"${'$'}HOME"/}"
            ;;
        *) _android_ide_shown=${'$'}_android_ide_physical_path ;;
    esac
    if [ ${'$'}{#_android_ide_shown} -gt 42 ]; then
        _android_ide_tail=${'$'}_android_ide_shown
        while [ ${'$'}{#_android_ide_tail} -gt 38 ]; do _android_ide_tail=${'$'}{_android_ide_tail#?}; done
        case "${'$'}_android_ide_physical_path" in
            "${'$'}HOME"/*) _android_ide_shown="~/${'$'}…${'$'}_android_ide_tail" ;;
            *) _android_ide_shown="…${'$'}_android_ide_tail" ;;
        esac
    fi
    printf '%s' "${'$'}_android_ide_shown"
}
PS1='${promptNonPrintingStart}${promptGreen}${promptNonPrintingEnd}${'$'}(_android_ide_prompt_path)\${'$'} ${promptNonPrintingStart}${promptReset}${promptNonPrintingEnd}'
""".trimIndent(),
        )
        setMode(profile, 0b110000000)
    }

    /** Run the exact shell/cwd combination used by a new terminal session before advertising readiness. */
    fun shellStartupFailure(): String? = runCatching {
        ensureDirectory(home, PRIVATE_DIRECTORY_MODE, "Terminal home")
        if (!repairRuntimeExecutables()) {
            throw IOException("${File(prefix, "bin/sh").absolutePath} is not executable")
        }
        val shellPath = File(prefix, "bin/bash").takeIf { it.canExecute() }?.absolutePath
            ?: File(prefix, "bin/sh").absolutePath
        val process = ProcessBuilder(shellPath, "-c", "exit 0")
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                sessionEnvironment(shellPath).forEach { entry ->
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
            if (!repairRuntimeExecutables()) {
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

    private fun repairRuntimeExecutables(): Boolean = runCatching {
        ensureDirectory(prefix, PREFIX_DIRECTORY_MODE, "Termux prefix")
        val bin = File(prefix, "bin")
        ensureDirectory(bin, PREFIX_DIRECTORY_MODE, "Termux binary directory")
        val required = listOf("sh", "pkg")
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

    private fun installStorageSetupCommand() {
        val command = File(prefix, "bin/termux-setup-storage")
        val action = TerminalStorageSetupProtocol.ACTION
        val packageName = context.packageName
        val requestKindExtra = TerminalStorageSetupProtocol.REQUEST_KIND_EXTRA
        val requestKind = TerminalStorageSetupProtocol.REQUEST_KIND_STORAGE
        val requestIdExtra = TerminalStorageSetupProtocol.REQUEST_ID_EXTRA
        val resultDirectory = TerminalStorageSetupProtocol.RESULT_DIRECTORY
        val script = """#!/system/bin/sh
if [ "${'$'}#" != "0" ]; then
    echo 'usage: termux-setup-storage'
    echo 'Use without arguments to ensure storage permission granted and symlinks to storage available in ${'$'}HOME/storage'
    exit 1
fi

if [ -d "${'$'}HOME/storage" ]; then
    printf '\n'
    printf "\\tIt appears that directory '~/storage' already exists.\\n"
    printf "\\tThis script is going to rebuild its structure from scratch, wiping all dangling files.\\n"
    printf "\\tThe actual storage content IS NOT going to be deleted.\\n\\n"
    printf 'Do you want to continue? (y/n) '
    read -r choice
    case "${'$'}choice" in
        Y|y) ;;
        *) echo "Aborting configuration and leaving directory '~/storage' intact."; exit 1 ;;
    esac
fi

printf 'Requesting Android shared-storage access…\n'
request_id=${'$'}${'$'}
result_dir="${'$'}HOME/$resultDirectory"
result_file="${'$'}result_dir/${'$'}request_id.result"
accepted_file="${'$'}result_dir/${'$'}request_id.accepted"
if ! mkdir -p "${'$'}result_dir"; then
    printf 'Unable to prepare the Android IDE storage-setup result channel.\n' >&2
    exit 1
fi
rm -f "${'$'}result_file" "${'$'}accepted_file" "${'$'}result_dir/${'$'}request_id.tmp"
broadcast_output=${'$'}(am broadcast -n "$packageName/dev.android.ide.runtime.TerminalStorageSetupReceiver" -a "$action" --es "$requestKindExtra" "$requestKind" --es "$requestIdExtra" "${'$'}request_id" 2>&1)
result=${'$'}?
if [ "${'$'}result" -ne 0 ]; then
    printf 'Android IDE did not receive the storage setup request (exit %s).\n' "${'$'}result"
    [ -z "${'$'}broadcast_output" ] || printf '%s\n' "${'$'}broadcast_output"
    exit "${'$'}result"
fi
accepted_wait_seconds=0
while [ ! -f "${'$'}accepted_file" ] && [ ! -f "${'$'}result_file" ]; do
    if [ "${'$'}accepted_wait_seconds" -ge 15 ]; then
        printf 'Android IDE did not acknowledge the storage setup request. Ensure the app is installed for this Android user, then retry.\n' >&2
        [ -z "${'$'}broadcast_output" ] || printf '%s\n' "${'$'}broadcast_output" >&2
        exit 1
    fi
    sleep 1
    accepted_wait_seconds=${'$'}((accepted_wait_seconds + 1))
done
rm -f "${'$'}accepted_file"
wait_seconds=0
while [ ! -f "${'$'}result_file" ]; do
    if [ "${'$'}wait_seconds" -eq 0 ]; then printf 'Waiting for Android IDE storage setup to finish…\n'; fi
    if [ "${'$'}wait_seconds" -ge 300 ]; then
        printf 'Timed out waiting for Android IDE to finish storage setup. Run the command again.\n' >&2
        exit 1
    fi
    sleep 1
    wait_seconds=${'$'}((wait_seconds + 1))
    if [ "${'$'}((wait_seconds % 10))" -eq 0 ]; then
        printf 'Still waiting for Android IDE storage access… (%ss)\n' "${'$'}wait_seconds" >&2
    fi
done
exec 3<"${'$'}result_file"
IFS= read -r result_code <&3
if [ "${'$'}result_code" = "0" ]; then
    cat <&3
else
    cat <&3 >&2
fi
exec 3<&-
rm -f "${'$'}result_file"
if [ "${'$'}result_code" != "0" ]; then exit 1; fi
"""
        command.writeText(script)
        setMode(command, EXECUTABLE_FILE_MODE)
    }

    /** Install the structured, app-aware CLI package used by terminal workflows and diagnostics. */
    private fun installIdeToolsPackage() {
        val packageRoot = File(prefix, "lib/android-ide/tools/android-ide-tools")
        ensureDirectory(packageRoot, PREFIX_DIRECTORY_MODE, "android-ide-tools package")
        File(packageRoot, "package.conf").writeText(
            """name=android-ide-tools
version=2.0.1
command=ide
description=Android IDE inspection, testing, and app-control tools
""".trimIndent() + "\n",
        )
        val commandDirectory = File(prefix, "lib/android-ide/bin")
        ensureDirectory(commandDirectory, PREFIX_DIRECTORY_MODE, "Android IDE command directory")
        val ide = File(commandDirectory, "ide")
        context.assets.open("terminal/ide").use { input ->
            ide.outputStream().use { output -> input.copyTo(output) }
        }
        setMode(ide, EXECUTABLE_FILE_MODE)
        val packageCommand = File(commandDirectory, "android-ide-tools")
        packageCommand.writeText(
            """#!/system/bin/sh
exec "${'$'}{PREFIX:-${prefix.absolutePath}}/lib/android-ide/bin/ide" "${'$'}@"
""".trimIndent() + "\n",
        )
        setMode(packageCommand, EXECUTABLE_FILE_MODE)
    }

    /** Install one canonical command and retain the historical names as thin wrappers. */
    private fun installUrlNavigationCommand() {
        val commandDirectory = File(prefix, "lib/android-ide/bin")
        ensureDirectory(commandDirectory, PREFIX_DIRECTORY_MODE, "Android IDE command directory")
        val idePath = "${'$'}{PREFIX:-${prefix.absolutePath}}/lib/android-ide/bin/ide"
        listOf("android-ide-open-url", "xdg-open", "termux-open", "termux-open-url", "open").forEach { name ->
            val wrapper = File(commandDirectory, name)
            wrapper.writeText(
                """#!/system/bin/sh
exec "$idePath" browser open --new-tab -u "${'$'}@"
""".trimIndent() + "\n",
            )
            setMode(wrapper, EXECUTABLE_FILE_MODE)
        }
    }

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
