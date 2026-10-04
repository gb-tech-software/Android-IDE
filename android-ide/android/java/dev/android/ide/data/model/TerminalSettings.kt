package dev.android.ide.data.model

/** Terminal-only appearance and shortcut preferences. */
data class TerminalSettings(
    /** "dark", "light", "application", or "device". */
    val theme: String = "application",
    val textSize: Int = 14,
    val shortcutToolbarEnabled: Boolean = true,
    val shortcutOrder: List<String> = DEFAULT_SHORTCUT_ORDER,
) {
    companion object {
        val DEFAULT_SHORTCUT_ORDER = listOf(
            "ctrl", "alt", "escape", "ctrlC", "ctrlD", "ctrlZ", "ctrlL",
            "escapeKey", "tab", "enter", "backspace", "up", "down", "left", "right",
            "home", "end", "pageUp", "pageDown",
        )
    }
}
