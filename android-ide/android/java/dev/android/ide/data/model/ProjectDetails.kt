// android-ide/android/java/dev/android/ide/data/model/ProjectDetails.kt
//
// Computed project metadata shown by the project-management screen.
// Statistics are calculated from the current storage provider rather than
// persisted so details remain accurate after external file changes.

package dev.android.ide.data.model

import dev.android.ide.contracts.StorageCapabilities

data class ProjectDetails(
    val project: Project,
    val description: String,
    val creationTimeMs: Long?,
    val lastModifiedTimeMs: Long?,
    val storageProvider: String,
    val storagePath: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val storageCapabilities: StorageCapabilities,
    val languageBytes: Map<String, Long> = emptyMap(),
    val git: GitDetails? = null,
)

data class GitRemote(
    val name: String,
    val url: String,
)

data class GitDetails(
    val currentBranch: String?,
    val branches: List<String>,
    val remotes: List<GitRemote>,
    val headCommit: String?,
    val latestCommitMessage: String?,
    val latestCommitAuthor: String?,
)
