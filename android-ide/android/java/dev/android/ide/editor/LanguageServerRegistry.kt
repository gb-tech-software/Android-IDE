package dev.android.ide.editor

import dev.android.ide.contracts.LanguageServerDefinition

/**
 * Language servers are ordinary Termux packages and are launched with stdio
 * JSON-RPC. The registry maps Monaco language IDs to launch commands without
 * making the editor depend on a particular package implementation.
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
            languageIds = setOf("css"),
            command = listOf("vscode-css-language-server", "--stdio"),
            requiredExecutable = "vscode-css-language-server",
        ),
        LanguageServerDefinition(
            id = "json",
            languageIds = setOf("json", "jsonc"),
            command = listOf("vscode-json-language-server", "--stdio"),
            requiredExecutable = "vscode-json-language-server",
        ),
    )

    fun forLanguage(languageId: String): LanguageServerDefinition? =
        definitions.firstOrNull { languageId in it.languageIds }
}
