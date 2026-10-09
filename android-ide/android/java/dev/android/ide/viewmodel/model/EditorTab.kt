// android-ide/android/java/dev/android/ide/viewmodel/model/EditorTab.kt
//
// State for a single open editor tab.

package dev.android.ide.viewmodel.model

import java.util.UUID

/**
 * [id]             Stable tab identifier — UUID v4.
 * [documentUri]    SAF document URI. For blank (unsaved) tabs: "blank://new/<uuid>".
 * [displayName]    Filename shown in the tab label.
 * [language]       Monaco language ID (e.g. "kotlin", "java", "plaintext").
 * [content]        File content; null while the file is being read from SAF.
 * [isDirty]        True if the editor has unsaved changes.
 * [isActive]       True if this tab is currently visible in the editor.
 * [isSaving]       True while an async write is in flight; used to show a saving indicator.
 * [isBlank]        True for a "new blank" tab that has never been saved to a real file.
 *                  Blank tabs have a synthetic documentUri and do not correspond to a
 *                  SAF document until the user performs "Save As".
 * [isTemporary]    True for a "preview" tab opened by a single-tap in the file tree.
 *                  A temporary tab is replaced by the next single-tapped file instead
 *                  of accumulating in the tab bar. It becomes permanent on: first edit
 *                  (any keystroke), explicit pin action (double-tap file tree item or
 *                  "Pin" from the tab overflow menu), or "Save As".
 *                  Temporary tabs are rendered with an italic display name.
 * [isPinned]       Durable pin. Pinned tabs survive bulk-close operations and
 *                  are persisted with the workspace.
 */
data class EditorTab(
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,
    val displayName: String,
    val language: String,
    val content: String? = null,
    val isDirty: Boolean = false,
    val isActive: Boolean = false,
    val isSaving: Boolean = false,
    val isBlank: Boolean = false,
    val isTemporary: Boolean = false,
    val isPinned: Boolean = false,
    val isImagePreview: Boolean = false,
)
