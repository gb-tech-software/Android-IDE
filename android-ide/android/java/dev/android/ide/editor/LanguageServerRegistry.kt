package dev.android.ide.editor

import dev.android.ide.contracts.LanguageServerDefinition

/**
 * Language-server executables are installed from the app's pinned on-demand
 * package set inside the private Termux prefix and launched with stdio JSON-RPC.
 * This registry maps Monaco language IDs to commands without bundling the tools.
 */
object LanguageServerRegistry {
    val definitions: List<LanguageServerDefinition> = listOf(
        LanguageServerDefinition(
            id = "typescript",
            languageIds = setOf("javascript", "typescript", "javascriptreact", "typescriptreact"),
            command = listOf("typescript-language-server", "--stdio"),
            requiredExecutable = "typescript-language-server",
        ),
        LanguageServerDefinition(
            id = "python",
            languageIds = setOf("python"),
            command = listOf("pyright-langserver", "--stdio"),
            requiredExecutable = "pyright-langserver",
        ),
        LanguageServerDefinition(
            id = "html",
            languageIds = setOf("html"),
            command = listOf("vscode-html-language-server", "--stdio"),
            requiredExecutable = "vscode-html-language-server",
        ),
        LanguageServerDefinition(
            id = "css",
            // Match only Monaco's bundled CSS-family language IDs. Indented Sass
            // has no matching grammar or language-server route in this bundle.
            languageIds = setOf("css", "scss", "less"),
            command = listOf("vscode-css-language-server", "--stdio"),
            requiredExecutable = "vscode-css-language-server",
        ),
        LanguageServerDefinition(
            id = "json",
            languageIds = setOf("json", "jsonc"),
            command = listOf("vscode-json-language-server", "--stdio"),
            requiredExecutable = "vscode-json-language-server",
        ),
        LanguageServerDefinition(
            id = "markdown",
            languageIds = setOf("markdown"),
            command = listOf("vscode-markdown-language-server", "--stdio"),
            requiredExecutable = "vscode-markdown-language-server",
        ),
    )

    fun forLanguage(languageId: String): LanguageServerDefinition? =
        definitions.firstOrNull { languageId in it.languageIds }
}
