// Shared application contracts. Implementations stay in their owning domain packages.
package dev.android.ide.contracts

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
object OpenUrlRequestProtocol {
    /** Explicit MainActivity action used by the bundled terminal URL helper. */
    const val ACTION = "dev.android.ide.action.OPEN_URL_REQUEST"
    const val URL_EXTRA = "dev.android.ide.extra.OPEN_URL"
    const val REQUEST_ID_EXTRA = "dev.android.ide.extra.OPEN_URL_REQUEST_ID"
    const val OPEN_IN_NEW_TAB_EXTRA = "dev.android.ide.extra.OPEN_URL_NEW_TAB"
}
