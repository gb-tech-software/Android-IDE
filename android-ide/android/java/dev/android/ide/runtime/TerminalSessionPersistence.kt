package dev.android.ide.runtime

import android.content.Context
import dev.android.ide.contracts.SessionAvailability
import dev.android.ide.contracts.SessionDescriptor
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists durable session descriptors only. PTYs, process handles, and output remain
 * owned by the runtime/service implementation and are never serialized here.
 */
class TerminalSessionPersistence(context: Context) {
    private val store = RuntimeStateStore(context)

    fun readAll(): List<SessionDescriptor> {
        val raw = store.read(SCOPE, SESSIONS_KEY)?.toString(Charsets.UTF_8) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SessionDescriptor(
                            id = item.getString("id"),
                            ownerScope = item.getString("ownerScope"),
                            backendId = item.optString("backendId").ifBlank { null },
                            name = item.optString("name", "Untitled session").ifBlank { "Untitled session" },
                            workingDirectory = item.optString("workingDirectory").ifBlank { null },
                            createdAt = Instant.parse(item.getString("createdAt")),
                            availability = SessionAvailability.valueOf(item.getString("availability")),
                            terminationReason = item.optString("terminationReason").ifBlank { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun writeAll(sessions: List<SessionDescriptor>): Boolean {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("ownerScope", session.ownerScope)
                    .put("backendId", session.backendId)
                    .put("name", session.name)
                    .put("workingDirectory", session.workingDirectory)
                    .put("createdAt", session.createdAt.toString())
                    .put("availability", session.availability.name)
                    .put("terminationReason", session.terminationReason),
            )
        }
        return store.write(SCOPE, SESSIONS_KEY, array.toString().toByteArray(Charsets.UTF_8))
    }

    fun upsert(session: SessionDescriptor): Boolean =
        writeAll(readAll().filterNot { it.id == session.id } + session)

    fun remove(sessionId: String): Boolean =
        writeAll(readAll().filterNot { it.id == sessionId })

    /** Preserve descriptors for recovery, but never claim a stored PTY is still live. */
    fun markAvailableUnavailable(reason: String): List<SessionDescriptor> {
        val current = readAll()
        val updated = current.map { session ->
            if (session.availability == SessionAvailability.AVAILABLE) {
                session.copy(availability = SessionAvailability.UNAVAILABLE, terminationReason = reason)
            } else {
                session
            }
        }
        if (updated != current) writeAll(updated)
        return updated
    }

    /** Explicit application shutdown is the only boundary that discards descriptors. */
    fun clear(): Boolean = writeAll(emptyList())

    private companion object {
        const val SCOPE = "terminal"
        const val SESSIONS_KEY = "sessions.json"
    }
}
