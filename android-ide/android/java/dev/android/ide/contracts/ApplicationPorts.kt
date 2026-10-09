// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

interface BrowserPreviewAdapter {
    suspend fun listTabs(): List<BrowserTabIdentity>
    suspend fun closeTab(tabId: String): OperationReport
}

interface CredentialVaultAdapter {
    suspend fun listCredentialIds(): List<String>
    suspend fun revoke(credentialId: String): OperationReport
    fun redact(value: String): String
}

interface LifecycleCoordinator {
    suspend fun restore(): OperationReport
    suspend fun onForeground(): OperationReport
    suspend fun onBackground(): OperationReport
    suspend fun onExplicitExit(): OperationReport
    suspend fun onRuntimeServiceStopped(reason: String): OperationReport = OperationReport(
        outcome = OperationOutcome.INTERRUPTED,
        message = "Runtime service stopped: $reason",
        errorCategory = ErrorCategory.PROCESS_LOSS,
    )
    suspend fun onProcessRecreated(): OperationReport = OperationReport(
        outcome = OperationOutcome.COMPLETE,
        message = "Application process recreated",
    )
}

/** Facts shared across domains. Event payloads must never contain secrets. */
