// android-ide/android/java/dev/android/ide/data/model/EditorSettings.kt
//
// Editor display and behaviour settings, persisted by EditorSettingsRepository.

package dev.android.ide.data.model

data class EditorSettings(
    // ── Monaco editor ───────────────────────────────────────────────────────
    val fontSize: Int          = 14,
    val tabSize: Int           = 4,
    val wordWrap: Boolean      = false,
    val lineNumbers: Boolean   = true,
    val autoSave: Boolean      = false,
    /** Monaco theme: "dark" | "light" | "application" | "device". */
    val editorTheme: String    = "application",
    /**
     * "none"      — whitespace never highlighted
     * "selection" — whitespace highlighted in selected text (default)
     * "all"       — all whitespace always highlighted
     * "boundary"  — whitespace at block boundaries
     */
    val renderWhitespace: String = "selection",
    /** Show/hide the minimap (code overview) on the right side of the editor. */
    val minimapEnabled: Boolean           = true,
    /** Allow scrolling past the last line so it can sit in the centre of the view. */
    val scrollBeyondLastLine: Boolean     = false,
    /**
     * Cursor render shape.
     * "line"       — thin vertical bar (default)
     * "block"      — filled block that sits over the next character
     * "underline"  — horizontal underline below the current character
     */
    val cursorStyle: String               = "line",
    /** Enable bracket-pair colourisation — one colour per nesting level. */
    val bracketPairColorization: Boolean  = true,
    /**
     * Auto-close brackets.
     * "always"           — always insert the matching bracket
     * "languageDefined"  — language grammar decides (default)
     * "never"            — no auto-closing
     */
    val autoClosingBrackets: String       = "languageDefined",

    // ── Keyboard ────────────────────────────────────────────────────────────
    /** Show the keyboard accessory toolbar above the system keyboard. */
    val showKeyboardToolbar: Boolean = true,
    /** Show the symbol shortcut bar above the keyboard toolbar. */
    val showSymbolBar: Boolean       = true,
    /** Enabled keyboard-toolbar action IDs in user-defined order; pages are derived in groups of five. */
    val keyboardToolbarOrder: List<String> = DEFAULT_KEYBOARD_TOOLBAR_ORDER,
    /** Show the optional document-information row below the editor. */
    val showStatusBar: Boolean       = true,
    // ── File tree ───────────────────────────────────────────────────────────
    /** Hide the root .git folder in the file tree sidebar. */
    val hideGitFolder: Boolean       = true,
    /** Hide the project-local .dev-android-ide metadata folder in the file tree sidebar. */
    val hideProjectMetadataFolder: Boolean = true,

    // ── UI font scaling ─────────────────────────────────────────────────────
    /**
     * Multiplier applied to the entire application's text scale via
     * LocalDensity.fontScale. Affects the file tree, menus, settings,
     * dialogs, and all other UI chrome — not just the Monaco editor.
     *
     * Range: 0.75 – 1.50. Default: 1.0 (system default).
     */
    val uiFontScale: Float = 1.0f,

) {
    companion object {
        val DEFAULT_SYMBOLS = listOf("()", "{}", "[]", "\"\"", "''", "``", "<>")
        /**
         * Practical mobile defaults. Find and close-search remain in the editor
         * top bar and are intentionally not duplicated in this list.
         */
        val DEFAULT_KEYBOARD_TOOLBAR_ORDER = listOf(
            "indent", "outdent", "cursorLeft", "cursorRight", "cursorUp", "cursorDown", "cursorHome", "cursorEnd",
            "undo", "redo", "cut", "copy", "paste", "selectAll", "selectWord", "selectLine",
            "formatDocument", "commentLine", "fold", "unfold", "triggerSuggest", "quickFix", "toggleKeyboard",
        )
        const val UI_FONT_SCALE_MIN = 0.75f
        const val UI_FONT_SCALE_MAX = 1.50f
        const val UI_FONT_SCALE_STEP = 0.05f
    }
}
