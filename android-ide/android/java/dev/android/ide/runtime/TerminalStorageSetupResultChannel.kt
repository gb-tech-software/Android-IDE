package dev.android.ide.runtime

import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.TerminalStorageSetupProtocol
import java.io.File
import java.io.IOException

/** Publishes the completion record consumed by the waiting termux-setup-storage process. */
internal class TerminalStorageSetupResultChannel(private val home: File) {
    fun publishAccepted(requestId: String) {
        requireValidRequestId(requestId)
        val directory = prepareDirectory()
        val acceptedFile = File(directory, "$requestId${TerminalStorageSetupProtocol.ACCEPTED_SUFFIX}")
        val temporaryFile = File(directory, "$requestId.accepted.tmp")
        temporaryFile.writeText("accepted\n")
        if (acceptedFile.exists()) acceptedFile.delete()
        if (!temporaryFile.renameTo(acceptedFile)) {
            temporaryFile.delete()
            throw IOException("Unable to publish the terminal storage setup acknowledgement")
        }
    }

    fun publish(requestId: String, report: OperationReport) {
        requireValidRequestId(requestId)
        val directory = prepareDirectory()

        val resultFile = File(directory, "$requestId.result")
        val temporaryFile = File(directory, "$requestId.tmp")
        temporaryFile.writeText(buildString {
            append(if (report.outcome == OperationOutcome.COMPLETE) "0\n" else "1\n")
            append(report.message)
            report.recoveryHint?.takeIf(String::isNotBlank)?.let {
                append('\n')
                append(it)
            }
            append('\n')
        })
        if (resultFile.exists() && !resultFile.delete()) {
            temporaryFile.delete()
            throw IOException("Unable to replace the terminal storage setup result")
        }
        if (!temporaryFile.renameTo(resultFile)) {
            temporaryFile.delete()
            throw IOException("Unable to publish the terminal storage setup result")
        }
    }

    private fun requireValidRequestId(requestId: String) {
        if (!requestId.matches(REQUEST_ID_PATTERN)) {
            throw IOException("The terminal storage setup request id is invalid")
        }
    }

    private fun prepareDirectory(): File {
        val directory = File(home, TerminalStorageSetupProtocol.RESULT_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create the terminal storage setup result directory")
        }
        return directory
    }

    private companion object {
        val REQUEST_ID_PATTERN = Regex("[0-9]+")
    }
}
