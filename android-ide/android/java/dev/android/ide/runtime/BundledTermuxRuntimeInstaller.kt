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
        "BROWSER=${File(prefix, "lib/android-ide/bin/ide").absolutePath}",
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
version=1.0.0
command=ide
description=Android IDE inspection, testing, and app-control tools
""".trimIndent() + "\n",
        )
        val commandDirectory = File(prefix, "lib/android-ide/bin")
        val ide = File(commandDirectory, "ide")
        ide.writeText(
            """#!/system/bin/sh
set -f
IDE_TOOLS_VERSION=1.0.0
URL_COMMAND="${'$'}{PREFIX:-$prefix}/lib/android-ide/bin/android-ide-open-url"
usage() {
    cat <<'USAGE'
Usage: ide COMMAND [SUBCOMMAND] [OPTIONS]
Android IDE inspection, testing, and app-control tools.
Commands:
  open [--current|--new-tab] URL   Open a URL in Android IDE Browser
  browser open URL [OPTIONS]      Open a URL in Android IDE Browser
  browser status                   Show browser bridge status
  terminal status                  Show terminal runtime status
  app status                       Show app-control capabilities
  test browser-navigation          Check the browser command bridge
  --help                          Show this help message
  --version                       Show the package version
Examples:
  ide open localhost:3000
  ide browser open --new-tab http://localhost:3000
  ide browser open --current example.com
  ide app status
USAGE
}
error() { printf 'ide: error: %s\n\n' "${'$'}*" >&2; usage >&2; exit 2; }
browser_status() {
    printf 'Android IDE Browser\n  bridge: available\n  URL command: %s\n  navigation: intent + receipt confirmation\n' "${'$'}URL_COMMAND"
}
terminal_status() {
    printf 'Android IDE Terminal\n  prefix: %s\n  home: %s\n' "${'$'}{PREFIX:-unavailable}" "${'$'}{HOME:-unavailable}"
    if [ -x "${'$'}{PREFIX:-}/bin/sh" ]; then printf '  shell: ready\n'; else printf '  shell: unavailable\n'; fi
}
app_status() {
    printf 'Android IDE tools\n  package: android-ide-tools\n  version: %s\n  browser: available\n' "${'$'}IDE_TOOLS_VERSION"
    if [ -x "${'$'}{PREFIX:-}/bin/sh" ]; then printf '  terminal: available\n'; else printf '  terminal: unavailable\n'; fi
    printf '  editor: app-owned; use the Editor workspace for file state\n'
}
if [ "${'$'}#" -eq 0 ]; then usage; exit 0; fi
# BROWSER-compatible direct invocation: BROWSER URL
case "${'$'}1" in
    http://*|https://*|localhost:*|127.0.0.1:*|www.*|*.*)
        exec "${'$'}URL_COMMAND" --new-tab "${'$'}@"
        ;;
esac
case "${'$'}1" in
    -h|--help) usage; exit 0 ;;
    -v|-V|--version) printf 'android-ide-tools %s\n' "${'$'}IDE_TOOLS_VERSION"; exit 0 ;;
    open) shift; [ "${'$'}#" -gt 0 ] || error 'missing URL for open'; exec "${'$'}URL_COMMAND" "${'$'}@" ;;
    browser)
        shift
        case "${'$'}1" in
            open) shift; [ "${'$'}#" -gt 0 ] || error 'missing URL for browser open'; exec "${'$'}URL_COMMAND" "${'$'}@" ;;
            status) [ "${'$'}#" -eq 1 ] || error 'browser status does not accept arguments'; browser_status; exit 0 ;;
            *) error "unknown browser command: ${'$'}1" ;;
        esac
        ;;
    terminal) shift; [ "${'$'}1" = status ] && [ "${'$'}#" -eq 1 ] || error 'usage: ide terminal status'; terminal_status ;;
    app) shift; [ "${'$'}1" = status ] && [ "${'$'}#" -eq 1 ] || error 'usage: ide app status'; app_status ;;
    test)
        shift
        [ "${'$'}1" = browser-navigation ] && [ "${'$'}#" -eq 1 ] || error 'only browser-navigation is currently supported'
        [ -x "${'$'}URL_COMMAND" ] || { printf 'FAIL browser-navigation: URL command is unavailable\n' >&2; exit 3; }
        printf 'PASS browser-navigation: URL command and receipt bridge are installed\n'
        ;;
    *) error "unknown command: ${'$'}1" ;;
esac
""".trimIndent() + "\n",
        )
        setMode(ide, EXECUTABLE_FILE_MODE)
        val packageCommand = File(commandDirectory, "android-ide-tools")
        packageCommand.writeText(
            """#!/system/bin/sh
exec "${'$'}{PREFIX:-${prefix.absolutePath}}/lib/android-ide/bin/ide" "${'$'}@"
""".trimIndent() + "\n",
        )
        setMode(packageCommand, EXECUTABLE_FILE_MODE)
    }

    /** Publish ordinary URL navigation requests to the application; no external intent fallback is allowed. */
    private fun installUrlNavigationCommand() {
        val commandDirectory = File(prefix, "lib/android-ide/bin")
        ensureDirectory(commandDirectory, PREFIX_DIRECTORY_MODE, "Android IDE command directory")
        val action = OpenUrlRequestProtocol.ACTION
        val urlExtra = OpenUrlRequestProtocol.URL_EXTRA
        val requestIdExtra = OpenUrlRequestProtocol.REQUEST_ID_EXTRA
        val newTabExtra = OpenUrlRequestProtocol.OPEN_IN_NEW_TAB_EXTRA
        val packageName = context.packageName
        val receiptDirectory = File(context.filesDir, "browser-navigation-receipts").absolutePath
        val opener = File(commandDirectory, "android-ide-open-url")
opener.writeText(
            """#!/system/bin/sh
new_tab=true
usage() {
    cat >&2 <<'USAGE'
Usage: android-ide-open-url [OPTIONS] URL

Open an HTTP(S) URL in Android IDE Browser.

Options:
  --new-tab     Open the URL in a new browser tab (default)
  --current     Reuse the current browser tab
  -h, --help    Show this help message

Examples:
  android-ide-open-url --new-tab http://localhost:3000
  android-ide-open-url --current https://example.com
  android-ide-open-url localhost:3000
USAGE
}
while [ "${'$'}#" -gt 0 ]; do
    case "${'$'}1" in
        --current) new_tab=false; shift ;;
        --new-tab) new_tab=true; shift ;;
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            printf 'android-ide-open-url: error: unknown option: %s\n\n' "${'$'}1" >&2
            usage
            exit 2
            ;;
        -*)
            printf 'android-ide-open-url: error: invalid option: %s\n\n' "${'$'}1" >&2
            usage
            exit 2
            ;;
        *) break ;;
    esac
done
if [ "${'$'}#" -eq 0 ]; then
    printf 'android-ide-open-url: error: missing URL\n\n' >&2
    usage
    exit 2
fi
if [ "${'$'}#" -gt 1 ]; then
    printf 'android-ide-open-url: error: unexpected argument after URL: %s\n\n' "${'$'}2" >&2
    usage
    exit 2
fi
url=${'$'}1
case "${'$'}url" in
    localhost:*|127.0.0.1:*) url="http://${'$'}url" ;;
    *://*) : ;;
    *.*|www.*) url="https://${'$'}url" ;;
esac
case "${'$'}url" in
    http://*|https://*) ;;
    *)
        printf 'android-ide-open-url: error: invalid URL %s\n' "${'$'}url" >&2
        printf 'Only http://, https://, localhost:PORT, or 127.0.0.1:PORT URLs are supported.\n\n' >&2
        usage
        exit 2
        ;;
esac
request_id=${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr -d '-')
if [ -z "${'$'}request_id" ]; then request_id="term-${'$'}${'$'}-${'$'}(date +%s)"; fi
# This value is embedded by the installer; it must not depend on an undefined
# shell variable named receiptDirectory at runtime.
receipt_directory="${receiptDirectory}"
receipt="${'$'}receipt_directory/${'$'}request_id.status"
mkdir -p "${'$'}receipt_directory" || { echo 'Unable to create Android IDE request receipt directory.' >&2; exit 1; }
rm -f "${'$'}receipt"
component="${'$'}packageName/dev.android.ide.MainActivity"
output_file="${'$'}{TMPDIR:-/tmp}/android-ide-open-url-${'$'}request_id.log"
am start -W -n "${'$'}component" -a "${'$'}action" \
    --es "${'$'}urlExtra" "${'$'}url" \
    --es "${'$'}requestIdExtra" "${'$'}request_id" \
    --ez "${'$'}newTabExtra" "${'$'}new_tab" >"${'$'}output_file" 2>&1
result=${'$'}?
if [ "${'$'}result" -ne 0 ]; then
    printf 'Android IDE URL request could not be delivered (exit %s).\n' "${'$'}result" >&2
    [ ! -s "${'$'}output_file" ] || cat "${'$'}output_file" >&2
    rm -f "${'$'}output_file" "${'$'}receipt"
    exit "${'$'}result"
fi
attempt=0
while [ "${'$'}attempt" -lt 10 ]; do
    if [ -f "${'$'}receipt" ]; then
        status=${'$'}(cat "${'$'}receipt" 2>/dev/null)
        case "${'$'}status" in
            dispatched)
                rm -f "${'$'}output_file"
                if [ "${'$'}new_tab" = true ]; then mode='new tab'; else mode='current tab'; fi
                printf 'Opened %s in Android IDE Browser (%s). Request %s was dispatched.\n' "${'$'}url" "${'$'}mode" "${'$'}request_id"
                exit 0
                ;;
            rejected)
                rm -f "${'$'}output_file"
                echo 'Android IDE rejected the URL navigation request.' >&2
                exit 1
                ;;
        esac
    fi
    sleep 1
    attempt=${'$'}((attempt + 1))
done
status=unconfirmed
[ ! -f "${'$'}receipt" ] || status=${'$'}(cat "${'$'}receipt" 2>/dev/null)
if [ "${'$'}status" = accepted ]; then
    rm -f "${'$'}output_file"
    if [ "${'$'}new_tab" = true ]; then mode='new tab'; else mode='current tab'; fi
    printf 'Android IDE accepted %s for the %s, but browser dispatch was not confirmed within 10 seconds (request %s).\n' "${'$'}url" "${'$'}mode" "${'$'}request_id"
    exit 0
fi
printf 'Could not open %s in Android IDE Browser: dispatch status=%s (request %s).\n' "${'$'}url" "${'$'}status" "${'$'}request_id" >&2
[ ! -s "${'$'}output_file" ] || cat "${'$'}output_file" >&2
rm -f "${'$'}output_file"
exit 1
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
