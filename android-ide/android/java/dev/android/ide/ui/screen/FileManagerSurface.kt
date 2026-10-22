package dev.android.ide.ui.screen

import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.android.ide.contracts.FileSystemNode
import dev.android.ide.filemanager.FileManagerViewModel
import dev.android.ide.ui.components.FileTypeBadge

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerSurface(
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    /** Parent wiring is optional so this independent surface remains reusable. */
    onOpenTerminalAt: (FileSystemNode) -> Unit = {},
    onAcquireProjectFromFolder: (FileSystemNode) -> Unit = {},
    onAcquireProjectFromArchive: (FileSystemNode) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val model: FileManagerViewModel = viewModel()
    val state by model.state.collectAsState()
    var search by remember { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(FileManagerSortMode.NAME_ASCENDING) }
    var foldersFirst by rememberSaveable { mutableStateOf(true) }
    var fileFilter by rememberSaveable { mutableStateOf("All items") }
    var showHiddenItems by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileSystemNode?>(null) }
    var selectedItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var input by remember { mutableStateOf("") }
    var destinationTarget by remember { mutableStateOf<FileSystemNode?>(null) }
    var destinationMove by remember { mutableStateOf(false) }
    var accessPromptVisible by rememberSaveable { mutableStateOf(false) }
    // Back first dismisses transient File Manager state, then walks its own folder stack.
    BackHandler(enabled = searchVisible || selectedItems.isNotEmpty() || state.ancestors.isNotEmpty()) {
        when {
            searchVisible -> { searchVisible = false; search = "" }
            selectedItems.isNotEmpty() -> selectedItems = emptySet()
            state.ancestors.isNotEmpty() -> model.openBreadcrumb(state.breadcrumbs.lastIndex - 1)
        }
    }
    var sharedAccessGranted by remember { mutableStateOf(hasSharedStorageAccess(context)) }
    val legacyPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        sharedAccessGranted = hasSharedStorageAccess(context)
        model.refreshRoots()
        if (sharedAccessGranted) model.report("Shared storage access granted")
        else model.report("Shared storage access was not granted. Android IDE files remain available.")
    }
    fun requestSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            val allAppsSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { context.startActivity(appSettings) }
                .recoverCatching { context.startActivity(allAppsSettings) }
                .onFailure { model.report("Android could not open the all-files-access setting. Use the Android shared-storage folder picker instead.") }
        } else {
            legacyPermissionRequest.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    LaunchedEffect(Unit) {
        sharedAccessGranted = hasSharedStorageAccess(context)
        if (!sharedAccessGranted) accessPromptVisible = true
        model.refreshRoots()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sharedAccessGranted = hasSharedStorageAccess(context)
                model.refreshRoots()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val addLocation = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.onFailure { model.report("Android did not grant persistent write access to this location") }
            model.refreshRoots()
        }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) model.importFrom(uris.map { it.toString() })
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val documentUri = runCatching {
                android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
            }.getOrDefault(uri)
            model.importFrom(listOf(documentUri.toString()))
        }
    }
    // Copy/move destinations are selected through SAF, not by fabricating a path from a label.
    val destinationFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val source = selected
            if (source != null) {
                if (destinationMove) {
                    destinationTarget = FileSystemNode("androidide://filesystem/${Uri.encode(uri.toString())}", "Selected folder", true)
                    dialog = FileDialog.CONFIRM_MOVE
                } else model.copyOrMoveToPickedFolder(source, uri.toString(), move = false)
            }
        }
        destinationMove = false
    }
    var exportNodes by remember { mutableStateOf<List<FileSystemNode>>(emptyList()) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val nodes = exportNodes
        exportNodes = emptyList()
        if (uri != null && nodes.isNotEmpty()) model.export(nodes, uri.toString())
    }
    LaunchedEffect(state.pendingShareUris) {
        val values = state.pendingShareUris ?: return@LaunchedEffect
        val uris = values.map(Uri::parse)
        runCatching {
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uris.first()) }
            else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
            intent.clipData = ClipData.newUri(context.contentResolver, "Shared files", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share files"))
        }.onFailure { model.report("Could not open the Android share menu") }
        model.consumeShare()
    }
    LaunchedEffect(state.pendingOpenUri, state.pendingOpenMimeType) {
        val uri = state.pendingOpenUri ?: return@LaunchedEffect
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), state.pendingOpenMimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure { model.report("No app could open this file type") }
        model.consumeOpenWith()
    }
    LaunchedEffect(state.entries, sortMode) {
        if (sortMode.requiresMetadata) model.loadSortMetadata(state.entries)
    }
    LaunchedEffect(state.current?.virtualPath) {
        selectedItems = emptySet()
        selected = null
    }
    val listState = rememberLazyListState()
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var refreshDispatched by remember { mutableStateOf(false) }
    val refreshThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(state.loading) {
        if (!state.loading) refreshDispatched = false
    }
    val refreshConnection = remember(listState, state.loading, state.current?.virtualPath, refreshThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f && listState.firstVisibleItemIndex == 0 && !state.loading) {
                    pullDistancePx += available.y
                    if (pullDistancePx >= refreshThresholdPx && !refreshDispatched) {
                        refreshDispatched = true
                        pullDistancePx = 0f
                        model.refreshRoots()
                    }
                } else if (available.y < 0f) {
                    pullDistancePx = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pullDistancePx = 0f
                return Velocity.Zero
            }
        }
    }
    val visible = state.entries.filter { node ->
        (showHiddenItems || !node.displayLabel.startsWith('.')) &&
            node.displayLabel.contains(search, ignoreCase = true) &&
            when (fileFilter) { "Files" -> !node.isDirectory; "Folders" -> node.isDirectory; else -> true }
    }.sortedWith(Comparator { a, b ->
        val group = if (foldersFirst) b.isDirectory.compareTo(a.isDirectory) else 0
        if (group != 0) group else {
            val aMetadata = state.sortMetadata[a.virtualPath]
            val bMetadata = state.sortMetadata[b.virtualPath]
            val primary = when (sortMode) {
                FileManagerSortMode.NAME_ASCENDING -> a.displayLabel.compareTo(b.displayLabel, ignoreCase = true)
                FileManagerSortMode.NAME_DESCENDING -> b.displayLabel.compareTo(a.displayLabel, ignoreCase = true)
                FileManagerSortMode.NEWEST_FIRST -> compareNullableMetric(aMetadata?.modifiedMillis, bMetadata?.modifiedMillis, descending = true)
                FileManagerSortMode.OLDEST_FIRST -> compareNullableMetric(aMetadata?.modifiedMillis, bMetadata?.modifiedMillis, descending = false)
                FileManagerSortMode.LARGEST_FIRST -> compareNullableMetric(aMetadata?.sizeBytes, bMetadata?.sizeBytes, descending = true)
                FileManagerSortMode.SMALLEST_FIRST -> compareNullableMetric(aMetadata?.sizeBytes, bMetadata?.sizeBytes, descending = false)
            }
            if (primary != 0 || sortMode == FileManagerSortMode.NAME_ASCENDING || sortMode == FileManagerSortMode.NAME_DESCENDING) primary
            else a.displayLabel.compareTo(b.displayLabel, ignoreCase = true)
        }
    })

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open navigation") }
            if (selectedItems.isNotEmpty()) {
                Text("${selectedItems.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { selectedItems = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
                Box {
                    IconButton(onClick = { bulkMenu = true }) { Icon(Icons.Default.MoreVert, "Selected-item actions") }
                    DropdownMenu(expanded = bulkMenu, onDismissRequest = { bulkMenu = false }) {
                        DropdownMenuItem(text = { Text("Select all in folder") }, leadingIcon = { Icon(Icons.Default.CheckBox, null) }, onClick = { selectedItems = state.entries.map { it.virtualPath }.toSet(); bulkMenu = false })
                        DropdownMenuItem(text = { Text("Share selected") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { bulkMenu = false; model.share(state.entries.filter { it.virtualPath in selectedItems }) })
                        DropdownMenuItem(text = { Text("Export selected") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { bulkMenu = false; exportNodes = state.entries.filter { it.virtualPath in selectedItems }; exportPicker.launch(if (exportNodes.size > 1 || exportNodes.any { it.isDirectory }) "files.zip" else exportNodes.firstOrNull()?.displayLabel ?: "file") })
                        DropdownMenuItem(text = { Text("Item details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { bulkMenu = false; if (selectedItems.size == 1) state.entries.firstOrNull { it.virtualPath in selectedItems }?.let(model::loadDetails) else model.report("Select one item to view details") })
                        DropdownMenuItem(text = { Text("Delete selected", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { bulkMenu = false; dialog = FileDialog.DELETE_SELECTION })
                    }
                }
            } else {
                Text("Files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            IconButton(onClick = { searchVisible = !searchVisible }) { Icon(Icons.Default.Search, "Search this folder") }
            Box {
                IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Default.MoreVert, "File Manager actions") }
                DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                    DropdownMenuItem(text = { Text("Sort and filter…") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { overflowMenu = false; sortMenu = true })
                    DropdownMenuItem(text = { Text("Import files") }, leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) }, enabled = state.current != null && !state.busy, onClick = { overflowMenu = false; importFiles.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("Import folder") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, enabled = state.current != null && !state.busy, onClick = { overflowMenu = false; importFolder.launch(null) })
                    DropdownMenuItem(text = { Text("Add storage location") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { overflowMenu = false; addLocation.launch(null) })
                }
            }
        }
        if (sortMenu) {
            DropdownMenu(expanded = true, onDismissRequest = { sortMenu = false }) {
                Text("Name", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                DropdownMenuItem(text = { Text("A–Z") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.NAME_ASCENDING, onClick = null) }, onClick = { sortMode = FileManagerSortMode.NAME_ASCENDING; sortMenu = false })
                DropdownMenuItem(text = { Text("Z–A") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.NAME_DESCENDING, onClick = null) }, onClick = { sortMode = FileManagerSortMode.NAME_DESCENDING; sortMenu = false })
                HorizontalDivider()
                Text("Modified", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                DropdownMenuItem(text = { Text("Newest first") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.NEWEST_FIRST, onClick = null) }, onClick = { sortMode = FileManagerSortMode.NEWEST_FIRST; sortMenu = false })
                DropdownMenuItem(text = { Text("Oldest first") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.OLDEST_FIRST, onClick = null) }, onClick = { sortMode = FileManagerSortMode.OLDEST_FIRST; sortMenu = false })
                HorizontalDivider()
                Text("Size", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                DropdownMenuItem(text = { Text("Largest first") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.LARGEST_FIRST, onClick = null) }, onClick = { sortMode = FileManagerSortMode.LARGEST_FIRST; sortMenu = false })
                DropdownMenuItem(text = { Text("Smallest first") }, leadingIcon = { RadioButton(sortMode == FileManagerSortMode.SMALLEST_FIRST, onClick = null) }, onClick = { sortMode = FileManagerSortMode.SMALLEST_FIRST; sortMenu = false })
                HorizontalDivider()
                Text("Filter and visibility", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                DropdownMenuItem(text = { Text("All items") }, leadingIcon = { RadioButton(fileFilter == "All items", onClick = null) }, onClick = { fileFilter = "All items"; sortMenu = false })
                DropdownMenuItem(text = { Text("Files only") }, leadingIcon = { RadioButton(fileFilter == "Files", onClick = null) }, onClick = { fileFilter = "Files"; sortMenu = false })
                DropdownMenuItem(text = { Text("Folders only") }, leadingIcon = { RadioButton(fileFilter == "Folders", onClick = null) }, onClick = { fileFilter = "Folders"; sortMenu = false })
                DropdownMenuItem(text = { Text("Folders first") }, leadingIcon = { Checkbox(foldersFirst, onCheckedChange = null) }, onClick = { foldersFirst = !foldersFirst })
                DropdownMenuItem(text = { Text("Show hidden items") }, leadingIcon = { Checkbox(showHiddenItems, onCheckedChange = null) }, onClick = { showHiddenItems = !showHiddenItems })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            var storageMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { storageMenu = true }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text(state.current?.displayLabel ?: "Choose storage", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = storageMenu, onDismissRequest = { storageMenu = false }) {
                    Text("Mounted storages", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    state.roots.forEach { root ->
                        DropdownMenuItem(text = { Text(root.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { RadioButton(state.current?.virtualPath == root.virtualPath, onClick = null) }, onClick = { storageMenu = false; model.openRoot(root) })
                    }
                }
            }
        if (state.roots.isEmpty() && !state.loading) TextButton(onClick = { addLocation.launch(null) }) { Text("Choose storage") }
        }
        if (!sharedAccessGranted) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shared storage access is off", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::requestSharedStorageAccess) { Text("Grant access") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            state.breadcrumbs.forEachIndexed { index, node ->
                if (index > 0) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                TextButton(onClick = { model.openBreadcrumb(index) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(node.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
        }
        if (searchVisible) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true, placeholder = { Text("Search this folder") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") } })
        state.feedback?.let { InlineFeedback(it, positive = !it.startsWith("Could not")) }
        state.error?.let { InlineFeedback(it, positive = false) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.operationLabel?.let { Text("$it · continues in background", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (sortMode.requiresMetadata && state.sortMetadataLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Reading file details for sorting…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.error != null) TextButton(onClick = { state.roots.firstOrNull()?.let(model::openRoot) ?: addLocation.launch(null) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Choose another location") }
        if (state.current != null && !state.loading && visible.isEmpty() && state.error == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (search.isEmpty()) Icons.Default.FolderOpen else Icons.Default.SearchOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(38.dp))
                    Text(if (search.isEmpty()) "This folder is empty" else "No matching items", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(refreshConnection)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
                items(visible, key = { it.virtualPath }) { node ->
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (selectedItems.isNotEmpty()) selectedItems = if (node.virtualPath in selectedItems) selectedItems - node.virtualPath else selectedItems + node.virtualPath else if (node.isDirectory) model.openChild(node) else model.openWith(node) },
                            onLongClick = { selectedItems = selectedItems + node.virtualPath },
                        )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedItems.isNotEmpty()) Checkbox(checked = node.virtualPath in selectedItems, onCheckedChange = { checked -> selectedItems = if (checked) selectedItems + node.virtualPath else selectedItems - node.virtualPath })
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            if (node.isDirectory) {
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Folder, "Folder", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            } else {
                                FileTypeBadge(displayName = node.displayLabel, accent = true)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(node.displayLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (node.isDirectory) "Folder" else "File", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { selected = node; menu = true }) { Icon(Icons.Default.MoreVert, "More actions for ${node.displayLabel}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Select") }, leadingIcon = { Icon(Icons.Default.CheckBox, null) }, onClick = { menu = false; selectedItems = selectedItems + node.virtualPath })
                                DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; model.loadDetails(node) })
                                if (!node.isDirectory) DropdownMenuItem(text = { Text("Open with") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { menu = false; model.openWith(node) })
                                if (node.isDirectory) {
                                    DropdownMenuItem(text = { Text("Open terminal") }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { menu = false; onOpenTerminalAt(node) })
                                    DropdownMenuItem(text = { Text("Use folder as project") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menu = false; onAcquireProjectFromFolder(node) })
                                } else if (isArchiveNode(node)) {
                                    DropdownMenuItem(text = { Text("Use archive as project") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menu = false; onAcquireProjectFromArchive(node) })
                                }
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; selectedItems = setOf(node.virtualPath); model.share(listOf(node)) })
                                DropdownMenuItem(text = { Text(if (node.isDirectory) "Export as ZIP…" else "Export…") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { menu = false; exportNodes = listOf(node); exportPicker.launch(if (node.isDirectory) "${node.displayLabel}.zip" else node.displayLabel) })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; selected = node; input = node.displayLabel; dialog = FileDialog.RENAME })
                                DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; selected = node; destinationTarget = null; dialog = FileDialog.COPY })
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { menu = false; selected = node; destinationTarget = null; dialog = FileDialog.MOVE })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; selected = node; dialog = FileDialog.DELETE })
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                }
            }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            val canCreateFolder = state.current != null && !state.busy
            FloatingActionButton(
                onClick = { if (canCreateFolder) { dialog = FileDialog.CREATE; input = "" } },
                containerColor = if (canCreateFolder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
            }
        }
    }
    if (accessPromptVisible) {
        AlertDialog(
            onDismissRequest = { accessPromptVisible = false },
            title = { Text("Allow shared-storage access?") },
            text = { Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "To browse and manage files across Android shared storage, Android IDE needs the system’s ‘Allow access to manage all files’ permission. Android will open the special app-access settings page for you to approve it." else "To browse and manage shared files on this Android version, Android IDE needs read and write access to shared storage. Android will ask you to grant these permissions." ) },
            confirmButton = { TextButton(onClick = { accessPromptVisible = false; requestSharedStorageAccess() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { accessPromptVisible = false }) { Text("Not now") } },
        )
    }
    when (dialog) {
        FileDialog.DELETE_SELECTION -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${selectedItems.size} items?") },
            text = { Text("This permanently deletes the selected files and folders and their contents. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { val nodes = state.entries.filter { it.virtualPath in selectedItems }; selectedItems = emptySet(); dialog = null; model.deleteMany(nodes) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        FileDialog.CREATE, FileDialog.RENAME -> {
            val target = selected
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == FileDialog.CREATE) "New folder" else "Rename") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("Name") })
                    Text(if (dialog == FileDialog.CREATE) "Created in ${state.current?.displayLabel.orEmpty()}" else "The name must be unique in this folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = { TextButton(enabled = input.isNotBlank() && !state.busy, onClick = {
                val name = input.trim(); val action = dialog; dialog = null
                if (action == FileDialog.CREATE) model.createFolder(name)
                else if (target != null) model.rename(target, name)
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        FileDialog.DELETE -> {
            val target = selected
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Delete ${target?.displayLabel.orEmpty()}?") }, text = { Text("This permanently deletes this ${if (target?.isDirectory == true) "folder and its contents" else "file"}. This cannot be undone.") }, confirmButton = { TextButton(onClick = { dialog = null; target?.let(model::delete) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        FileDialog.COPY, FileDialog.MOVE -> {
            val source = selected
            val roots = state.roots
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == FileDialog.COPY) "Copy to" else "Move to") }, text = {
                Text("Choose a destination folder using Android's secure folder picker. Provider permissions are retained for this operation.")
            }, confirmButton = { TextButton(enabled = source != null && !state.busy, onClick = {
                val node = source; val move = dialog == FileDialog.MOVE; dialog = null
                if (node != null) { destinationMove = move; destinationFolderPicker.launch(null) }
            }) { Text("Choose folder") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        FileDialog.CONFIRM_MOVE -> {
            val source = selected
            val target = destinationTarget
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Move ${source?.displayLabel.orEmpty()}?") }, text = {
                Text("This removes the original item from its current folder and moves it to ${target?.displayLabel.orEmpty()}.")
            }, confirmButton = { TextButton(enabled = source != null && target != null && !state.busy, onClick = {
                dialog = null
                if (source != null && target != null) model.moveTo(source, target)
            }) { Text("Move") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        null -> Unit
    }
    state.itemDetails?.let { details ->
        AlertDialog(onDismissRequest = model::clearDetails, title = { Text(details.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(details.kind)
                details.mimeType?.let { Text("Type · $it", style = MaterialTheme.typography.bodySmall) }
                details.sizeBytes?.let { Text("Size · ${formatBytes(it)}", style = MaterialTheme.typography.bodySmall) }
                details.modifiedMillis?.let { Text("Modified · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall) }
                Text("Location · ${details.location}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }, confirmButton = { TextButton(onClick = model::clearDetails) { Text("Done") } })
    }
}

private enum class FileManagerSortMode(val requiresMetadata: Boolean) {
    NAME_ASCENDING(false),
    NAME_DESCENDING(false),
    NEWEST_FIRST(true),
    OLDEST_FIRST(true),
    LARGEST_FIRST(true),
    SMALLEST_FIRST(true),
}

/** Missing provider metadata sorts after known values in either direction. */
private fun compareNullableMetric(left: Long?, right: Long?, descending: Boolean): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    descending -> right.compareTo(left)
    else -> left.compareTo(right)
}

private enum class FileDialog { CREATE, RENAME, DELETE, DELETE_SELECTION, COPY, MOVE, CONFIRM_MOVE }

private fun isArchiveNode(node: FileSystemNode): Boolean = node.displayLabel.substringAfterLast('.', "").lowercase() in setOf("zip", "tar", "gz", "tgz", "tar.gz", "7z")

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun hasSharedStorageAccess(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager()
    val readGranted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    return readGranted && writeGranted
}

@Composable
private fun InlineFeedback(text: String, positive: Boolean) {
    Surface(color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer)
    }
}
