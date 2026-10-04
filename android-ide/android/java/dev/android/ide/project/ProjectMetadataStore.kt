package dev.android.ide.project

import dev.android.ide.contracts.ErrorCategory
import dev.android.ide.contracts.OperationOutcome
import dev.android.ide.contracts.OperationReport
import dev.android.ide.contracts.ProjectIdentity
import dev.android.ide.contracts.ProjectLocation
import dev.android.ide.contracts.ProjectMetadataAdapter
import dev.android.ide.saf.ChildrenInspectionResult
import dev.android.ide.saf.FileManagementService
import dev.android.ide.contracts.ApplicationIdentity
import org.json.JSONObject
import java.time.Instant

/** Project-domain owner of portable metadata policy and legacy-directory migration. */
class ProjectMetadataStore(private val files: FileManagementService) : ProjectMetadataAdapter {
    private data class MetadataDirectories(val approved: String, val legacy: String?)

    private suspend fun locate(rootUri: String): MetadataDirectories? {
        val children = (files.inspectChildren(rootUri) as? ChildrenInspectionResult.Success)?.children
            ?: return null
        val approved = children.firstOrNull {
            it.isDirectory && it.displayName == ApplicationIdentity.TARGET_METADATA_DIRECTORY
        }
        if (approved != null) {
            return MetadataDirectories(
                approved = approved.documentUri,
                legacy = children.firstOrNull {
                    it.isDirectory && it.displayName == ApplicationIdentity.LEGACY_METADATA_DIRECTORY
                }?.documentUri,
            )
        }
        val legacy = children.firstOrNull {
            it.isDirectory && it.displayName == ApplicationIdentity.LEGACY_METADATA_DIRECTORY
        }?.documentUri
        val created = files.createFolder(rootUri, ApplicationIdentity.TARGET_METADATA_DIRECTORY)
        val approvedUri = (created as? dev.android.ide.saf.ExactCreateResult.Created)?.documentUri
            ?: return null
        if ((files.inspectChildren(approvedUri) as? ChildrenInspectionResult.Success) == null) {
            return null
        }
        return MetadataDirectories(approvedUri, legacy)
    }

    override suspend fun ensurePortableState(project: ProjectIdentity): OperationReport {
        val root = project.location.stableId
        val directories = locate(root)
            ?: return failed("Portable project metadata could not be inspected", project.id, ErrorCategory.PERMISSION_LOST)
        val legacy = directories.legacy ?: return complete("Portable project metadata initialized", project.id)
        val legacyChildren = (files.inspectChildren(legacy) as? ChildrenInspectionResult.Success)
            ?: return failed("Legacy project metadata could not be inspected", project.id, ErrorCategory.PERMISSION_LOST)
        for (source in legacyChildren.children) {
            if (source.isDirectory) {
                return failed("Legacy project metadata contains an unsupported directory", project.id, ErrorCategory.MALFORMED_METADATA)
            }
            val bytes = files.read(source.documentUri)
                ?: return failed("Legacy project metadata could not be read", project.id, ErrorCategory.PERMISSION_LOST)
            val copied = when (val target = files.createFile(directories.approved, source.displayName, source.mimeType)) {
                is dev.android.ide.saf.ExactCreateResult.Created -> files.write(target.documentUri, bytes)
                dev.android.ide.saf.ExactCreateResult.Duplicate -> {
                    val existing = (files.inspectChildren(directories.approved) as? ChildrenInspectionResult.Success)
                        ?.children?.firstOrNull { !it.isDirectory && it.displayName == source.displayName }
                    existing != null && files.read(existing.documentUri)?.contentEquals(bytes) == true
                }
                else -> false
            }
            if (!copied) return failed("Legacy project metadata could not be migrated", project.id, ErrorCategory.MALFORMED_METADATA)
        }
        return if (files.delete(legacy)) complete("Portable project metadata migrated", project.id)
        else failed("Legacy project metadata could not be removed", project.id, ErrorCategory.PERMISSION_LOST)
    }

    override suspend fun readIdentity(location: ProjectLocation): ProjectIdentity? {
        if (ensurePortableState(ProjectIdentity(
                id = location.stableId,
                name = location.displayLabel,
                description = "",
                location = location,
                registeredAt = Instant.now(),
                lastOpenedAt = null,
            )).outcome != OperationOutcome.COMPLETE
        ) return null
        val manifest = readJson(location.stableId, "project.json") ?: return null
        val project = manifest.optJSONObject("project") ?: return null
        return ProjectIdentity(
            id = location.stableId,
            name = project.optString("name", location.displayLabel),
            description = project.optString("description", ""),
            location = location,
            registeredAt = Instant.ofEpochMilli(manifest.optLong("createdAt", System.currentTimeMillis())),
            lastOpenedAt = null,
        )
    }

    override suspend fun writeIdentity(project: ProjectIdentity): OperationReport {
        ensurePortableState(project).takeIf { it.outcome != OperationOutcome.COMPLETE }?.let { return it }
        val existing = readJson(project.location.stableId, "project.json")
        val manifest = JSONObject().apply {
            put("schemaVersion", existing?.optInt("schemaVersion", 1) ?: 1)
            put("project", (existing?.optJSONObject("project") ?: JSONObject()).apply {
                put("name", project.name)
                put("description", project.description)
                if (!has("createdAt")) put("createdAt", project.registeredAt.toEpochMilli())
                put("updatedAt", System.currentTimeMillis())
            })
        }
        return if (writeJson(project.location.stableId, "project.json", manifest.toString(2)))
            complete("Portable project identity written", project.id)
        else failed("Portable project identity could not be written", project.id, ErrorCategory.MALFORMED_METADATA)
    }

    override suspend fun readWorkspaceDescriptor(projectId: String): ByteArray? =
        readJson(projectId, "workspace.json")?.toString()?.toByteArray()

    override suspend fun writeWorkspaceDescriptor(projectId: String, descriptor: ByteArray): OperationReport {
        val ok = writeRaw(projectId, "workspace.json", descriptor)
        return if (ok) complete("Workspace descriptor written", projectId)
        else failed("Workspace descriptor could not be written", projectId, ErrorCategory.MALFORMED_METADATA)
    }

    suspend fun deletePortableState(project: ProjectIdentity): OperationReport {
        val children = (files.inspectChildren(project.location.stableId) as? ChildrenInspectionResult.Success)
            ?: return failed("Project metadata could not be inspected", project.id, ErrorCategory.PERMISSION_LOST)
        val owned = children.children.filter {
            it.isDirectory && (it.displayName == ApplicationIdentity.TARGET_METADATA_DIRECTORY ||
                it.displayName == ApplicationIdentity.LEGACY_METADATA_DIRECTORY)
        }
        return if (owned.all { files.delete(it.documentUri) }) complete("Android IDE project metadata removed", project.id)
        else failed("Android IDE project metadata could not be removed", project.id, ErrorCategory.PERMISSION_LOST)
    }

    suspend fun readWorkspace(projectId: String): JSONObject? = readJson(projectId, "workspace.json")

    suspend fun writeWorkspace(projectId: String, workspace: JSONObject): Boolean =
        writeJson(projectId, "workspace.json", workspace.toString(2))

    suspend fun ensureEditorFiles(
        projectId: String,
        displayName: String,
        createdAt: Long,
        description: String = "",
    ): Boolean {
        val identity = ProjectIdentity(
            id = projectId,
            name = displayName,
            description = description,
            location = ProjectLocation(projectId, displayName),
            registeredAt = Instant.ofEpochMilli(createdAt),
        )
        if (ensurePortableState(identity).outcome != OperationOutcome.COMPLETE) return false
        if (readJson(projectId, "project.json") == null && !writeJson(
                projectId,
                "project.json",
                JSONObject().apply {
                    put("schemaVersion", 1)
                    put("project", JSONObject().apply {
                        put("name", displayName)
                        put("description", description)
                        put("createdAt", createdAt)
                        put("updatedAt", createdAt)
                    })
                }.toString(2),
            )) return false
        if (readJson(projectId, "workspace.json") == null && !writeJson(
                projectId,
                "workspace.json",
                JSONObject().apply {
                    put("schemaVersion", 1)
                    put("openTabUris", org.json.JSONArray())
                    put("activeTabUri", JSONObject.NULL)
                    put("cursorPositions", JSONObject())
                    put("scrollPositions", JSONObject())
                    put("updatedAt", System.currentTimeMillis())
                }.toString(2),
            )) return false
        return true
    }

    private suspend fun readJson(root: String, name: String): JSONObject? {
        if (ensurePortableState(ProjectIdentity(
                id = root,
                name = root,
                description = "",
                location = ProjectLocation(root, root),
                registeredAt = Instant.now(),
            )).outcome != OperationOutcome.COMPLETE
        ) return null
        val directories = locate(root) ?: return null
        val file = (files.inspectChildren(directories.approved) as? ChildrenInspectionResult.Success)
            ?.children?.firstOrNull { !it.isDirectory && it.displayName == name } ?: return null
        return files.read(file.documentUri)?.toString(Charsets.UTF_8)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private suspend fun writeJson(root: String, name: String, content: String): Boolean =
        writeRaw(root, name, content.toByteArray(Charsets.UTF_8))

    private suspend fun writeRaw(root: String, name: String, content: ByteArray): Boolean {
        if (ensurePortableState(ProjectIdentity(
                id = root,
                name = root,
                description = "",
                location = ProjectLocation(root, root),
                registeredAt = Instant.now(),
            )).outcome != OperationOutcome.COMPLETE
        ) return false
        val directories = locate(root) ?: return false
        val existing = (files.inspectChildren(directories.approved) as? ChildrenInspectionResult.Success)
            ?.children?.firstOrNull { it.displayName == name }
        if (existing?.isDirectory == true) return false
        val target = existing?.documentUri ?: (files.createFile(directories.approved, name, "application/json")
            as? dev.android.ide.saf.ExactCreateResult.Created)?.documentUri ?: return false
        return files.write(target, content)
    }

    private fun complete(message: String, id: String) = OperationReport(OperationOutcome.COMPLETE, message, affectedIds = listOf(id))
    private fun failed(message: String, id: String, category: ErrorCategory) = OperationReport(OperationOutcome.FAILED, message, category, listOf(id))
}
