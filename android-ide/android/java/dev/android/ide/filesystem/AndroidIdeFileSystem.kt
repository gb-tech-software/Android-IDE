package dev.android.ide.filesystem

import android.content.Context
import dev.android.ide.saf.FileManagementService
import dev.android.ide.saf.SafBackedFileSystem
import dev.android.ide.saf.SafRepository

/**
 * Application-scoped filesystem primitive module. Domains obtain provider-backed
 * documents, generic mutations, and resource-resolution operations here.
 * Project registration and project-relative workflows are composed above this
 * module, while provider URI interpretation stays inside its SAF adapters.
 */
class AndroidIdeFileSystem private constructor(context: Context) {
    private val appContext = context.applicationContext

    val documents: SafRepository = SafRepository(appContext)
    val operations: FileManagementService = FileManagementService(documents)
    val resources: SafBackedFileSystem = SafBackedFileSystem(appContext, documents)

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
