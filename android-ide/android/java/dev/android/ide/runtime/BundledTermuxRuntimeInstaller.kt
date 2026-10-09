package dev.android.ide.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import dev.android.ide.contracts.TerminalBrowserRequestProtocol
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
    private val defaultPackagesMarker = File(context.filesDir, ".android-ide-offline-runtime-2026.10.09-r1")
    private val bundledToolsRevision = "2026.10.09-r1"

    /** Runtime packages are installed through the terminal's own package manager; no second UI is needed. */
    val defaultPackagePlan: List<String> = listOf(
        "curl", "git", "openssh", "ca-certificates", "procps", "nodejs-lts", "python", "zip", "unzip",
        "live-server", "typescript", "typescript-language-server", "pyright", "vscode-langservers-extracted",
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

            onProgress("Setting up the bundled offline terminal packages…")
            val packages = installBundledRuntimePackages(assetName, onProgress)
            if (packages.outcome != OperationOutcome.COMPLETE) return@runCatching packages

            installInteractiveShellProfile()
            installStorageSetupCommand()
            installBrowserRoutingCommands()
            shellStartupFailure()?.let { throw IOException(it) }
            OperationReport(OperationOutcome.COMPLETE, "Bundled terminal, Git, language servers, and preview tools are ready")
        }.getOrElse { error ->
            unavailable("The bundled terminal runtime could not be initialized: ${error.message ?: "unknown error"}")
        }
    }

    fun prefix(): File = prefix
    fun home(): File = home
    fun userFilesRoot(): File = userFiles
    fun rebuildStorageLinks(): OperationReport = storageBridge.rebuildLinks()
    fun publishStorageSetupResult(requestId: String, report: OperationReport) =
        storageSetupResultChannel.publish(requestId, report)

    /** The same environment is used by the preflight probe and the interactive PTY. */
    fun sessionEnvironment(): Array<String> = arrayOf(
        "HOME=${home.absolutePath}",
        "PREFIX=${prefix.absolutePath}",
        "PATH=${File(prefix, "lib/android-ide/bin").absolutePath}:${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
        "SHELL=${File(prefix, "bin/sh").absolutePath}",
        "BROWSER=${File(prefix, "lib/android-ide/bin/android-ide-open-url").absolutePath}",
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
    _android_ide_physical_path=${'$'}(command pwd -P 2>/dev/null)
    case "${'$'}_android_ide_physical_path" in
        "${'$'}HOME") _android_ide_shown='~' ;;
        "${'$'}HOME"/*)
            _android_ide_tail=${'$'}{PWD#"${'$'}HOME"/}
            if [ ${'$'}{#_android_ide_tail} -gt 23 ]; then
                while [ ${'$'}{#_android_ide_tail} -gt 23 ] && [ "${'$'}{_android_ide_tail#*/}" != "${'$'}_android_ide_tail" ]; do
                    _android_ide_tail=${'$'}{_android_ide_tail#*/}
                done
                while [ ${'$'}{#_android_ide_tail} -gt 23 ]; do _android_ide_tail=${'$'}{_android_ide_tail#?}; done
                _android_ide_shown="~/.../${'$'}_android_ide_tail"
            else
                _android_ide_shown="~/${'$'}_android_ide_tail"
            fi
            ;;
        "${'$'}{HOME%/*}") _android_ide_shown='..' ;;
        "${'$'}{HOME%/*}"/*) _android_ide_shown="../${'$'}{_android_ide_physical_path#"${'$'}{HOME%/*}"/}" ;;
        *)
            _android_ide_tail=${'$'}{_android_ide_physical_path##*/}
            if [ -z "${'$'}_android_ide_tail" ]; then _android_ide_shown='/'; else _android_ide_shown="…/${'$'}_android_ide_tail"; fi
            ;;
    esac
    printf '%s' "${'$'}_android_ide_shown"
}
PS1='\[\033[1;32m\]${'$'}(_android_ide_prompt_path)\${'$'} \[\033[0m\]'
""".trimIndent(),
        )
        setMode(profile, 0b110000000)
    }

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

    private fun installBundledRuntimePackages(
        assetName: String,
        onProgress: (String) -> Unit,
    ): OperationReport {
        val shell = File(prefix, "bin/sh")
        if (defaultPackagesMarker.isFile && runtimePackagesAvailable()) {
            return OperationReport(OperationOutcome.COMPLETE, "Bundled offline runtime packages are available")
        }
        if (!repairRuntimeExecutables(includeNpm = false)) {
            return OperationReport(
                OperationOutcome.BLOCKED,
                "The bundled shell or package manager is not executable",
                ErrorCategory.UNAVAILABLE_RUNTIME,
            )
        }

        val staging = File(context.filesDir, "termux-runtime-bundle.staging")
        return try {
            onProgress("Unpacking the offline terminal and language-tool bundle…")
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("Unable to clear runtime bundle staging")
            ensureDirectory(staging, PRIVATE_DIRECTORY_MODE, "Runtime bundle staging")
            extractRuntimeBundle(assetName, staging)

            val repository = File(staging, "offline-repo")
            val packageIndex = File(repository, "Packages.gz")
            val packageManifest = File(repository, "manifest.json")
            if (!repository.isDirectory || !packageIndex.isFile || !packageManifest.isFile) {
                throw IOException("The bundled offline package repository is incomplete")
            }
            val sourceList = File(staging, "offline.sources.list")
            sourceList.writeText("deb [trusted=yes] file:${repository.absolutePath} ./\n")
            val aptLists = File(prefix, "var/lib/apt/lists/android-ide-offline")
            ensureDirectory(File(prefix, "var/lib/apt/lists"), PREFIX_DIRECTORY_MODE, "APT list directory")
            if (aptLists.exists() && !aptLists.deleteRecursively()) throw IOException("Unable to clear stale offline APT indexes")
            ensureDirectory(aptLists, PREFIX_DIRECTORY_MODE, "Offline APT list directory")

            onProgress("Setting up bundled terminal packages without network access…")
            val aptOptions = "-o Dir::Etc::sourcelist='${sourceList.absolutePath}' " +
                "-o Dir::Etc::sourceparts='-' " +
                "-o Dir::State::lists='${aptLists.absolutePath}' " +
                "-o APT::Get::List-Cleanup='0' -o Acquire::Languages='none'"
            val update = runCommand(shell, "\"${File(prefix, "bin/apt-get").absolutePath}\" $aptOptions update")
            if (update.exitCode != 0) {
                return OperationReport(
                    OperationOutcome.PARTIAL,
                    commandFailure("The bundled offline package index could not be prepared", update),
                    ErrorCategory.PACKAGE_FAILURE,
                    recoveryHint = "Install an APK containing the complete runtime bundle for this device architecture.",
                )
            }
            val packageNames = listOf("ca-certificates", "curl", "git", "nodejs-lts", "openssh", "procps", "python", "unzip", "zip")
            val install = runCommand(
                shell,
                "\"${File(prefix, "bin/apt-get").absolutePath}\" $aptOptions install -y --no-install-recommends ${packageNames.joinToString(" ")}",
            )
            if (install.exitCode != 0) {
                return OperationReport(
                    OperationOutcome.PARTIAL,
                    commandFailure("The bundled offline terminal packages could not be set up", install),
                    ErrorCategory.PACKAGE_FAILURE,
                    recoveryHint = "Install an APK with a matching, complete offline runtime bundle and retry Terminal initialization.",
                )
            }

            val toolManifest = File(staging, "tools/commands.json")
            val nodeModules = File(staging, "tools/node_modules")
            if (!toolManifest.isFile || !nodeModules.isDirectory) throw IOException("The bundled language-tool tree is incomplete")
            val toolRoot = File(prefix, "lib/android-ide/tools/$bundledToolsRevision")
            val installedModules = File(toolRoot, "node_modules")
            copyDirectoryTree(nodeModules, installedModules)
            val commandDirectory = File(prefix, "lib/android-ide/bin")
            ensureDirectory(commandDirectory, PREFIX_DIRECTORY_MODE, "Android IDE command directory")
            installBundledCommandWrappers(toolManifest, installedModules, commandDirectory)

            if (!repairRuntimeExecutables(includeNpm = true) || !runtimePackagesAvailable()) {
                throw IOException("One or more bundled terminal or language-server commands are missing")
            }
            shellStartupFailure()?.let { throw IOException(it) }
            defaultPackagesMarker.writeText(defaultPackagePlan.joinToString("\n"))
            OperationReport(OperationOutcome.COMPLETE, "Bundled offline terminal packages are ready")
        } catch (error: Exception) {
            OperationReport(
                OperationOutcome.PARTIAL,
                "The bundled offline terminal tools could not be initialized: ${error.message ?: error.javaClass.simpleName}",
                ErrorCategory.PACKAGE_FAILURE,
                recoveryHint = "Install an APK containing the complete offline runtime bundle for this device architecture and retry Terminal initialization.",
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun runtimePackagesAvailable(): Boolean {
        val baseline = listOf("curl", "git", "ssh", "node", "npm", "python", "zip", "unzip", "ps")
        if (!baseline.all { File(prefix, "bin/$it").canExecute() } || !File(prefix, "etc/tls/cert.pem").isFile) return false
        val tools = File(prefix, "lib/android-ide/bin")
        return listOf(
            "live-server",
            "pyright-langserver",
            "tsc",
            "tsserver",
            "typescript-language-server",
            "vscode-css-language-server",
            "vscode-html-language-server",
            "vscode-json-language-server",
        ).all { File(tools, it).canExecute() }
    }

    private fun extractRuntimeBundle(assetName: String, destination: File) {
        val assetPath = "termux/runtime-$assetName.zip"
        context.assets.open(assetPath).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name
                    if (relative.isBlank() || relative.startsWith("/") || relative.contains('\\') ||
                        relative.split('/').any { it == ".." || it == "." }
                    ) throw IOException("The bundled runtime archive contains an unsafe path")
                    val target = File(destination, relative).canonicalFile
                    if (!target.toPath().startsWith(destination.canonicalFile.toPath())) {
                        throw IOException("The bundled runtime archive escapes its staging directory")
                    }
                    if (entry.isDirectory) {
                        ensureDirectory(target, PREFIX_DIRECTORY_MODE, "Runtime bundle directory")
                    } else {
                        target.parentFile?.let { ensureDirectory(it, PREFIX_DIRECTORY_MODE, "Runtime bundle parent") }
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                        setMode(target, 0b110100100)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun copyDirectoryTree(source: File, destination: File) {
        if (!source.isDirectory) throw IOException("Bundled language-tool directory is missing")
        ensureDirectory(destination, PREFIX_DIRECTORY_MODE, "Installed language-tool directory")
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) {
                copyDirectoryTree(child, target)
            } else {
                child.inputStream().buffered().use { input -> target.outputStream().buffered().use(input::copyTo) }
                setMode(target, 0b110100100)
            }
        }
    }

    private fun installBundledCommandWrappers(
        manifestFile: File,
        installedModules: File,
        commandDirectory: File,
    ) {
        val commands = org.json.JSONObject(manifestFile.readText()).optJSONObject("commands")
            ?: throw IOException("The bundled language-tool command manifest is invalid")
        val commandNamePattern = Regex("[A-Za-z0-9._-]+")
        val entryPattern = Regex("[A-Za-z0-9._/-]+")
        val iterator = commands.keys()
        while (iterator.hasNext()) {
            val command = iterator.next()
            if (!commandNamePattern.matches(command)) throw IOException("Invalid bundled command name")
            val definition = commands.optJSONObject(command) ?: throw IOException("Invalid command entry for $command")
            val packageName = definition.optString("package")
            val entry = definition.optString("entry")
            if (packageName !in defaultToolPackages || !entryPattern.matches(entry) || entry.split('/').any { it == ".." || it == "." }) {
                throw IOException("Invalid language-tool entry point for $command")
            }
            val modulePath = File(installedModules, packageName.replace('/', File.separatorChar)).canonicalFile
            val entryFile = File(modulePath, entry).canonicalFile
            if (!modulePath.isDirectory || !entryFile.isFile || !entryFile.toPath().startsWith(modulePath.toPath())) {
                throw IOException("The language-tool entry point for $command is missing")
            }
            val script = """#!/system/bin/sh
_android_ide_prefix=${'$'}{PREFIX:-${prefix.absolutePath}}
exec "${'$'}_android_ide_prefix/bin/node" "${'$'}_android_ide_prefix/lib/android-ide/tools/$bundledToolsRevision/node_modules/${packageName.replace("'", "'\\''")}/$entry" "${'$'}@"
""".trimIndent() + "\n"
            val wrapper = File(commandDirectory, command)
            wrapper.writeText(script)
            setMode(wrapper, EXECUTABLE_FILE_MODE)
        }
        if (commands.length() == 0 || !runtimePackagesAvailableExceptTools()) {
            throw IOException("The bundled language-tool command manifest is incomplete")
        }
    }

    private fun runtimePackagesAvailableExceptTools(): Boolean =
        listOf("curl", "git", "ssh", "node", "npm", "python", "zip", "unzip", "ps")
            .all { File(prefix, "bin/$it").canExecute() } && File(prefix, "etc/tls/cert.pem").isFile

    private val defaultToolPackages = setOf(
        "live-server",
        "pyright",
        "typescript",
        "typescript-language-server",
        "vscode-langservers-extracted",
    )

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

    private fun installStorageSetupCommand() {
        val command = File(prefix, "bin/termux-setup-storage")
        val action = "${context.packageName}.app.reload_style"
        val packageName = context.packageName
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
if ! mkdir -p "${'$'}result_dir"; then
    printf 'Unable to prepare the Android IDE storage-setup result channel.\n' >&2
    exit 1
fi
rm -f "${'$'}result_file" "${'$'}result_dir/${'$'}request_id.tmp"
user_id="${'$'}{TERMUX__USER_ID:-0}"
case "${'$'}user_id" in
    ''|*[!0-9]*|0[0-9]*) user_id=0 ;;
esac
broadcast_output=${'$'}(am broadcast --user "${'$'}user_id" -a "$action" -p "$packageName" --es "$action" storage --es "$requestIdExtra" "${'$'}request_id" 2>&1)
result=${'$'}?
if [ "${'$'}result" -ne 0 ]; then
    printf 'Android IDE did not receive the storage setup request (exit %s).\n' "${'$'}result"
    [ -z "${'$'}broadcast_output" ] || printf '%s\n' "${'$'}broadcast_output"
    exit "${'$'}result"
fi
printf 'Waiting for Android IDE storage setup to finish…\n'
wait_seconds=0
while [ ! -f "${'$'}result_file" ]; do
    if [ "${'$'}wait_seconds" -ge 600 ]; then
        printf 'Timed out waiting for Android IDE to finish storage setup. Run the command again.\n' >&2
        exit 1
    fi
    sleep 1
    wait_seconds=${'$'}((wait_seconds + 1))
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

    /** Keep browser-opening commands inside Android IDE; no external intent fallback is allowed. */
    private fun installBrowserRoutingCommands() {
        val commandDirectory = File(prefix, "lib/android-ide/bin")
        ensureDirectory(commandDirectory, PREFIX_DIRECTORY_MODE, "Android IDE command directory")
        val action = TerminalBrowserRequestProtocol.ACTION
        val urlExtra = TerminalBrowserRequestProtocol.URL_EXTRA
        val packageName = context.packageName
        val opener = File(commandDirectory, "android-ide-open-url")
        opener.writeText(
            """#!/system/bin/sh
if [ "${'$'}#" -lt 1 ]; then
    echo 'usage: android-ide-open-url URL' >&2
    exit 2
fi
url=${'$'}1
case "${'$'}url" in
    localhost:*|127.0.0.1:*) url="http://${'$'}url" ;;
esac
case "${'$'}url" in
    http://*|https://*) ;;
    *) echo 'Android IDE Browser accepts only HTTP(S) URLs from Terminal.' >&2; exit 2 ;;
esac
user_id=${'$'}{TERMUX__USER_ID:-0}
case "${'$'}user_id" in
    ''|*[!0-9]*|0[0-9]*) user_id=0 ;;
esac
output=${'$'}(am broadcast --user "${'$'}user_id" -a "$action" -p "$packageName" --es "$urlExtra" "${'$'}url" 2>&1)
result=${'$'}?
if [ "${'$'}result" -ne 0 ]; then
    printf 'Android IDE Browser request failed (exit %s).\n' "${'$'}result" >&2
    [ -z "${'$'}output" ] || printf '%s\n' "${'$'}output" >&2
    exit "${'$'}result"
fi
printf 'Opened in Android IDE Browser: %s\n' "${'$'}url"
""".trimIndent() + "\n",
        )
        setMode(opener, EXECUTABLE_FILE_MODE)
        listOf("xdg-open", "termux-open", "termux-open-url", "open").forEach { name ->
            val wrapper = File(commandDirectory, name)
            wrapper.writeText(
                """#!/system/bin/sh
exec "${'$'}{PREFIX:-${prefix.absolutePath}}/lib/android-ide/bin/android-ide-open-url" "${'$'}@"
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
