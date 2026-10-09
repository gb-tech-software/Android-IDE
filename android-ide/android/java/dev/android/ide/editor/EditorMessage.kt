// android-ide/android/java/dev/android/ide/editor/EditorMessage.kt
//
// Message protocol between Monaco (JS) and Kotlin.
//
// Inbound  (Monaco → Kotlin, via AndroidBridge.onMessage):
//   { type: "ready" }
//   { type: "contentChanged", path, content }
//   { type: "cursorMoved",    line, column }
//   { type: "fileSaved",      path }
//   { type: "selectionChanged", hasSelection }
//   { type: "languageServerMessage", message }
//
// Outbound (Kotlin → Monaco, via window.androidIDE.receiveMessage):
//   { type: "loadFile",         path, content, language }
//   { type: "setTheme",         theme }
//   { type: "setFontSize",      size }
//   { type: "requestSave",      path }
//   { type: "closeTab",         path }
//   { type: "forceLayout" }
//   { type: "executeCommand",   command }
//   { type: "insertText",       text }
//   { type: "setEditorOptions", tabSize?, wordWrap?, lineNumbers?, fontSize? }
//   { type: "showFind" }
//   { type: "showReplace" }

package dev.android.ide.editor

import org.json.JSONObject

// ── Inbound (Monaco → Kotlin) ─────────────────────────────────────────────────

sealed class EditorInbound {
    /** Monaco has finished initialising and is ready to receive loadFile messages. */
    object Ready : EditorInbound()

    /** User edited the file; debounced 300 ms by monaco-init.js. */
    data class ContentChanged(val path: String, val content: String) : EditorInbound()

    /** Cursor moved; emitted on every cursor position change. */
    data class CursorMoved(val line: Int, val column: Int) : EditorInbound()

    /** Monaco selection changed; used to enable or disable clipboard actions. */
    data class SelectionChanged(val hasSelection: Boolean) : EditorInbound()

    /** User pressed Ctrl+S / Cmd+S in Monaco. */
    data class FileSaved(val path: String) : EditorInbound()

    /** A JSON-RPC request or notification from Monaco to the active server. */
    data class LanguageServerMessage(val message: String) : EditorInbound()

    /**
     * [text] on the Android clipboard.  [isCut] is true when Monaco has already
     * deleted the selection (cut); false for a plain copy.
     */
    data class TextCopied(val text: String, val isCut: Boolean) : EditorInbound()

    /**
     * Debounced scroll position report from Monaco.
     * [scrollTop] is the pixel offset from the top of the editor content.
     * Used to persist and restore scroll position per tab across sessions.
     */
    data class ScrollPositionReport(val scrollTop: Int) : EditorInbound()

    companion object {
        /**
         * Parse a JSON string from [AndroidBridge.onMessage] into an [EditorInbound].
         * Returns null for unknown message types or malformed JSON.
         */
        fun fromJson(json: String): EditorInbound? = runCatching {
            val obj = JSONObject(json)
            when (obj.getString("type")) {
                "ready"                -> Ready
                "contentChanged"       -> ContentChanged(
                    path    = obj.getString("path"),
                    content = obj.getString("content"),
                )
                "cursorMoved"          -> CursorMoved(
                    line   = obj.getInt("line"),
                    column = obj.getInt("column"),
                )
                "selectionChanged"    -> SelectionChanged(
                    hasSelection = obj.optBoolean("hasSelection", false),
                )
                "fileSaved"            -> FileSaved(path = obj.getString("path"))
                "languageServerMessage" -> LanguageServerMessage(message = obj.getString("message"))
                "textCopied"           -> TextCopied(
                    text  = obj.getString("text"),
                    isCut = obj.optBoolean("isCut", false),
                )
                "scrollPositionReport" -> ScrollPositionReport(
                    scrollTop = obj.getInt("scrollTop"),
                )
                else                   -> null
            }
        }.getOrNull()
    }
}

// ── Outbound (Kotlin → Monaco) ────────────────────────────────────────────────

sealed class EditorOutbound {

    // ── File management ────────────────────────────────────────────────────

    /** Load a file into the editor. Creates or reuses a Monaco model for [path]. */
    data class LoadFile(val path: String, val content: String, val language: String) : EditorOutbound()

    /** Replace a clean cached model with bytes freshly read from storage. */
    data class ReloadFile(
        val path: String,
        val expectedCurrentContent: String?,
        val content: String,
        val language: String,
    ) : EditorOutbound()

    /** Clear the visible model while keeping cached models for other tabs. */
    object ClearActiveModel : EditorOutbound()

    /** Unload the model for [path] from Monaco. */
    data class CloseTab(val path: String) : EditorOutbound()

    /** Ask Monaco to emit a fileSaved message for the current file. */
    data class RequestSave(val path: String) : EditorOutbound()

    // ── Appearance ─────────────────────────────────────────────────────────

    /** Switch Monaco theme. Pass "dark" or "light"; monaco-init.js maps to named themes. */
    data class SetTheme(val theme: String) : EditorOutbound()

    /** Update editor font size (sp). */
    data class SetFontSize(val size: Int) : EditorOutbound()

    /**
     * Update one or more editor options. Null fields are not sent (no change).
     * Sent whenever the user changes editor settings; also on first editor ready
     * to sync the persisted settings into Monaco.
     */
    data class SetEditorOptions(
        val tabSize: Int?           = null,
        val wordWrap: Boolean?      = null,
        val lineNumbers: Boolean?   = null,
        val fontSize: Int?          = null,
        val renderWhitespace: String? = null,
        /** Show/hide the code minimap on the right side of the editor. */
        val minimapEnabled: Boolean? = null,
        /** Allow scrolling past the last line of the file. */
        val scrollBeyondLastLine: Boolean? = null,
        /** Cursor render shape: "line" | "block" | "underline" */
        val cursorStyle: String? = null,
        /** Enable bracket-pair colourisation (one colour per nesting level). */
        val bracketPairColorization: Boolean? = null,
        /** Auto-close brackets: "always" | "languageDefined" | "never" */
        val autoClosingBrackets: String? = null,
    ) : EditorOutbound()

    // ── Layout ─────────────────────────────────────────────────────────────

    /**
     * Force Monaco to re-layout with its container's current pixel dimensions.
     * Sent once after the editor sends "ready", at which point the WebView is
     * in the Compose tree and has its final measured size.
     */
    object ForceLayout : EditorOutbound()

    // ── Commands ───────────────────────────────────────────────────────────

    /**
     * Trigger a Monaco built-in command or action by ID.
     *
     * Monaco action IDs (use editor.getAction(id).run()):
     *   editor.action.indentLines, editor.action.outdentLines,
     *   editor.action.selectAll, actions.find,
     *   editor.action.startFindReplaceAction
     *
     * Monaco keyboard handler IDs (use editor.trigger('keyboard', id, null)):
     *   cursorLeft, cursorRight, cursorUp, cursorDown,
     *   cursorHome, cursorEnd, cursorPageUp, cursorPageDown,
     *   cursorLeftSelect, cursorRightSelect, cursorUpSelect, cursorDownSelect,
     *   cursorWordLeftSelect, cursorWordRightSelect, cursorHomeSelect, cursorEndSelect,
     *   undo, redo, deleteLeft, deleteRight,
     *   editor.action.clipboardCutAction, editor.action.clipboardCopyAction
     *
     * monaco-init.js tries getAction() first, then trigger() as a fallback.
     */
    data class ExecuteCommand(val command: String) : EditorOutbound()

    /**
     * Insert a literal string at the cursor position.
     * Used by the keyboard toolbar for bracket/punctuation buttons.
     */
    data class InsertText(val text: String) : EditorOutbound()

    /** Insert a matching delimiter pair, placing the cursor between the delimiters. */
    data class InsertPair(val opening: String, val closing: String) : EditorOutbound()

    /** Insert a Monaco snippet; replace a matching [trigger] token immediately before the cursor. */
    data class InsertSnippet(val trigger: String, val snippet: String) : EditorOutbound()

    /** Show Monaco's built-in find widget. */
    object ShowFind : EditorOutbound()

    /** Show Monaco's built-in find + replace widget. */
    object ShowReplace : EditorOutbound()

    /** Close Monaco's find or replace widget and return focus to the editor. */
    object CloseSearch : EditorOutbound()

    /**
     * Restore the cursor to [line] / [column] in the active model.
     * Sent after LoadFile to re-establish the saved cursor position.
     */
    data class SetCursorPosition(val line: Int, val column: Int) : EditorOutbound()

    /** Select and reveal a content-search match in the active model. */
    data class SelectMatch(
        val line: Int = 1,
        val column: Int = 1,
        val length: Int = 0,
    ) : EditorOutbound()

    /**
     * Restore the vertical scroll offset to [scrollTop] pixels.
     * Sent after LoadFile (and after SetCursorPosition) to re-establish the
     * saved scroll position. Monaco's revealCursor may change the scroll, so
     * this must be sent last to win.
     */
    data class SetScrollPosition(val scrollTop: Int) : EditorOutbound()

    /** Deliver one framed JSON-RPC message from a native language server to Monaco. */
    data class LanguageServerMessage(
        val projectId: String,
        val serverId: String,
        val message: String,
    ) : EditorOutbound()

    /**
     * Sent at the start of [openProjectInternal] before tabs are cleared, so stale
     * models from project A cannot leak into project B (would cause wrong content
     * or corrupt undo history when the same filename exists in both projects).
     */
    object CloseAllModels : EditorOutbound()

    // ── Serialisation ──────────────────────────────────────────────────────

    /**
     * Serialise this message to a JavaScript expression that calls
     * [window.androidIDE.receiveMessage] with the payload object.
     *
     * The caller passes the result to [android.webkit.WebView.evaluateJavascript].
     */
    fun toJs(): String {
        val payload = toJsonObject().toString()
        return "window.androidIDE && window.androidIDE.receiveMessage(JSON.parse(${escapeJs(payload)}))"
    }

    private fun toJsonObject(): JSONObject = JSONObject().apply {
        when (val msg = this@EditorOutbound) {
            is LoadFile        -> { put("type", "loadFile");    put("path", msg.path); put("content", msg.content); put("language", msg.language) }
            is ReloadFile      -> {
                put("type", "reloadFile")
                put("path", msg.path)
                msg.expectedCurrentContent?.let { put("expectedCurrentContent", it) }
                put("content", msg.content)
                put("language", msg.language)
            }
            is ClearActiveModel -> put("type", "clearActiveModel")
            is CloseTab        -> { put("type", "closeTab");    put("path", msg.path) }
            is RequestSave     -> { put("type", "requestSave"); put("path", msg.path) }
            is SetTheme        -> { put("type", "setTheme");    put("theme", msg.theme) }
            is SetFontSize     -> { put("type", "setFontSize"); put("size", msg.size) }
            is SetEditorOptions-> {
                put("type", "setEditorOptions")
                msg.tabSize?.let          { put("tabSize", it) }
                msg.wordWrap?.let         { put("wordWrap", if (it) "on" else "off") }
                msg.lineNumbers?.let      { put("lineNumbers", if (it) "on" else "off") }
                msg.fontSize?.let         { put("fontSize", it) }
                msg.renderWhitespace?.let         { put("renderWhitespace", it) }
                msg.minimapEnabled?.let           { put("minimapEnabled", it) }
                msg.scrollBeyondLastLine?.let     { put("scrollBeyondLastLine", it) }
                msg.cursorStyle?.let              { put("cursorStyle", it) }
                msg.bracketPairColorization?.let  { put("bracketPairColorization", it) }
                msg.autoClosingBrackets?.let      { put("autoClosingBrackets", it) }
            }
            is ForceLayout     -> put("type", "forceLayout")
            is ExecuteCommand  -> { put("type", "executeCommand"); put("command", msg.command) }
            is InsertText      -> { put("type", "insertText");     put("text", msg.text) }
            is InsertPair      -> { put("type", "insertPair"); put("opening", msg.opening); put("closing", msg.closing) }
            is InsertSnippet   -> { put("type", "insertSnippet"); put("trigger", msg.trigger); put("snippet", msg.snippet) }
            is ShowFind           -> put("type", "showFind")
            is ShowReplace        -> put("type", "showReplace")
            is CloseSearch        -> put("type", "closeSearch")
            is SetCursorPosition  -> { put("type", "setCursorPosition"); put("line", msg.line); put("column", msg.column) }
            is SelectMatch        -> { put("type", "selectMatch"); put("line", msg.line); put("column", msg.column); put("length", msg.length) }
            is SetScrollPosition  -> { put("type", "setScrollPosition"); put("scrollTop", msg.scrollTop) }
            is LanguageServerMessage -> {
                put("type", "languageServerMessage")
                put("projectId", msg.projectId)
                put("serverId", msg.serverId)
                put("message", msg.message)
            }
            is CloseAllModels     -> put("type", "closeAllModels")
        }
    }

    private fun escapeJs(json: String): String {
        // Wrap in a JS single-quoted string literal, escaping characters that
        // would break the evaluateJavascript call.  JSON already escapes " and \
        // so double-backslash must come first to avoid double-escaping.
        return "'${json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")}'"
    }
}
