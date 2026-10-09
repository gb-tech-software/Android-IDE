package dev.android.ide.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/** Owns Content-Length framing for an LSP stdio transport; it does not own process lifecycle. */
class LanguageServerProtocolTransport(
    private val input: InputStream,
    private val output: OutputStream,
) {
    suspend fun readFrames(onFrame: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        while (true) {
            val header = readUntilHeaderEnd() ?: break
            val length = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: break
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) return@withContext
                offset += read
            }
            onFrame(body)
        }
    }

    fun write(message: ByteArray) {
        synchronized(output) {
            output.write("Content-Length: ${message.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(message)
            output.flush()
        }
    }

    private fun readUntilHeaderEnd(): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return null
            bytes += value.toByte()
            if (bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) {
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
        }
    }
}
