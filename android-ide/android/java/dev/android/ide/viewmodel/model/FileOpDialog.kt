// android-ide/android/java/dev/android/ide/viewmodel/model/FileOpDialog.kt
//
// Sealed class representing the currently-active file-operation dialog.
// Stored in IdeUiState.fileOpDialog — null when no dialog is visible.

package dev.android.ide.viewmodel.model

sealed class FileOpDialog {
    /** Shown when a binary document is selected for opening in the text editor. */
    data class BinaryOpenError(val fileName: String, val documentUri: String) : FileOpDialog()

    /** Rename dialog for [node]. [errorMessage] is shown inline when non-null. */
    data class Rename(val node: FileNode, val errorMessage: String? = null, val resultMessage: String? = null, val isSubmitting: Boolean = false) : FileOpDialog()

    /** Delete confirmation dialog for [node] or the snapshotted [selectedNodes]. */
    data class Delete(
        val node: FileNode,
        val selectedNodes: List<FileNode> = emptyList(),
        val errorMessage: String? = null,
        val resultMessage: String? = null,
        val isSubmitting: Boolean = false,
    ) : FileOpDialog()

    /** "Create file" dialog; new file will be created inside [parentNode]. [errorMessage] is shown inline when non-null. */
    data class CreateFile(
        val parentNode: FileNode,
        val errorMessage: String? = null,
        val resultMessage: String? = null,
        val isSubmitting: Boolean = false,
        val createdDocumentUri: String? = null,
    ) : FileOpDialog()

    /** "Create folder" dialog; new folder will be created inside [parentNode]. [errorMessage] is shown inline when non-null. */
    data class CreateFolder(
        val parentNode: FileNode,
        val errorMessage: String? = null,
        val resultMessage: String? = null,
        val isSubmitting: Boolean = false,
    ) : FileOpDialog()

    /**
     * Duplicate dialog for [node].
     * The UI pre-fills the name field with a user-facing "Copy of <displayName>" suggestion.
     * [errorMessage] is shown inline when non-null.
     */
    data class Duplicate(val node: FileNode, val errorMessage: String? = null, val resultMessage: String? = null, val isSubmitting: Boolean = false) : FileOpDialog()

    /** Export confirmation for a file-tree file or folder. */
    data class Export(val node: FileNode, val resultMessage: String? = null, val failed: Boolean = false, val isSubmitting: Boolean = false) : FileOpDialog()

    /**
     * Confirm-close dialog shown when the user tries to close a tab that has
     * unsaved changes. Options: Save and close, Discard and close, Cancel.
     */
    data class UnsavedClose(val tabId: String, val displayName: String) : FileOpDialog()

    /** Preview/confirmation and progress state for a project-wide replacement. */
    data class ReplaceAll(
        val find: String,
        val replacement: String,
        val files: Int,
        val matches: Int,
        val documentUris: List<String> = emptyList(),
        val isSubmitting: Boolean = false,
        val resultMessage: String? = null,
    ) : FileOpDialog()

    /** Preview/confirmation and progress state for replacing matches in one file. */
    data class ReplaceFile(
        val documentUri: String,
        val fileName: String,
        val find: String,
        val replacement: String,
        val matches: Int,
        val isSubmitting: Boolean = false,
        val resultMessage: String? = null,
    ) : FileOpDialog()

    /**
     * for saving the active file's content.  The [suggestedName] pre-fills the
     * text field with the current tab's display name.
     */
    data class SaveAs(
        val suggestedName: String,
        val errorMessage: String? = null,
        val resultMessage: String? = null,
        val isSubmitting: Boolean = false,
    ) : FileOpDialog()

    /** Focused name-only save flow for a new, unsaved editor tab. */
    data class SaveBlank(
        val tabId: String,
        val suggestedName: String = "untitled",
        val errorMessage: String? = null,
        val resultMessage: String? = null,
        val isSubmitting: Boolean = false,
    ) : FileOpDialog()
}
