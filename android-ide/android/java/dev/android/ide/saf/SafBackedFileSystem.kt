package dev.android.ide.saf

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import dev.android.ide.contracts.CapabilityState
import dev.android.ide.contracts.FileSystemLocation
import dev.android.ide.contracts.FileSystemNode
import dev.android.ide.contracts.FileSystemResolution
import dev.android.ide.contracts.FileSystemAdapter
import dev.android.ide.contracts.StorageLocation
import java.io.File

/**
 * First application-wide filesystem boundary.
 *
 * Project and runtime domains exchange the stable virtualPath and retain the
 * provider URI only at this adapter boundary. A localPath is optional: SAF
 * file operations can work without one, while Termux and language servers need
 * it to execute against the selected project rather than a substituted home.
 */
class SafBackedFileSystem(
    context: Context,
    private val saf: SafRepository = SafRepository(context.applicationContext),
) : FileSystemAdapter {
    private val appContext = context.applicationContext

    override suspend fun resolve(location: FileSystemLocation): FileSystemResolution {
        val providerUri = location.resourceUri
        val localPath = saf.localFilesystemPath(providerUri)
        val capabilities = saf.inspectStorage(
            StorageLocation(
                stableId = providerUri,
                displayLabel = location.displayLabel,
                userVisiblePath = providerUri,
            ),
        )
        val readable = capabilities.readable
        val writable = capabilities.writable
        val terminalAccessible = localPath
            ?.let(::File)
            ?.let { it.isDirectory && it.canRead() && it.canWrite() }
            ?: false
        val node = if (readable) {
            FileSystemNode(
                virtualPath = virtualPath(providerUri),
                displayLabel = location.displayLabel,
                isDirectory = true,
            )
        } else {
            null
        }
        return FileSystemResolution(
            node = node,
            readable = readable,
            writable = writable,
            terminalAccessible = terminalAccessible,
            terminalPath = localPath.takeIf { terminalAccessible },
            explanation = when {
                capabilities.state == CapabilityState.PERMISSION_LOST ->
                    capabilities.explanation ?: "Permission to the selected filesystem location was lost"
                !readable -> capabilities.explanation ?: "The selected filesystem location cannot be read"
                !terminalAccessible -> capabilities.localWorkspaceExplanation
                    ?: "This project is on a storage path that Terminal cannot access. Move it to Android IDE storage or Android external storage for full access."
                !writable -> capabilities.explanation ?: "The selected filesystem location cannot be written"
                else -> null
            },
        )
    }

    override suspend fun mountedRoots(): List<FileSystemNode> = buildList {
        val androidIdeRoot = AndroidIdeDocumentsProvider.rootTreeUri()
        if (saf.documentPresence(androidIdeRoot) == DocumentPresence.EXISTS && saf.isDirectoryDocument(androidIdeRoot)) {
            add(
                FileSystemNode(
                    virtualPath = virtualPath(androidIdeRoot),
                    displayLabel = "Android IDE",
                    isDirectory = true,
                ),
            )
        }
        val hasLegacyStorageAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        if (hasAllFilesAccess || hasLegacyStorageAccess) {
            val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appContext.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
                    volume.directory?.let { directory -> directory to volume.isRemovable }
                }
            } else {
                val candidates = mutableListOf(Environment.getExternalStorageDirectory() to false)
                appContext.getExternalFilesDirs(null).filterNotNull().forEach { appSpecific ->
                    val volumeRoot = appSpecific.absolutePath.substringBefore("/Android/data/")
                    if (volumeRoot != appSpecific.absolutePath) candidates += File(volumeRoot) to true
                }
                candidates
            }
            volumes.distinctBy { it.first.canonicalPath }.forEachIndexed { index, (directory, removable) ->
                if (directory.isDirectory && directory.canRead()) {
                    add(
                        FileSystemNode(
                            virtualPath = virtualPath(Uri.fromFile(directory).toString()),
                            displayLabel = when {
                                removable -> "SD card"
                                index == 0 -> "Shared storage"
                                else -> "External storage ${index + 1}"
                            },
                            isDirectory = true,
                        ),
                    )
                }
            }
        }
        appContext.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .filterNot { it == androidIdeRoot }
            .distinct()
            .forEach { grantedUri ->
                if (saf.documentPresence(grantedUri) != DocumentPresence.EXISTS || !saf.isDirectoryDocument(grantedUri)) return@forEach
                val label = saf.getDisplayName(grantedUri) ?: "Mounted storage"
                add(
                    FileSystemNode(
                        virtualPath = virtualPath(grantedUri),
                        displayLabel = label,
                        isDirectory = true,
                    ),
                )
            }
    }

    override suspend fun children(parent: FileSystemNode): List<FileSystemNode> {
        val parentUri = providerUri(parent.virtualPath) ?: return emptyList()
        return saf.listChildren(parentUri).map { child ->
            FileSystemNode(
                virtualPath = virtualPath(child.documentUri),
                displayLabel = child.displayName,
                isDirectory = child.isDirectory,
                parentVirtualPath = parent.virtualPath,
            )
        }
    }

    private fun providerUri(virtualPath: String): String? = runCatching {
        val uri = Uri.parse(virtualPath)
        if (uri.scheme != "androidide" || uri.host != "filesystem") return null
        Uri.decode(uri.encodedPath?.removePrefix("/") ?: return null)
    }.getOrNull()

    private fun virtualPath(providerUri: String): String =
        "androidide://filesystem/${Uri.encode(providerUri)}"
}
