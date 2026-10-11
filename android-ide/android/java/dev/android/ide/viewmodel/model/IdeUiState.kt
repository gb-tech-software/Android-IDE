// android-ide/android/java/dev/android/ide/viewmodel/model/IdeUiState.kt
//
// Single source of truth for all observable IDE UI state.

package dev.android.ide.viewmodel.model

import dev.android.ide.data.model.EditorSettings
import dev.android.ide.data.model.Project
import dev.android.ide.data.model.ProjectDetails
import dev.android.ide.data.model.RecoveryEntry
import dev.android.ide.data.model.VolumeKeyMode
import dev.android.ide.contracts.LanguageServerFeature

data class EditorDiagnostic(
    val documentUri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    /** LSP diagnostic severity: 1 error, 2 warning, 3 information, 4 hint. */
    val severity: Int,
    val message: String,
    val source: String? = null,
)

data class IdeUiState(
    // ── Navigation ─────────────────────────────────────────────────────────
    /** Which top-level screen is visible. */
    val currentScreen: AppScreen = AppScreen.HOME,
    /** Previous top-level surface used when dismissing a child surface. */
    val previousScreen: AppScreen? = null,

    // ── Project ────────────────────────────────────────────────────────────
    /** Display name shown in the top app bar. */
    val projectName: String = "",

    /** SAF tree URI of the open project root; null if no project is open. */
    val projectRootUri: String? = null,

    /** True only when the provider has confirmed this project is local to the device. */
    val localStorageConfirmed: Boolean = false,

    /** True only when terminal, Git, language tools, and local-server preview have a native path. */
    val localWorkspaceAvailable: Boolean = false,

    /** Short explanation shown when the open project is editable but not a full workspace. */
    val localWorkspaceExplanation: String? = null,

    /** Project-relative path to the HTML file used by Preview/Run for supporting source files. */
    val defaultPreviewPath: String? = null,

    /** Recently-opened project registry, most-recent first. */
    val recentProjects: List<Project> = emptyList(),

    /** Computed metadata for the project-details dialog. */
    val projectDetails: ProjectDetails? = null,

    /** True while project details are being calculated from storage. */
    val projectDetailsLoading: Boolean = false,

    /** Cached metadata used for project-list sorting and detail screens. */
    val projectDetailsByUri: Map<String, ProjectDetails> = emptyMap(),

    /** True while project-list metadata is being refreshed. */
    val projectMetadataLoading: Boolean = false,

    // ── File tree ──────────────────────────────────────────────────────────
    /** Root nodes of the file tree sidebar. */
    val fileTree: List<FileNode> = emptyList(),

    /** True while the file tree is being inspected or refreshed. */
    val fileTreeLoading: Boolean = false,

    /** True while a provider-backed copy, cut, or paste mutation is running. */
    val fileMutationLoading: Boolean = false,

    /** URI requested by the sidebar locate action. */
    val locateTargetUri: String? = null,

    /** Incremented for each locate request, including repeated requests for one file. */
    val locateRequestToken: Long = 0L,

    // ── File search ────────────────────────────────────────────────────────
    /** Whether filename search is visible in the sidebar. */
    val isSearchVisible: Boolean = false,

    /** Whether whole-project content search is visible in the sidebar. */
    val isContentSearchVisible: Boolean = false,

    /** Current filename query. */
    val fileSearchQuery: String = "",

    /** Matching files for the filename query. */
    val fileSearchResults: List<FileSearchResult> = emptyList(),

    /** Whether filename search should include matching directories. */
    val fileSearchIncludeFolders: Boolean = false,

    /** True while the filename search is traversing the project. */
    val fileSearchRunning: Boolean = false,

    /** Current content query scanned across project text files. */
    val contentSearchQuery: String = "",

    /** Files containing the current project-content query. */
    val contentSearchResults: List<FileSearchResult> = emptyList(),

    /** Query value whose scan has completed; null means results are not current. */
    val contentSearchCompletedQuery: String? = null,

    /** True while the current explicit content-search request is scanning. */
    val contentSearchRunning: Boolean = false,

    /** Non-fatal scan issue, such as a directory the provider would not list. */
    val contentSearchWarning: String? = null,

    /** Content-search matching preferences, aligned with Monaco find behavior. */
    val contentSearchMatchCase: Boolean = false,
    val contentSearchWholeWord: Boolean = false,
    val contentSearchRegex: Boolean = false,
    val contentSearchShowContext: Boolean = false,

    /** True while Monaco's find or replace widget is the active editor overlay. */
    val isEditorSearchVisible: Boolean = false,

    // ── Multi-selection ────────────────────────────────────────────────────
    /** Whether multi-select mode is active in the file tree. */
    val isMultiSelectMode: Boolean = false,

    /** Set of document URIs currently selected in multi-select mode. */
    val selectedUris: Set<String> = emptySet(),

    // ── File tree clipboard ─────────────────────────────────────────────────
    /**
     * Files/folders pending a paste operation.
     * Supports both single-item and multi-item clipboard from multi-select.
     * Empty list means clipboard is empty.
     */
    val clipboardItems: List<FileNode> = emptyList(),

    /** True when [clipboardItems] were cut (will be moved); false for copy. */
    val clipboardIsCut: Boolean = false,

    // ── Editor ─────────────────────────────────────────────────────────────
    /** All open editor tabs. */
    val openTabs: List<EditorTab> = emptyList(),

    /** ID of the currently active tab. */
    val activeTabId: String? = null,

    /** Whether Monaco currently has a non-empty text selection. */
    val hasEditorSelection: Boolean = false,

    /** True only when the active file's project language server has initialized. */
    val languageIntelligenceAvailable: Boolean = false,

    /** Protocol features actually advertised by the active file's language server. */
    val languageServerFeatures: Set<LanguageServerFeature> = emptySet(),
    /** Current LSP diagnostics keyed by project document URI; not persisted across sessions. */
    val languageServerDiagnostics: Map<String, List<EditorDiagnostic>> = emptyMap(),
    /** Package setup information is surfaced in settings only while LSP is enabled. */
    val languageServerPackageSetupMessage: String? = null,
    val languageServerPackageSetupCanRetry: Boolean = false,

    /** True once Monaco sends the "ready" message. */
    val isEditorReady: Boolean = false,

    /** True while the active file is being bound into Monaco. */
    val editorFileLoading: Boolean = false,

    // ── Cursor ─────────────────────────────────────────────────────────────
    val cursorLine: Int = 1,
    val cursorColumn: Int = 1,

    // ── Per-tab cursor and scroll position maps ─────────────────────────────
    // Keyed by documentUri. Persisted per-project in .dev-android-ide/workspace.json.
    val tabCursorPositions: Map<String, Pair<Int, Int>> = emptyMap(),
    val tabScrollPositions: Map<String, Int>            = emptyMap(),

    // ── Status ─────────────────────────────────────────────────────────────
    /** Transient status bar message (e.g. "Saved", "Renamed"). */
    val statusMessage: String = "",

    // ── File operation dialog ──────────────────────────────────────────────
    /** Non-null when a file operation dialog is visible. */
    val fileOpDialog: FileOpDialog? = null,

    // ── Editor settings ────────────────────────────────────────────────────
    /** Persisted editor display and behaviour settings. */
    val editorSettings: EditorSettings = EditorSettings(),

    // ── Volume keys ─────────────────────────────────────────────────────────
    /** Controls how hardware volume keys behave when the editor is focused. */
    val volumeKeyMode: VolumeKeyMode = VolumeKeyMode.HORIZONTAL,

    // ── Exit confirmation ───────────────────────────────────────────────────
    /**
     * True when the user pressed Back while at least one tab has unsaved changes.
     * Triggers the "You have unsaved changes. Exit anyway?" dialog.
     */
    val showExitConfirmation: Boolean = false,

    /** Non-null when switching projects requires a dirty-tab decision. */
    val projectSwitchRequest: ProjectSwitchRequest? = null,

    // ── Crash recovery ──────────────────────────────────────────────────────
    /**
     * Non-empty when the previous session did not exit cleanly and there are
     * persisted unsaved content entries to offer for restoration.
     */
    val recoveryEntries: List<RecoveryEntry> = emptyList(),

    /** Incremented when Monaco must bind the active tab again. */
    val editorBindRevision: Long = 0L,
)
