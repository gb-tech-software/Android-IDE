package dev.android.ide.project

import dev.android.ide.data.model.GitDetails
import dev.android.ide.data.model.GitRemote
import dev.android.ide.saf.ChildrenInspectionResult
import dev.android.ide.saf.FileManagementService
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * Interprets a Git directory using generic file and folder operations.
 *
 * FileManagementService does not know that these files are Git files. This
 * reader owns only Git layout and Git parsing semantics, so the same reader
 * can be reused by project details, project cards, or another project-aware
 * workflow without adding Git knowledge to storage primitives.
 */
class GitMetadataReader(private val files: FileManagementService) {
    private data class Entry(val uri: String, val directory: Boolean)

    suspend fun read(projectRootUri: String): GitDetails? {
        val entries = linkedMapOf<String, Entry>()
        if (!collect(projectRootUri, "", entries)) return null
        if (entries[".git"]?.directory != true) return null

        val readText: suspend (String?) -> String? = { path ->
            path?.let { entry -> files.read(entry)?.toString(StandardCharsets.UTF_8) }
        }
        val headText = readText(entries[".git/HEAD"]?.uri)?.trim()
        val branchRef = headText?.takeIf { it.startsWith("ref: ") }
            ?.removePrefix("ref: ")?.trim()
        val branch = branchRef?.removePrefix("refs/heads/")
        val packedRefs = readText(entries[".git/packed-refs"]?.uri)
            .orEmpty().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("^") }
            .mapNotNull { line ->
                val parts = line.split(' ', limit = 2)
                if (parts.size == 2 && parts[1].startsWith("refs/heads/")) {
                    parts[1].removePrefix("refs/heads/") to parts[0]
                } else null
            }.toMap()
        val branchRefs = entries.filter { (path, entry) ->
            !entry.directory && path.startsWith(".git/refs/heads/")
        }.mapValues { (path, entry) ->
            path.removePrefix(".git/refs/heads/") to (readText(entry.uri)?.trim().orEmpty())
        }.values.toMap()
        val branches = (branchRefs.keys + packedRefs.keys).filter { it.isNotBlank() }.distinct().sorted()
        val headCommit = when {
            branchRef != null -> branchRefs[branch.orEmpty()] ?: packedRefs[branch.orEmpty()]
            !headText.isNullOrBlank() && !headText.startsWith("ref: ") -> headText
            else -> null
        }?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
        val remotes = parseRemotes(readText(entries[".git/config"]?.uri).orEmpty())
        val commit = headCommit?.let { hash ->
            val objectUri = entries[".git/objects/${hash.take(2)}/${hash.drop(2)}"]?.uri
            parseCommit(objectUri?.let { files.read(it) })
        }
        return GitDetails(
            currentBranch = branch,
            branches = branches,
            remotes = remotes,
            headCommit = headCommit,
            latestCommitMessage = commit?.first,
            latestCommitAuthor = commit?.second,
        )
    }

    private suspend fun collect(uri: String, prefix: String, out: MutableMap<String, Entry>): Boolean {
        when (val result = files.inspectChildren(uri)) {
            is ChildrenInspectionResult.Failed -> return false
            is ChildrenInspectionResult.Success -> for (child in result.children) {
                val path = if (prefix.isEmpty()) child.displayName else "$prefix/${child.displayName}"
                out[path] = Entry(child.documentUri, child.isDirectory)
                if (child.isDirectory && !collect(child.documentUri, path, out)) return false
            }
        }
        return true
    }

    private fun parseRemotes(config: String): List<GitRemote> {
        var current: String? = null
        val remotes = mutableListOf<GitRemote>()
        config.lineSequence().forEach { raw ->
            val line = raw.trim()
            val section = Regex("\\[remote \\\"([^\\\"]+)\\\"]").matchEntire(line)
            if (section != null) current = section.groupValues[1]
            else if (current != null && line.startsWith("url")) {
                val url = line.substringAfter('=', "").trim()
                if (url.isNotEmpty()) {
                    remotes += GitRemote(current!!, url)
                    current = null
                }
            } else if (line.startsWith("[") && !line.startsWith("[remote")) current = null
        }
        return remotes
    }

    private fun parseCommit(bytes: ByteArray?): Pair<String?, String?>? {
        if (bytes == null) return null
        return runCatching {
            val inflated = InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            val body = inflated.toString(StandardCharsets.UTF_8).substringAfter("\u0000", "")
            val message = body.substringAfter("\n\n", "").trim().lineSequence()
                .firstOrNull()?.takeIf { it.isNotBlank() }
            val author = Regex("^author (.+) <[^>]+> \\d+ [+-]\\d+$", RegexOption.MULTILINE)
                .find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            message to author
        }.getOrNull()
    }
}
