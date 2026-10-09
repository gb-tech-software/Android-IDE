package dev.android.ide.project

/** Result of resolving user-entered paths against a resource boundary. */
sealed interface ResourcePathResolution {
    data class Success(val segments: List<String>) : ResourcePathResolution
    data object OutsideBoundary : ResourcePathResolution
    data object MissingFinalName : ResourcePathResolution
    data object InvalidComponent : ResourcePathResolution
}

/**
 * Resolves a user-entered path relative to a selected resource boundary.
 * A leading slash is boundary-root-relative; other paths are relative to the
 * selected base. This performs lexical validation only. Provider resolution,
 * permissions, existence checks, and mutations belong to the filesystem module.
 */
class ResourcePathResolver {
    fun resolve(rawPath: String, relativeBase: List<String>): ResourcePathResolution {
        val input = rawPath.trim()
        if (input.isEmpty() || input.indexOf('\u0000') >= 0) {
            return ResourcePathResolution.MissingFinalName
        }
        if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(input)) {
            return ResourcePathResolution.OutsideBoundary
        }

        val boundaryRelative = input.startsWith('/') || input.startsWith('\\')
        val segments = if (boundaryRelative) mutableListOf() else relativeBase.toMutableList()
        for (component in input.replace('\\', '/').split('/')) {
            when (component) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return ResourcePathResolution.OutsideBoundary
                    segments.removeAt(segments.lastIndex)
                }
                else -> {
                    if (component.any(Char::isISOControl)) return ResourcePathResolution.InvalidComponent
                    segments += component
                }
            }
        }
        return if (segments.isEmpty()) ResourcePathResolution.MissingFinalName
        else ResourcePathResolution.Success(segments.toList())
    }

    fun format(segments: List<String>): String =
        if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
}
