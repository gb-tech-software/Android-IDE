package dev.android.ide.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import dev.android.ide.saf.TextDocumentCodec
import java.io.File
import java.util.Locale
import java.util.UUID

/** A normalized external item. Keep the original URI and grant flags; never infer a filesystem path for content URIs. */
data class OpenContentRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val uri: String,
    val mimeType: String?,
    val originalAction: String,
    val grantedFlags: Int,
    val displayName: String?,
)

data class OpenUrlRequest(
    val url: String,
    val openInNewTab: Boolean = false,
    val requestId: String = UUID.randomUUID().toString(),
)

enum class ExternalContentActionKind { BROWSER, IMPORT_ARCHIVE, EXTERNAL }

data class ExternalContentAction(
    val kind: ExternalContentActionKind,
    val label: String,
    val externalHandlerAvailable: Boolean = false,
)

sealed interface ExternalContentRoute {
    data class Browser(val request: OpenUrlRequest) : ExternalContentRoute
    data class ImportArchive(val request: OpenContentRequest) : ExternalContentRoute
    data class External(val request: OpenContentRequest) : ExternalContentRoute
    data class Choose(val request: OpenContentRequest, val actions: List<ExternalContentAction>) : ExternalContentRoute
    data object Unsupported : ExternalContentRoute
}

/**
 * Classifies external Android intents into capabilities that the current app or
 * Android can actually provide. MIME is authoritative when supplied by the
 * provider; extensions are hints and ambiguous resources are signature-sniffed.
 */
class ExternalContentRouter {
    fun route(context: Context, intent: Intent): ExternalContentRoute {
        if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return ExternalContentRoute.Unsupported

        val uri = extractUri(intent) ?: return ExternalContentRoute.Unsupported
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "http" || scheme == "https") {
            return ExternalContentRoute.Browser(OpenUrlRequest(uri.toString()))
        }
        if (scheme != "content" && scheme != "file") return ExternalContentRoute.Unsupported
        if (scheme == "file" && !runCatching { File(uri.path.orEmpty()).isFile && File(uri.path.orEmpty()).canRead() }.getOrDefault(false)) {
            return ExternalContentRoute.Unsupported
        }

        preservePersistableGrant(context, uri, intent.flags)
        val displayName = displayName(context, uri)
        val intentMime = normalizedMime(intent.type)
        val providerMime = normalizedMime(runCatching { context.contentResolver.getType(uri) }.getOrNull())
        val reliableMime = sequenceOf(providerMime, intentMime)
            .firstOrNull { it != null && it !in GENERIC_MIME_TYPES }
        val mime = reliableMime
            ?: sniffMimeType(context, uri, displayName)
            ?: intentMime
            ?: providerMime
            ?: mimeFromName(displayName)
        val request = OpenContentRequest(
            uri = uri.toString(),
            mimeType = mime,
            originalAction = intent.action.orEmpty(),
            grantedFlags = intent.flags,
            displayName = displayName,
        )
        val archive = isSupportedZip(mime, displayName)
        val browser = !archive && isBrowserRenderable(uri, mime, displayName)
        val external = hasExternalViewer(context, request)

        val actions = buildList {
            if (browser) add(ExternalContentAction(ExternalContentActionKind.BROWSER, "Open in browser"))
            if (archive) add(ExternalContentAction(ExternalContentActionKind.IMPORT_ARCHIVE, "Import ZIP as project"))
            if (external) add(ExternalContentAction(ExternalContentActionKind.EXTERNAL, "Open with another app", true))
        }
        return when (actions.size) {
            0 -> ExternalContentRoute.Unsupported
            1 -> routeFor(request, actions.single())
            else -> ExternalContentRoute.Choose(request, actions)
        }
    }

    private fun extractUri(intent: Intent): Uri? {
        intent.data?.let { return it }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { return it }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)) {
            return runCatching { Uri.parse(text) }.getOrNull()
        }
        return null
    }

    private fun routeFor(request: OpenContentRequest, action: ExternalContentAction): ExternalContentRoute = when (action.kind) {
        ExternalContentActionKind.BROWSER -> ExternalContentRoute.Browser(OpenUrlRequest(request.uri, requestId = request.requestId))
        ExternalContentActionKind.IMPORT_ARCHIVE -> ExternalContentRoute.ImportArchive(request)
        ExternalContentActionKind.EXTERNAL -> ExternalContentRoute.External(request)
    }

    private fun hasExternalViewer(context: Context, request: OpenContentRequest): Boolean {
        val uri = Uri.parse(request.uri)
        // Android cannot grant another app access to a raw file:// URI. Keep those
        // sources available to this app's browser/editor, but do not offer a
        // handoff that would fail or expose a filesystem path.
        if (uri.scheme != "content") return false
        val target = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, request.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.packageManager.queryIntentActivities(target, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.packageName != context.packageName }
        }.getOrDefault(false)
    }

    private fun isBrowserRenderable(uri: Uri, mime: String?, displayName: String?): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return true
        if (mime in BROWSER_MIME_TYPES || mime?.startsWith("text/") == true || mime?.startsWith("image/") == true ||
            mime?.startsWith("audio/") == true || mime?.startsWith("video/") == true) return true
        if (mime != null && mime !in GENERIC_MIME_TYPES) return false
        val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return extension in BROWSER_EXTENSIONS
    }

    private fun isSupportedZip(mime: String?, displayName: String?): Boolean {
        if (mime in ZIP_MIME_TYPES) return true
        if (mime != null && mime !in GENERIC_MIME_TYPES) return false
        return displayName?.substringAfterLast('.', "")?.equals("zip", ignoreCase = true) == true
    }

    private fun normalizedMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.contains('/') }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment?.let(Uri::decode)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }.getOrNull() ?: uri.lastPathSegment?.let(Uri::decode)
    }

    private fun mimeFromName(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return null
        return when (ext) {
            "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"
            "txt", "log", "md", "markdown" -> "text/plain"
            "json", "map" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "js", "mjs", "cjs" -> "text/javascript"
            "ts", "tsx", "jsx", "kt", "kts", "java", "py", "sh", "c", "h", "cpp", "rs" -> "text/plain"
            else -> null
        }
    }

    private fun sniffMimeType(context: Context, uri: Uri, displayName: String?): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(512)
            val length = input.read(header).coerceAtLeast(0)
            if (length == 0) return@use null
            fun starts(vararg bytes: Int) = length >= bytes.size && bytes.indices.all { (header[it].toInt() and 0xff) == bytes[it] }
            when {
                starts(0x50, 0x4b, 0x03, 0x04) || starts(0x50, 0x4b, 0x05, 0x06) -> "application/zip"
                starts(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
                starts(0xff, 0xd8, 0xff) -> "image/jpeg"
                starts(0x47, 0x49, 0x46, 0x38) -> "image/gif"
                length >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> if (TextDocumentCodec.isText(header.copyOf(length))) {
                    mimeFromName(displayName)?.takeIf { it.startsWith("text/") || it in TEXT_DOCUMENT_MIME_TYPES } ?: "text/plain"
                } else null
            }
        }
    }.getOrNull()

    private fun preservePersistableGrant(context: Context, uri: Uri, flags: Int) {
        if (uri.scheme != "content" || flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        val accessFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (accessFlags == 0) return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, accessFlags) }
    }

    private companion object {
        val GENERIC_MIME_TYPES = setOf("application/octet-stream", "binary/octet-stream")
        val TEXT_DOCUMENT_MIME_TYPES = setOf("application/json", "application/xml", "application/xhtml+xml", "image/svg+xml", "application/javascript", "application/x-javascript")
        val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
        val BROWSER_MIME_TYPES = setOf(
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/pdf", "application/json",
            "application/xml", "text/xml", "text/csv", "text/javascript", "application/javascript",
        )
        val BROWSER_EXTENSIONS = setOf(
            "html", "htm", "xhtml", "svg", "pdf", "json", "xml", "csv", "txt", "md", "markdown",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "sh", "c", "h", "cpp", "rs",
            "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "ico", "tif", "tiff",
            "mp4", "webm", "ogv", "mkv", "mov", "m4v", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac",
        )
    }
}

internal fun ExternalContentRoute.requestId(): String? = when (this) {
    is ExternalContentRoute.Browser -> request.requestId
    is ExternalContentRoute.ImportArchive -> request.requestId
    is ExternalContentRoute.External -> request.requestId
    is ExternalContentRoute.Choose -> request.requestId
    ExternalContentRoute.Unsupported -> null
}
