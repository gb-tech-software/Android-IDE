package dev.android.ide.project

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide lock for operations that read or mutate project resources.
 *
 * URI strings are treated as hierarchical resource identifiers. An operation
 * holding a directory also blocks operations on descendants, and vice versa.
 * This deliberately serializes overlapping work while allowing unrelated
 * projects and folders to proceed independently.
 */
class ProjectResourceLock {
    private data class Lease(val id: Long, val resources: Set<String>)

    private val gate = Mutex()
    private val active = mutableListOf<Lease>()
    private var nextId = 0L

    suspend fun <T> withLock(resources: Collection<String>, block: suspend () -> T): T {
        val requested = resources.mapNotNull(::normalize).toSet()
        require(requested.isNotEmpty()) { "At least one project resource is required" }
        val lease = awaitLease(requested)
        return try {
            block()
        } finally {
            gate.withLock { active.removeAll { it.id == lease.id } }
        }
    }

    private suspend fun awaitLease(resources: Set<String>): Lease {
        while (true) {
            val lease = gate.withLock {
                if (active.none { existing -> existing.resources.any { it overlaps resources } }) {
                    Lease(++nextId, resources).also(active::add)
                } else null
            }
            if (lease != null) return lease
            delay(RETRY_DELAY_MS)
        }
    }

    private fun normalize(value: String): String? = value.trim().trimEnd('/').takeIf(String::isNotEmpty)

    private infix fun String.overlaps(other: Set<String>): Boolean = other.any { candidate ->
        this == candidate || this.startsWith("$candidate/") || candidate.startsWith("$this/")
    }

    private companion object {
        const val RETRY_DELAY_MS = 75L
    }
}
