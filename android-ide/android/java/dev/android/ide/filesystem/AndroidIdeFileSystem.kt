package dev.android.ide.filesystem

import android.content.Context
import dev.android.ide.project.ProjectStorageAdapterImpl
import dev.android.ide.saf.FileManagementService
import dev.android.ide.saf.SafBackedFileSystem
import dev.android.ide.saf.SafRepository

/**
 * Application-scoped filesystem module. Domains obtain provider-backed file,
 * project-storage, and resource-resolution operations from this shared owner;
 * provider URI interpretation stays inside its SAF-backed adapters.
 */
class AndroidIdeFileSystem private constructor(context: Context) {
    private val appContext = context.applicationContext

    val documents: SafRepository = SafRepository(appContext)
    val operations: FileManagementService = FileManagementService(documents)
    val resources: SafBackedFileSystem = SafBackedFileSystem(appContext, documents)
    val projects: ProjectStorageAdapterImpl = ProjectStorageAdapterImpl(documents, operations)

    companion object {
        @Volatile
        private var applicationInstance: AndroidIdeFileSystem? = null

        fun from(context: Context): AndroidIdeFileSystem {
            applicationInstance?.let { return it }
            return synchronized(this) {
                applicationInstance ?: AndroidIdeFileSystem(context.applicationContext).also {
                    applicationInstance = it
                }
            }
        }
    }
}
