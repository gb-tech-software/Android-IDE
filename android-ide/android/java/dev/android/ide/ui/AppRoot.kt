package dev.android.ide.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import dev.android.ide.app.AppShellViewModel
import dev.android.ide.app.BackgroundProjectOperation
import dev.android.ide.app.ExternalContentActionKind
import dev.android.ide.app.ExternalContentRoute
import dev.android.ide.data.ThemeResolver
import dev.android.ide.contracts.Surface
import dev.android.ide.project.CreateProjectTemplate
import dev.android.ide.ui.shell.AppShell
import dev.android.ide.ui.screen.humanReadableStorageLocation
import dev.android.ide.ui.theme.AndroidIDETheme
import dev.android.ide.viewmodel.IdeViewModel

private fun projectNameIsValid(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

/** Composition root: owns Android launchers and reviewed acquisition workflow state. */
@Composable
fun AppRoot(viewModel: AppShellViewModel, ideViewModel: IdeViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val shellState by viewModel.state.collectAsState()
    val ideState by ideViewModel.uiState.collectAsState()
    TerminalStorageSetupPermissionHost(viewModel, shellState.terminalStorageSetupRequest)
    val themeResolver = remember { ThemeResolver(context) { viewModel.state.value.applicationTheme } }
    val resolvedAppTheme = themeResolver.getAppTheme()
    androidx.compose.runtime.LaunchedEffect(shellState.applicationTheme) {
        ideViewModel.onApplicationThemeChanged()
    }
    var createVisible by remember { mutableStateOf(false) }
    var createReviewVisible by remember { mutableStateOf(false) }
    var createConfirmationVisible by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createDescription by remember { mutableStateOf("") }
    var createDestination by remember { mutableStateOf<String?>(null) }
    var createTemplate by remember { mutableStateOf(CreateProjectTemplate.FROM_SCRATCH) }

    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderReviewVisible by remember { mutableStateOf(false) }
    var folderConfirmationVisible by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

    var zipUri by remember { mutableStateOf<String?>(null) }
    var zipReviewVisible by remember { mutableStateOf(false) }
    var zipConfirmationVisible by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf("") }
    var zipDescription by remember { mutableStateOf("") }
    var zipDestination by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pickerFeedback by remember { mutableStateOf<String?>(null) }
    var operationKind by remember { mutableStateOf<ProjectOperationKind?>(null) }
    var operationProjectId by remember { mutableStateOf<String?>(null) }
    var operationName by remember { mutableStateOf("") }
    var operationDescription by remember { mutableStateOf("") }
    var operationDestination by remember { mutableStateOf<String?>(null) }
    var operationReviewVisible by remember { mutableStateOf(false) }
    var exportReviewVisible by remember { mutableStateOf(false) }
    var pendingExportProjectId by remember { mutableStateOf<String?>(null) }
    var pendingBatchExportIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBackgroundOperation by remember { mutableStateOf<BackgroundProjectOperation?>(null) }

    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.clearOperationFeedback()
            if (persistTreePermission(context, uri)) {
                folderUri = uri.toString()
                folderName = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: ""
                folderConfirmationVisible = false
                viewModel.inspectExistingFolder(uri.toString())
                folderReviewVisible = true
            } else pickerFeedback = "Android did not grant durable read and write access to this folder. Choose a location that supports persistent access, then try again."
        }
    }
    val openZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.clearOperationFeedback()
            if (persistDocumentPermission(context, uri)) {
                zipUri = uri.toString()
                zipName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip") ?: "Imported project"
                zipDescription = ""
                zipDestination = null
                zipConfirmationVisible = false
                zipReviewVisible = true
            } else pickerFeedback = "Android did not grant durable read access to this archive. Choose an archive from a document provider that supports persistent access."
        }
    }
    val createDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (persistTreePermission(context, uri)) createDestination = uri.toString()
            else pickerFeedback = "Android did not grant durable read and write access to this project destination."
        }
    }
    val zipDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (persistTreePermission(context, uri)) zipDestination = uri.toString()
            else pickerFeedback = "Android did not grant durable read and write access to this ZIP destination."
        }
    }
    val operationDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (persistTreePermission(context, uri)) operationDestination = uri.toString()
            else pickerFeedback = "Android did not grant durable read and write access to this operation destination."
        }
    }
    val exportFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val projectId = pendingExportProjectId
        if (uri != null && projectId != null) {
            viewModel.selectProject(projectId)
            viewModel.exportSelectedProject(uri.toString())
        }
    }
    val batchExportDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pendingBatchExportIds.isNotEmpty()) {
            if (persistTreePermission(context, uri)) viewModel.exportProjects(pendingBatchExportIds.toList(), uri.toString())
            else pickerFeedback = "Android did not grant durable read and write access to the batch export destination."
        }
        pendingBatchExportIds = emptySet()
    }

    LaunchedEffect(shellState.pendingExternalContent) {
        when (val route = shellState.pendingExternalContent) {
            is ExternalContentRoute.Browser -> {
                viewModel.requestBrowserNavigation(route.request.url, route.request.openInNewTab)
                viewModel.consumeExternalContent(route.request.requestId)
            }
            is ExternalContentRoute.ImportArchive -> {
                zipUri = route.request.uri
                zipName = (route.request.displayName ?: "Imported project").removeSuffix(".zip").ifBlank { "Imported project" }
                zipDescription = ""
                zipDestination = null
                zipConfirmationVisible = false
                zipReviewVisible = true
                viewModel.consumeExternalContent(route.request.requestId)
            }
            is ExternalContentRoute.External -> {
                val contentUri = Uri.parse(route.request.uri)
                val readIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, route.request.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(route.request.grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(readIntent, "Open with").apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context.packageName, "dev.android.ide.MainActivity")))
                    }
                }
                runCatching { context.startActivity(chooser) }
                    .onFailure { pickerFeedback = "No external application could open this item." }
                viewModel.consumeExternalContent(route.request.requestId)
            }
            is ExternalContentRoute.Choose, null, ExternalContentRoute.Unsupported -> Unit
        }
    }

    val baseDensity = LocalDensity.current
    val uiScale = ideState.editorSettings.uiFontScale
    val resolvedDark = resolvedAppTheme == dev.android.ide.data.ResolvedTheme.DARK
    val rootView = LocalView.current
    AndroidIDETheme(resolvedTheme = resolvedAppTheme) {
        val systemBarColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            val activity = rootView.context as? Activity
            activity?.window?.let { window ->
                window.statusBarColor = systemBarColor
                window.navigationBarColor = systemBarColor
                androidx.core.view.WindowCompat.getInsetsController(window, rootView).apply {
                    isAppearanceLightStatusBars = !resolvedDark
                    isAppearanceLightNavigationBars = !resolvedDark
                }
            }
        }
        CompositionLocalProvider(
            LocalDensity provides androidx.compose.ui.unit.Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * uiScale,
            ),
        ) {
        AppShell(
            viewModel = viewModel,
            ideViewModel = ideViewModel,
            onExit = onExit,
            onCreateProject = { initialName ->
                viewModel.clearOperationFeedback()
                createName = initialName.orEmpty()
                createDescription = ""
                createDestination = null
                createTemplate = CreateProjectTemplate.FROM_SCRATCH
                createReviewVisible = false
                createConfirmationVisible = false
                createVisible = true
            },
            onImportFolder = { openFolder.launch(null) },
            onImportZip = { openZip.launch(arrayOf("application/zip", "application/octet-stream")) },
            onExportProject = { projectId -> viewModel.clearOperationFeedback(); pendingExportProjectId = projectId; exportReviewVisible = true },
            onExportProjects = { projectIds -> pendingBatchExportIds = projectIds; batchExportDestinationPicker.launch(null) },
            onDuplicateProject = { projectId ->
                viewModel.clearOperationFeedback()
                val project = shellState.projects.firstOrNull { it.id == projectId }
                operationKind = ProjectOperationKind.DUPLICATE
                operationProjectId = projectId
                operationName = project?.name?.let { "Copy of $it" }.orEmpty()
                operationDescription = project?.description.orEmpty()
                operationDestination = null
                operationReviewVisible = false
            },
            onRelocateProject = { projectId ->
                viewModel.clearOperationFeedback()
                val project = shellState.projects.firstOrNull { it.id == projectId }
                operationKind = ProjectOperationKind.RELOCATE
                operationProjectId = projectId
                operationName = project?.name.orEmpty()
                operationDescription = project?.description.orEmpty()
                operationDestination = null
                operationReviewVisible = false
            },
            onOpenBackgroundOperation = { operation -> selectedBackgroundOperation = operation },
        )

        selectedBackgroundOperation?.let { operation ->
            val currentOperation = shellState.backgroundOperations.firstOrNull { it.id == operation.id }
            if (currentOperation != null) {
                BackgroundProjectOperationDialog(
                    operation = currentOperation,
                    onDismiss = { selectedBackgroundOperation = null },
                )
            }
        }

        (shellState.pendingExternalContent as? ExternalContentRoute.Choose)?.let { choice ->
            AlertDialog(
                onDismissRequest = { viewModel.consumeExternalContent(choice.request.requestId) },
                title = { Text("Open ${choice.request.displayName ?: "content"}") },
                text = {
                    Column {
                        Text("Choose an available way to open this item.")
                        choice.actions.forEach { action ->
                            TextButton(
                                onClick = { viewModel.selectExternalContentAction(action.kind) },
                            ) { Text(action.label) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.consumeExternalContent(choice.request.requestId) }) { Text("Cancel") }
                },
            )
        }

        if (createVisible || createReviewVisible) {
            CreateNewProjectDialog(
                state = shellState,
                viewModel = viewModel,
                name = createName,
                description = createDescription,
                destination = createDestination,
                template = createTemplate,
                reviewVisible = createReviewVisible,
                confirmationVisible = createConfirmationVisible,
                onNameChange = { createName = it },
                onDescriptionChange = { createDescription = it },
                onDestination = { createDestinationPicker.launch(null) },
                onTemplateChange = { createTemplate = it },
                onReviewVisibleChange = {
                    createReviewVisible = it
                    createVisible = !it
                },
                onConfirmationVisibleChange = { createConfirmationVisible = it },
                onDismiss = { createVisible = false; createReviewVisible = false; createConfirmationVisible = false },
                onOpenProject = { createVisible = false; createReviewVisible = false; createConfirmationVisible = false; viewModel.openProject(it) },
                onLocation = ::humanReadableStorageLocation,
                validName = ::projectNameIsValid,
                onKeepInBackground = {
                    viewModel.keepCurrentProjectOperationInBackground()
                    createVisible = false
                    createReviewVisible = false
                    createConfirmationVisible = false
                },
            )
        }
        if (folderReviewVisible) {
            LoadExistingProjectDialog(
                state = shellState,
                viewModel = viewModel,
                folderUri = folderUri,
                folderName = folderName,
                confirmationVisible = folderConfirmationVisible,
                onConfirmationVisibleChange = { folderConfirmationVisible = it },
                onCancel = { folderReviewVisible = false; folderConfirmationVisible = false; viewModel.dismissAcquisitionPrompt() },
                onOpenProject = { folderReviewVisible = false; folderConfirmationVisible = false; viewModel.openProject(it) },
                onLocation = ::humanReadableStorageLocation,
                validName = ::projectNameIsValid,
                onKeepInBackground = {
                    viewModel.keepCurrentProjectOperationInBackground()
                    folderReviewVisible = false
                    folderConfirmationVisible = false
                },
            )
        }
        if (zipReviewVisible) {
            ImportZipProjectDialog(
                state = shellState,
                viewModel = viewModel,
                archiveUri = zipUri,
                name = zipName,
                description = zipDescription,
                destination = zipDestination,
                confirmationVisible = zipConfirmationVisible,
                onNameChange = { zipName = it },
                onDescriptionChange = { zipDescription = it },
                onDestination = { zipDestinationPicker.launch(null) },
                onConfirmationVisibleChange = { zipConfirmationVisible = it },
                onCancel = { zipReviewVisible = false; zipConfirmationVisible = false; viewModel.dismissAcquisitionPrompt() },
                onOpenProject = { zipReviewVisible = false; zipConfirmationVisible = false; viewModel.openProject(it) },
                onLocation = ::humanReadableStorageLocation,
                validName = ::projectNameIsValid,
                onKeepInBackground = {
                    viewModel.keepCurrentProjectOperationInBackground()
                    zipReviewVisible = false
                    zipConfirmationVisible = false
                },
            )
        }
        pickerFeedback?.let { message ->
            AlertDialog(
                onDismissRequest = { pickerFeedback = null },
                title = { Text("Storage access unavailable") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { pickerFeedback = null }) { Text("OK") } },
            )
        }
        val exportProject = shellState.projects.firstOrNull { it.id == pendingExportProjectId }
        ProjectExportDialog(
            visible = exportReviewVisible,
            projectName = exportProject?.name,
            storageLocation = exportProject?.location?.userVisiblePath
                ?.let(::humanReadableStorageLocation)
                ?: exportProject?.location?.displayLabel?.let(::humanReadableStorageLocation),
            operationReport = shellState.operationReport,
            operationInProgress = shellState.operationInProgress,
            onChooseDestination = { suggestedFileName -> exportFilePicker.launch(suggestedFileName) },
            onDismiss = { exportReviewVisible = false; pendingExportProjectId = null },
            onKeepInBackground = {
                viewModel.keepCurrentProjectOperationInBackground()
                exportReviewVisible = false
                pendingExportProjectId = null
            },
        )
        operationKind?.let { kind ->
            val project = shellState.projects.firstOrNull { it.id == operationProjectId }
            ProjectOperationDialog(
                kind = kind,
                projectName = project?.name,
                sourceLocation = project?.location?.userVisiblePath
                    ?.let(::humanReadableStorageLocation)
                    ?: project?.location?.displayLabel?.let(::humanReadableStorageLocation),
                name = operationName,
                description = operationDescription,
                destination = operationDestination?.let(::humanReadableStorageLocation),
                reviewVisible = operationReviewVisible,
                operationReport = shellState.operationReport,
                operationInProgress = shellState.operationInProgress,
                onNameChange = { operationName = it },
                onDescriptionChange = { operationDescription = it },
                isValidName = ::projectNameIsValid,
                onChooseDestination = { operationDestinationPicker.launch(null) },
                onReviewVisibleChange = { operationReviewVisible = it },
                onSubmit = {
                    val id = operationProjectId
                    val destination = operationDestination
                    if (id != null && destination != null) {
                        if (kind == ProjectOperationKind.DUPLICATE) {
                            viewModel.duplicateProject(id, destination, operationName, operationDescription)
                        } else {
                            viewModel.relocateProject(id, destination, operationName)
                        }
                    }
                },
                onDismiss = { operationKind = null; operationReviewVisible = false },
                onKeepInBackground = {
                    viewModel.keepCurrentProjectOperationInBackground()
                    operationKind = null
                    operationReviewVisible = false
                },
            )
        }
        }
    }
}

private fun persistTreePermission(context: android.content.Context, uri: android.net.Uri): Boolean {
    val resolver = context.contentResolver
    if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) return true
    val persisted = runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)
    return persisted && resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
}

private fun persistDocumentPermission(context: android.content.Context, uri: android.net.Uri): Boolean {
    val resolver = context.contentResolver
    if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return true
    val persisted = runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)
    return persisted && resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
