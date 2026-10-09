package dev.android.ide.ui.components

import dev.android.ide.contracts.LanguageServerFeature

/** One mobile toolbar shortcut. Commands are resolved by this stable action ID. */
data class EditorToolbarAction(
    val id: String,
    val label: String,
    val commandId: String? = id,
    val requiresSelection: Boolean = false,
    val isPaste: Boolean = false,
    val isKeyboardToggle: Boolean = false,
    val repeatable: Boolean = false,
    val requiredServerFeature: LanguageServerFeature? = null,
    val shortcut: String? = null,
    val description: String,
)

private fun toolbarAction(
    id: String,
    label: String,
    commandId: String? = id,
    requiresSelection: Boolean = false,
    isPaste: Boolean = false,
    isKeyboardToggle: Boolean = false,
    repeatable: Boolean = false,
    requiredServerFeature: LanguageServerFeature? = null,
    shortcut: String? = null,
    description: String = toolbarActionDescription(id),
) = EditorToolbarAction(
    id = id,
    label = label,
    commandId = commandId,
    requiresSelection = requiresSelection,
    isPaste = isPaste,
    isKeyboardToggle = isKeyboardToggle,
    repeatable = repeatable,
    requiredServerFeature = requiredServerFeature,
    shortcut = shortcut,
    description = description,
)

private fun toolbarActionDescription(id: String): String = when (id) {
    "cursorLeft" -> "Move the insertion point one character to the left."
    "cursorRight" -> "Move the insertion point one character to the right."
    "cursorUp" -> "Move the insertion point to the previous line."
    "cursorDown" -> "Move the insertion point to the next line."
    "cursorHome" -> "Move the insertion point to the start of the line."
    "cursorEnd" -> "Move the insertion point to the end of the line."
    "cursorWordLeft" -> "Move the insertion point to the previous word."
    "cursorWordRight" -> "Move the insertion point to the next word."
    "cursorPageUp" -> "Move the insertion point one page up."
    "cursorPageDown" -> "Move the insertion point one page down."
    "cursorDocumentStart" -> "Move the insertion point to the start of the document."
    "cursorDocumentEnd" -> "Move the insertion point to the end of the document."
    "indent" -> "Indent the current line or selected lines."
    "outdent" -> "Reduce indentation on the current line or selected lines."
    "undo" -> "Revert the most recent editor change."
    "redo" -> "Reapply the most recently undone change."
    "deleteLeft" -> "Delete the character before the insertion point."
    "deleteRight" -> "Delete the character after the insertion point."
    "deleteWordLeft" -> "Delete the previous word."
    "deleteWordRight" -> "Delete the next word."
    "insertLineAfter" -> "Create a new line below the current line."
    "insertLineBefore" -> "Create a new line above the current line."
    "cut" -> "Copy selected text to the clipboard and remove it."
    "copy" -> "Copy selected text to the system clipboard."
    "paste" -> "Insert text from the system clipboard."
    "selectAll" -> "Select all text in the current document."
    "selectLine" -> "Select the current line."
    "selectWord" -> "Select the word at the insertion point."
    "selectLeft" -> "Extend the selection one character to the left."
    "selectRight" -> "Extend the selection one character to the right."
    "selectUp" -> "Extend the selection to the previous line."
    "selectDown" -> "Extend the selection to the next line."
    "selectWordLeft" -> "Extend the selection to the previous word."
    "selectWordRight" -> "Extend the selection to the next word."
    "selectToStart" -> "Extend the selection to the start of the line."
    "selectToEnd" -> "Extend the selection to the end of the line."
    "expandSelection" -> "Expand the current selection to a larger syntax range."
    "shrinkSelection" -> "Shrink the selection to a smaller syntax range."
    "duplicateSelection" -> "Duplicate the selected text."
    "addCursorAbove" -> "Add another insertion point on the line above."
    "addCursorBelow" -> "Add another insertion point on the line below."
    "addCursorAtLineEnds" -> "Add an insertion point at each selected line end."
    "addNextOccurrence" -> "Add the next matching occurrence to the selection."
    "addPreviousOccurrence" -> "Add the previous matching occurrence to the selection."
    "selectAllOccurrences" -> "Select every matching occurrence in the document."
    "formatDocument" -> "Format the entire document using the active formatter."
    "formatSelection" -> "Format the selected text using the active formatter."
    "commentLine" -> "Toggle a line comment on the current line."
    "commentSelection" -> "Toggle comments on the selected lines."
    "moveLineUp" -> "Move the current line or selected lines upward."
    "moveLineDown" -> "Move the current line or selected lines downward."
    "joinLines" -> "Join the current line with the following line."
    "sortLinesAscending" -> "Sort selected lines in ascending order."
    "sortLinesDescending" -> "Sort selected lines in descending order."
    "fold" -> "Collapse the code region at the insertion point."
    "unfold" -> "Expand the code region at the insertion point."
    "foldAll" -> "Collapse all foldable code regions."
    "unfoldAll" -> "Expand all collapsed code regions."
    "foldLevel1" -> "Collapse code regions to the first nesting level."
    "foldLevel2" -> "Collapse code regions to the second nesting level."
    "goToDefinition" -> "Navigate to the symbol definition using language intelligence."
    "goToDeclaration" -> "Navigate to the symbol declaration using language intelligence."
    "goToTypeDefinition" -> "Navigate to the symbol's type definition."
    "goToImplementation" -> "Navigate to the symbol's implementation."
    "goBack" -> "Return to the previous editor navigation location."
    "goForward" -> "Move forward to the next editor navigation location."
    "triggerSuggest" -> "Request code completions from the editor or language server."
    "triggerParameterHints" -> "Show the active function's parameter information."
    "quickFix" -> "Request a quick fix for the code at the insertion point."
    "renameSymbol" -> "Rename the symbol across language-server-known references."
    "findReferences" -> "Find references to the symbol at the insertion point."
    "codeAction" -> "Request context-aware actions for the current code."
    "organizeImports" -> "Sort, group, or remove imports using language intelligence."
    "toggleKeyboard" -> "Show or hide the on-screen keyboard."
    "toggleWordWrap" -> "Toggle wrapping for long editor lines."
    "zoomIn" -> "Increase the editor's text size."
    "zoomOut" -> "Decrease the editor's text size."
    else -> "Run this editor action."
}

const val KEYBOARD_TOOLBAR_PAGE_SIZE = 5

/**
 * The complete set of actions intentionally exposed by the mobile toolbar.
 * Search is omitted because Find/Replace already live in the editor top bar.
 * Every command below is a Monaco action or keyboard-handler ID; this catalog
 * does not invent application-side editing commands or derive commands by position.
 */
val EDITOR_TOOLBAR_ACTIONS: List<EditorToolbarAction> = listOf(
    // Navigation and cursor movement.
    toolbarAction("cursorLeft", "Move cursor left", shortcut = "←"),
    toolbarAction("cursorRight", "Move cursor right", shortcut = "→"),
    toolbarAction("cursorUp", "Move cursor up", shortcut = "↑"),
    toolbarAction("cursorDown", "Move cursor down", shortcut = "↓"),
    toolbarAction("cursorHome", "Move to line start"),
    toolbarAction("cursorEnd", "Move to line end"),
    toolbarAction("cursorWordLeft", "Move one word left"),
    toolbarAction("cursorWordRight", "Move one word right"),
    toolbarAction("cursorPageUp", "Move page up"),
    toolbarAction("cursorPageDown", "Move page down"),
    toolbarAction("cursorDocumentStart", "Move to document start", commandId = "cursorTop"),
    toolbarAction("cursorDocumentEnd", "Move to document end", commandId = "cursorBottom"),

    // Indentation and common editing.
    toolbarAction("indent", "Indent", commandId = "smartIndent", shortcut = "Tab"),
    toolbarAction("outdent", "Outdent", commandId = "smartOutdent", shortcut = "Shift+Tab"),
    toolbarAction("undo", "Undo", shortcut = "Ctrl+Z"),
    toolbarAction("redo", "Redo", shortcut = "Ctrl+Y"),
    toolbarAction("deleteLeft", "Delete backward"),
    toolbarAction("deleteRight", "Delete forward"),
    toolbarAction("deleteWordLeft", "Delete previous word"),
    toolbarAction("deleteWordRight", "Delete next word"),
    toolbarAction("insertLineAfter", "Insert line after", commandId = "editor.action.insertLineAfter"),
    toolbarAction("insertLineBefore", "Insert line before", commandId = "editor.action.insertLineBefore"),

    // Clipboard and selection.
    toolbarAction("cut", "Cut", commandId = "requestCut", requiresSelection = true, shortcut = "Ctrl+X"),
    toolbarAction("copy", "Copy", commandId = "requestCopy", requiresSelection = true, shortcut = "Ctrl+C"),
    toolbarAction("paste", "Paste", commandId = null, isPaste = true, shortcut = "Ctrl+V"),
    toolbarAction("selectAll", "Select all", commandId = "editor.action.selectAll", shortcut = "Ctrl+A"),
    toolbarAction("selectLine", "Select line", commandId = "cursorLineSelect"),
    toolbarAction("selectWord", "Select word", commandId = "cursorWordSelect"),
    toolbarAction("selectLeft", "Extend selection left", commandId = "cursorLeftSelect", repeatable = true),
    toolbarAction("selectRight", "Extend selection right", commandId = "cursorRightSelect", repeatable = true),
    toolbarAction("selectUp", "Extend selection up", commandId = "cursorUpSelect", repeatable = true),
    toolbarAction("selectDown", "Extend selection down", commandId = "cursorDownSelect", repeatable = true),
    toolbarAction("selectWordLeft", "Select previous word", commandId = "cursorWordLeftSelect", repeatable = true),
    toolbarAction("selectWordRight", "Select next word", commandId = "cursorWordRightSelect", repeatable = true),
    toolbarAction("selectToStart", "Select to line start", commandId = "cursorHomeSelect", repeatable = true),
    toolbarAction("selectToEnd", "Select to line end", commandId = "cursorEndSelect", repeatable = true),
    toolbarAction("expandSelection", "Expand selection", commandId = "editor.action.smartSelect.expand"),
    toolbarAction("shrinkSelection", "Shrink selection", commandId = "editor.action.smartSelect.shrink"),
    toolbarAction("duplicateSelection", "Duplicate selection", commandId = "editor.action.duplicateSelection"),
    toolbarAction("addCursorAbove", "Add cursor above", commandId = "editor.action.insertCursorAbove"),
    toolbarAction("addCursorBelow", "Add cursor below", commandId = "editor.action.insertCursorBelow"),
    toolbarAction("addCursorAtLineEnds", "Add cursors to line ends", commandId = "editor.action.insertCursorAtEndOfEachLineSelected"),
    toolbarAction("addNextOccurrence", "Select next occurrence", commandId = "editor.action.addSelectionToNextFindMatch"),
    toolbarAction("addPreviousOccurrence", "Select previous occurrence", commandId = "editor.action.addSelectionToPreviousFindMatch"),
    toolbarAction("selectAllOccurrences", "Select all occurrences", commandId = "editor.action.selectHighlights"),

    // Formatting, comments, and line structure.
    toolbarAction("formatDocument", "Format document", commandId = "editor.action.formatDocument", requiredServerFeature = LanguageServerFeature.DOCUMENT_FORMATTING, shortcut = "Alt+Shift+F"),
    toolbarAction("formatSelection", "Format selection", commandId = "editor.action.formatSelection", requiresSelection = true, requiredServerFeature = LanguageServerFeature.RANGE_FORMATTING),
    toolbarAction("commentLine", "Comment or uncomment line", commandId = "editor.action.commentLine", shortcut = "Ctrl+/"),
    toolbarAction("commentSelection", "Comment or uncomment selection", commandId = "editor.action.commentLine", requiresSelection = true),
    toolbarAction("moveLineUp", "Move line up", commandId = "editor.action.moveLinesUpAction"),
    toolbarAction("moveLineDown", "Move line down", commandId = "editor.action.moveLinesDownAction"),
    toolbarAction("joinLines", "Join lines", commandId = "editor.action.joinLines"),
    toolbarAction("sortLinesAscending", "Sort lines ascending", commandId = "editor.action.sortLinesAscending"),
    toolbarAction("sortLinesDescending", "Sort lines descending", commandId = "editor.action.sortLinesDescending"),

    // Folding and navigation.
    toolbarAction("fold", "Fold", commandId = "editor.action.fold"),
    toolbarAction("unfold", "Unfold", commandId = "editor.action.unfold"),
    toolbarAction("foldAll", "Fold all", commandId = "editor.action.foldAll"),
    toolbarAction("unfoldAll", "Unfold all", commandId = "editor.action.unfoldAll"),
    toolbarAction("foldLevel1", "Fold to level 1", commandId = "editor.action.foldLevel1"),
    toolbarAction("foldLevel2", "Fold to level 2", commandId = "editor.action.foldLevel2"),
    toolbarAction("goToDefinition", "Go to definition", commandId = "editor.action.revealDefinition", requiredServerFeature = LanguageServerFeature.DEFINITION, shortcut = "F12"),
    toolbarAction("goToDeclaration", "Go to declaration", commandId = "editor.action.revealDeclaration", requiredServerFeature = LanguageServerFeature.DECLARATION),
    toolbarAction("goToTypeDefinition", "Go to type definition", commandId = "editor.action.revealTypeDefinition", requiredServerFeature = LanguageServerFeature.TYPE_DEFINITION),
    toolbarAction("goToImplementation", "Go to implementation", commandId = "editor.action.goToImplementation", requiredServerFeature = LanguageServerFeature.IMPLEMENTATION),
    toolbarAction("goBack", "Navigate back", commandId = "editor.action.navigateBack"),
    toolbarAction("goForward", "Navigate forward", commandId = "editor.action.navigateForward"),

    // Language-server-backed intelligence. Monaco invokes these actions when available.
    toolbarAction("triggerSuggest", "Show suggestions", commandId = "editor.action.triggerSuggest", requiredServerFeature = LanguageServerFeature.COMPLETION, shortcut = "Ctrl+Space"),
    toolbarAction("triggerParameterHints", "Show parameter hints", commandId = "editor.action.triggerParameterHints", requiredServerFeature = LanguageServerFeature.SIGNATURE_HELP),
    toolbarAction("quickFix", "Quick fix", commandId = "editor.action.quickFix", requiredServerFeature = LanguageServerFeature.CODE_ACTION),
    toolbarAction("renameSymbol", "Rename symbol", commandId = "editor.action.rename", requiredServerFeature = LanguageServerFeature.RENAME, shortcut = "F2"),
    toolbarAction("findReferences", "Find references", commandId = "editor.action.referenceSearch.trigger", requiredServerFeature = LanguageServerFeature.REFERENCES),
    toolbarAction("codeAction", "Code actions", commandId = "editor.action.quickFix", requiredServerFeature = LanguageServerFeature.CODE_ACTION),
    toolbarAction("organizeImports", "Organize imports", commandId = "editor.action.organizeImports", requiredServerFeature = LanguageServerFeature.CODE_ACTION),

    // View, save, and input controls.
    toolbarAction("toggleKeyboard", "Show or hide keyboard", commandId = null, isKeyboardToggle = true),
    toolbarAction("toggleWordWrap", "Toggle word wrap", commandId = "editor.action.toggleWordWrap"),
    toolbarAction("zoomIn", "Increase editor size", commandId = "editor.action.fontZoomIn"),
    toolbarAction("zoomOut", "Decrease editor size", commandId = "editor.action.fontZoomOut"),
)

private val ACTION_BY_ID = EDITOR_TOOLBAR_ACTIONS.associateBy { it.id }

fun editorToolbarAction(id: String): EditorToolbarAction? = ACTION_BY_ID[id]

/** Return a command from its stable action ID; never infer it from a page or list index. */
fun editorToolbarCommandId(actionId: String): String? = ACTION_BY_ID[actionId]?.commandId

/** Removes stale IDs, duplicates, and search actions from persisted user order. */
fun normalizeEditorToolbarOrder(order: List<String>): List<String> = order
    .asSequence()
    .mapNotNull(::editorToolbarAction)
    .distinctBy { it.id }
    .map { it.id }
    .toList()
