package dev.android.ide.project

import android.content.res.AssetManager
import java.util.Locale

/**
 * Reads starter projects from packaged assets. The acquisition workflow owns the
 * destination writes; this class only returns source-relative files and applies
 * safe, explicit placeholders.
 */
class ProjectTemplateCatalog(
    private val assets: AssetManager,
) {
    fun filesFor(template: CreateProjectTemplate, projectName: String, description: String): List<Pair<String, String>> {
        val directoryName = template.name.lowercase(Locale.ROOT).replace('_', '-')
        val root = "project-templates/$directoryName"
        val packageName = projectName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]"), "-")
        val projectNameJson = jsonString(projectName)
        val descriptionJson = jsonString(description.trim())
        val projectNameHtml = htmlString(projectName)
        val descriptionHtml = htmlString(description.trim())
        return collect(root, "").map { (path, source) ->
            path to source
                .replace("{{PROJECT_NAME}}", projectName)
                .replace("{{PROJECT_NAME_JSON}}", projectNameJson)
                .replace("{{PACKAGE_NAME}}", packageName)
                .replace("{{DESCRIPTION}}", description.trim())
                .replace("{{DESCRIPTION_JSON}}", descriptionJson)
                .replace("{{PROJECT_NAME_HTML}}", projectNameHtml)
                .replace("{{DESCRIPTION_HTML}}", descriptionHtml)
        }
    }

    private fun collect(assetPath: String, relativePath: String): List<Pair<String, String>> {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            val content = assets.open(assetPath).bufferedReader().use { it.readText() }
            return listOf(relativePath to content)
        }
        return children.flatMap { child ->
            val childAssetPath = "$assetPath/$child"
            val childRelativePath = if (relativePath.isEmpty()) child else "$relativePath/$child"
            collect(childAssetPath, childRelativePath)
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun htmlString(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
