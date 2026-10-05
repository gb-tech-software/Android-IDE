package dev.android.ide.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Implements the app-side half of Termux's termux-setup-storage flow.
 *
 * The command owns confirmation. Once Android grants storage permission, this
 * class rebuilds ~/storage from scratch, deleting only that directory's local
 * link structure and never following links into public storage.
 */
class TermuxStorageBridge(
    private val context: Context,
    private val home: File,
) {
    private val storageDirectory = File(home, "storage")

    fun rebuildLinks(): OperationReport = runCatching {
        clearStorageDirectory()
        if (!storageDirectory.isDirectory && !storageDirectory.mkdirs() && !storageDirectory.isDirectory) {
            throw IOException("Unable to create ${storageDirectory.absolutePath}")
        }

        val primary = Environment.getExternalStorageDirectory()
        link(primary, "shared")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "documents")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "downloads")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "dcim")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "pictures")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "music")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "movies")
        link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS), "podcasts")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            link(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS), "audiobooks")
        }

        context.getExternalFilesDirs(null).forEachIndexed { index, target ->
            target?.let { link(it, "external-$index") }
        }
        context.getExternalMediaDirs().forEachIndexed { index, target ->
            target?.let { link(it, "media-$index") }
        }

        OperationReport(
            OperationOutcome.COMPLETE,
            "Storage links created successfully under ~/storage",
        )
    }.getOrElse { error ->
        OperationReport(
            OperationOutcome.FAILED,
            "Android shared storage links could not be created",
            ErrorCategory.PERMISSION_LOST,
            recoveryHint = error.message ?: error.javaClass.simpleName,
        )
    }

    private fun clearStorageDirectory() {
        if (Files.isSymbolicLink(storageDirectory.toPath())) {
            if (!deleteEntry(storageDirectory)) throw IOException("Unable to replace ${storageDirectory.absolutePath}")
            return
        }
        if (!storageDirectory.isDirectory) {
            if (storageDirectory.exists() && !deleteEntry(storageDirectory)) {
                throw IOException("Unable to replace ${storageDirectory.absolutePath}")
            }
            return
        }
        storageDirectory.listFiles()?.forEach { child ->
            if (!deleteEntry(child)) throw IOException("Unable to clear ${child.absolutePath}")
        }
    }

    private fun deleteEntry(entry: File): Boolean = runCatching {
        if (Files.isSymbolicLink(entry.toPath())) {
            Files.deleteIfExists(entry.toPath())
        } else if (entry.isDirectory) {
            entry.listFiles()?.forEach { child ->
                if (!deleteEntry(child)) return@runCatching false
            }
            entry.delete()
        } else {
            entry.delete()
        }
    }.getOrDefault(false)

    private fun link(target: File, name: String) {
        val link = File(storageDirectory, name)
        Os.symlink(target.absolutePath, link.absolutePath)
    }
}
