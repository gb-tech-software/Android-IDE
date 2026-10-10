package dev.android.ide.editor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.android.ide.saf.TextDocumentCodec
import dev.android.ide.saf.TextEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal const val MAX_STANDALONE_DOCUMENT_BYTES = 5L * 1024L * 1024L

internal data class StandaloneDocumentSnapshot(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val language: String,
    val content: String,
    val encoding: TextEncoding,
    val sourceDigest: String,
    val isReadOnly: Boolean,
)

internal sealed interface StandaloneDocumentOpenResult {
    data class Opened(val snapshot: StandaloneDocumentSnapshot) : StandaloneDocumentOpenResult
    data class Failed(val message: String) : StandaloneDocumentOpenResult
}

internal sealed interface StandaloneDocumentSaveResult {
    data class Saved(val snapshot: StandaloneDocumentSnapshot) : StandaloneDocumentSaveResult
    data object SourceChanged : StandaloneDocumentSaveResult
    data object ReadOnly : StandaloneDocumentSaveResult
    data object TooLarge : StandaloneDocumentSaveResult
    data class Failed(val message: String) : StandaloneDocumentSaveResult
}

/** Direct source-URI access for standalone editor documents; it never stages or copies a file. */
internal class StandaloneDocumentEditorService(context: Context) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    suspend fun open(
        uriString: String,
        mimeHint: String?,
        nameHint: String?,
        grantFlags: Int,
    ): StandaloneDocumentOpenResult = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext StandaloneDocumentOpenResult.Failed("The document URI is invalid")
        if (uri.scheme != "content" && uri.scheme != "file") {
            return@withContext StandaloneDocumentOpenResult.Failed("Only accessible content and file URIs can be opened")
        }
        if (uri.scheme == "content") persistGrantedAccess(uri, grantFlags)
        val bytes = readBounded(uri)
            ?: return@withContext StandaloneDocumentOpenResult.Failed("The source file could not be read")
        if (bytes.size.toLong() > MAX_STANDALONE_DOCUMENT_BYTES) {
            return@withContext StandaloneDocumentOpenResult.Failed("Files larger than 5 MiB are not supported in the standalone editor")
        }
        val decoded = TextDocumentCodec.decode(bytes)
            ?: return@withContext StandaloneDocumentOpenResult.Failed("This item is not a supported text or code document")
        val displayName = nameHint?.takeIf(String::isNotBlank) ?: queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "Untitled"
        val mime = normalizeMime(mimeHint ?: runCatching { resolver.getType(uri) }.getOrNull())
        val writable = runCatching { canWrite(uri) }.getOrDefault(false)
        StandaloneDocumentOpenResult.Opened(
            StandaloneDocumentSnapshot(
                uri = uri,
                displayName = displayName,
                mimeType = mime,
                language = EditorLanguageRegistry.languageForFileName(displayName),
                content = decoded.text,
                encoding = decoded.encoding,
                sourceDigest = digest(bytes),
                isReadOnly = !writable,
            ),
        )
    }

    suspend fun save(
        snapshot: StandaloneDocumentSnapshot,
        content: String,
    ): StandaloneDocumentSaveResult = withContext(Dispatchers.IO) {
        val encoded = runCatching { snapshot.encoding.encode(content) }.getOrElse {
            return@withContext StandaloneDocumentSaveResult.Failed("The text could not be encoded for this source file")
        }
        if (encoded.size.toLong() > MAX_STANDALONE_DOCUMENT_BYTES) return@withContext StandaloneDocumentSaveResult.TooLarge
        if (!runCatching { canWrite(snapshot.uri) }.getOrDefault(false)) return@withContext StandaloneDocumentSaveResult.ReadOnly
        val currentBytes = readBounded(snapshot.uri)
            ?: return@withContext StandaloneDocumentSaveResult.Failed("The source can no longer be read")
        if (digest(currentBytes) != snapshot.sourceDigest) return@withContext StandaloneDocumentSaveResult.SourceChanged

        try {
            resolver.openOutputStream(snapshot.uri, "wt")?.use { output ->
                output.write(encoded)
                output.flush()
            } ?: return@withContext StandaloneDocumentSaveResult.Failed("The provider did not open the source for writing")
        } catch (error: Exception) {
            return@withContext StandaloneDocumentSaveResult.Failed(error.message ?: "The source could not be written")
        }

        val verifiedBytes = readBounded(snapshot.uri)
            ?: return@withContext StandaloneDocumentSaveResult.Failed("The file was written but could not be verified")
        if (!verifiedBytes.contentEquals(encoded)) {
            return@withContext StandaloneDocumentSaveResult.Failed("The provider did not preserve the complete saved contents")
        }
        StandaloneDocumentSaveResult.Saved(
            snapshot.copy(
                content = content,
                sourceDigest = digest(verifiedBytes),
                isReadOnly = !runCatching { canWrite(snapshot.uri) }.getOrDefault(false),
            ),
        )
    }

    private fun readBounded(uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_STANDALONE_DOCUMENT_BYTES) {
                    return@use ByteArray(MAX_STANDALONE_DOCUMENT_BYTES.toInt() + 1)
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun canWrite(uri: Uri): Boolean {
        if (uri.scheme == "file") return runCatching {
            val file = File(uri.path ?: return false)
            file.isFile && file.canRead() && file.canWrite()
        }.getOrDefault(false)
        if (uri.scheme != "content" || !hasUriGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return false

        if (DocumentsContract.isDocumentUri(appContext, uri)) {
            val declaredWriteSupport = runCatching {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else {
                        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                        if (index < 0 || cursor.isNull(index)) null
                        else (cursor.getLong(index).toInt() and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                    }
                }
            }.getOrNull()
            if (declaredWriteSupport == false) return false
            if (declaredWriteSupport == true) return true
        }

        // For non-Documents providers there is no standard capability column.
        // The "rw" mode probes access without truncating or changing file bytes.
        return runCatching { resolver.openFileDescriptor(uri, "rw")?.use { true } ?: false }.getOrDefault(false)
    }

    private fun hasUriGrant(uri: Uri, flag: Int): Boolean =
        appContext.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) == PackageManager.PERMISSION_GRANTED

    private fun persistGrantedAccess(uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        var available = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!hasUriGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) available = available and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
        if (!hasUriGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) available = available and Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
        if (available == 0) return
        runCatching { resolver.takePersistableUriPermission(uri, available) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }
        }
    }.getOrNull()

    private fun normalizeMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.contains('/') }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
