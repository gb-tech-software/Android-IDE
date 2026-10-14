// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

interface EditorDocumentAdapter {
    suspend fun load(project: ProjectIdentity, path: ProjectRelativePath): ByteArray
    suspend fun save(project: ProjectIdentity, path: ProjectRelativePath, content: ByteArray): OperationReport
    suspend fun reportExternalChange(projectId: String, path: ProjectRelativePath): OperationReport
}

data class LanguageServerDefinition(
    val id: String,
    val languageIds: Set<String>,
    val command: List<String>,
    val requiredExecutable: String,
)

enum class LanguageServerFeature {
    COMPLETION,
    SIGNATURE_HELP,
    HOVER,
    DEFINITION,
    DECLARATION,
    TYPE_DEFINITION,
    IMPLEMENTATION,
    REFERENCES,
    RENAME,
    DOCUMENT_FORMATTING,
    RANGE_FORMATTING,
    CODE_ACTION,
    DOCUMENT_SYMBOL,
    WORKSPACE_SYMBOL,
    SEMANTIC_TOKENS,
    INLAY_HINTS,
    CODE_LENS,
    FOLDING_RANGE,
    SELECTION_RANGE,
    LINKED_EDITING,
    EXECUTE_COMMAND,
    WORKSPACE_EDIT,
}

data class LanguageServerStatus(
    val serverId: String,
    val projectId: String,
    val languageId: String,
    val available: Boolean,
    val initialized: Boolean = false,
    val explanation: String? = null,
    val supportedFeatures: Set<LanguageServerFeature> = emptySet(),
)

/**
 * LSP processes are terminal-runtime children. They use the selected project
 * working directory, provider-backed files, package paths, and environment.
 * No language-server process may maintain a second project copy.
 */
interface LanguageServerAdapter {
    suspend fun start(project: ProjectIdentity, languageId: String): LanguageServerStatus
    suspend fun send(projectId: String, serverId: String, message: ByteArray): OperationReport
    suspend fun stop(projectId: String, serverId: String): OperationReport
    suspend fun status(projectId: String): List<LanguageServerStatus>
    suspend fun stopAll(): OperationReport
}
