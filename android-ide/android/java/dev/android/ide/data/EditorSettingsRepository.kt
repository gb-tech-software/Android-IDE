// android-ide/android/java/dev/android/ide/data/EditorSettingsRepository.kt
//
// Persists editor display, behaviour, and control settings in SharedPreferences.

package dev.android.ide.data

import android.content.Context
import dev.android.ide.data.model.EditorSettings
import dev.android.ide.data.model.VolumeKeyMode

class EditorSettingsRepository(context: Context) {

    companion object {
        private const val PREFS                    = "editor_settings"
        private const val KEY_FONT_SIZE            = "font_size"
        private const val KEY_TAB_SIZE             = "tab_size"
        private const val KEY_WORD_WRAP            = "word_wrap"
        private const val KEY_LINE_NUMBERS         = "line_numbers"
        private const val KEY_AUTO_SAVE            = "auto_save"
        private const val KEY_LANGUAGE_SERVER_ENABLED = "language_server_enabled"
        private const val KEY_EDITOR_THEME         = "editor_theme"
        private const val KEY_VOLUME_MODE          = "volume_key_mode"
        private const val KEY_SHOW_KEYBOARD_BAR    = "show_keyboard_toolbar"
        private const val KEY_SHOW_SYMBOL_BAR      = "show_symbol_bar"
        private const val KEY_KEYBOARD_TOOLBAR_ORDER = "keyboard_toolbar_order"
        private const val KEY_SHOW_STATUS_BAR      = "show_status_bar"
        private const val KEY_HIDE_GIT_FOLDER      = "hide_git_folder"
        private const val KEY_HIDE_METADATA_FOLDER = "hide_project_metadata_folder"
        private const val KEY_UI_FONT_SCALE        = "ui_font_scale"
        private const val KEY_RENDER_WHITESPACE       = "render_whitespace"
        private const val KEY_MINIMAP_ENABLED         = "minimap_enabled"
        private const val KEY_SCROLL_BEYOND_LAST_LINE = "scroll_beyond_last_line"
        private const val KEY_CURSOR_STYLE            = "cursor_style"
        private const val KEY_BRACKET_PAIR_COLOR      = "bracket_pair_colorization"
        private const val KEY_AUTO_CLOSING_BRACKETS   = "auto_closing_brackets"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getEditorSettings(): EditorSettings = EditorSettings(
        fontSize             = prefs.getInt(KEY_FONT_SIZE, 14),
        tabSize              = prefs.getInt(KEY_TAB_SIZE, 4),
        wordWrap             = prefs.getBoolean(KEY_WORD_WRAP, false),
        lineNumbers          = prefs.getBoolean(KEY_LINE_NUMBERS, true),
        autoSave             = prefs.getBoolean(KEY_AUTO_SAVE, false),
        languageServerEnabled = prefs.getBoolean(KEY_LANGUAGE_SERVER_ENABLED, false),
        editorTheme          = prefs.getString(KEY_EDITOR_THEME, "application")?.let { if (it == "system") "application" else it } ?: "application",
        showKeyboardToolbar  = prefs.getBoolean(KEY_SHOW_KEYBOARD_BAR, true),
        showSymbolBar        = prefs.getBoolean(KEY_SHOW_SYMBOL_BAR, true),
        keyboardToolbarOrder = prefs.getString(KEY_KEYBOARD_TOOLBAR_ORDER, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: EditorSettings.DEFAULT_KEYBOARD_TOOLBAR_ORDER,
        showStatusBar        = prefs.getBoolean(KEY_SHOW_STATUS_BAR, true),
        hideGitFolder        = prefs.getBoolean(KEY_HIDE_GIT_FOLDER, true),
        hideProjectMetadataFolder = prefs.getBoolean(KEY_HIDE_METADATA_FOLDER, true),
        uiFontScale          = prefs.getFloat(KEY_UI_FONT_SCALE, 1.0f)
            .coerceIn(EditorSettings.UI_FONT_SCALE_MIN, EditorSettings.UI_FONT_SCALE_MAX),
        renderWhitespace       = prefs.getString(KEY_RENDER_WHITESPACE, "selection") ?: "selection",
        minimapEnabled         = prefs.getBoolean(KEY_MINIMAP_ENABLED, true),
        scrollBeyondLastLine   = prefs.getBoolean(KEY_SCROLL_BEYOND_LAST_LINE, false),
        cursorStyle            = prefs.getString(KEY_CURSOR_STYLE, "line") ?: "line",
        bracketPairColorization= prefs.getBoolean(KEY_BRACKET_PAIR_COLOR, true),
        autoClosingBrackets    = prefs.getString(KEY_AUTO_CLOSING_BRACKETS, "languageDefined") ?: "languageDefined",
    )

    fun setEditorSettings(settings: EditorSettings) {
        prefs.edit()
            .putInt    (KEY_FONT_SIZE,           settings.fontSize)
            .putInt    (KEY_TAB_SIZE,            settings.tabSize)
            .putBoolean(KEY_WORD_WRAP,           settings.wordWrap)
            .putBoolean(KEY_LINE_NUMBERS,        settings.lineNumbers)
            .putBoolean(KEY_AUTO_SAVE,           settings.autoSave)
            .putBoolean(KEY_LANGUAGE_SERVER_ENABLED, settings.languageServerEnabled)
            .putString (KEY_EDITOR_THEME,        settings.editorTheme)
            .putBoolean(KEY_SHOW_KEYBOARD_BAR,   settings.showKeyboardToolbar)
            .putBoolean(KEY_SHOW_SYMBOL_BAR,     settings.showSymbolBar)
            .putString(KEY_KEYBOARD_TOOLBAR_ORDER, settings.keyboardToolbarOrder.joinToString(","))
            .putBoolean(KEY_SHOW_STATUS_BAR,     settings.showStatusBar)
            .putBoolean(KEY_HIDE_GIT_FOLDER,     settings.hideGitFolder)
            .putBoolean(KEY_HIDE_METADATA_FOLDER, settings.hideProjectMetadataFolder)
            .putFloat  (KEY_UI_FONT_SCALE,       settings.uiFontScale
                .coerceIn(EditorSettings.UI_FONT_SCALE_MIN, EditorSettings.UI_FONT_SCALE_MAX))
            .putString (KEY_RENDER_WHITESPACE,       settings.renderWhitespace)
            .putBoolean(KEY_MINIMAP_ENABLED,         settings.minimapEnabled)
            .putBoolean(KEY_SCROLL_BEYOND_LAST_LINE, settings.scrollBeyondLastLine)
            .putString (KEY_CURSOR_STYLE,            settings.cursorStyle)
            .putBoolean(KEY_BRACKET_PAIR_COLOR,      settings.bracketPairColorization)
            .putString (KEY_AUTO_CLOSING_BRACKETS,   settings.autoClosingBrackets)
            .apply()
    }

    fun getVolumeKeyMode(): VolumeKeyMode = runCatching {
        VolumeKeyMode.valueOf(prefs.getString(KEY_VOLUME_MODE, VolumeKeyMode.HORIZONTAL.name) ?: "HORIZONTAL")
    }.getOrElse { VolumeKeyMode.HORIZONTAL }

    fun setVolumeKeyMode(mode: VolumeKeyMode) {
        prefs.edit().putString(KEY_VOLUME_MODE, mode.name).apply()
    }
}
