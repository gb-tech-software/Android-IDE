package dev.android.ide.ui.screen

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.android.ide.data.model.AppTheme
import dev.android.ide.data.model.EditorSettings
import dev.android.ide.data.model.VolumeKeyMode
import dev.android.ide.data.model.TerminalSettings
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.ui.components.EDITOR_TOOLBAR_ACTIONS
import dev.android.ide.ui.components.EditorToolbarGlyph
import dev.android.ide.ui.components.normalizeEditorToolbarOrder
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.IdeViewModel
import dev.android.ide.viewmodel.model.IdeUiState
import androidx.lifecycle.viewmodel.compose.viewModel

enum class SettingsCategory(val title: String, val description: String, val icon: ImageVector) {
    GENERAL("General", "App theme and interface-wide preferences", Icons.Default.Palette),
    EDITOR("Editor", "Editor appearance, code behavior, keyboard, and file tree", Icons.Default.Code),
    PROJECTS("Projects", "Project creation and storage preferences", Icons.Default.FolderOpen),
    CREDENTIALS("Credentials", "Secure sign-in data", Icons.Default.Lock),
    SECURITY("Security", "Storage access, Android permissions, and protected data", Icons.Default.Lock),
    TERMINAL("Terminal", "Terminal runtime and session preferences", Icons.Default.Terminal),
    BROWSER("Browser", "Browser preview and web-project preferences", Icons.Default.Language),
}

@Composable
fun SettingsScreen(
    uiState: IdeUiState,
    ideViewModel: IdeViewModel,
    applicationTheme: AppTheme,
    onApplicationThemeChanged: (AppTheme) -> Unit,
    terminalSettings: TerminalSettings,
    onTerminalSettingsChanged: (TerminalSettings) -> Unit,
    onNavigationIconClick: (() -> Unit)? = null,
    scrollToSection: String? = null,
    onScrollConsumed: () -> Unit = {},
) {
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = selectedCategoryName?.let { name ->
        SettingsCategory.values().firstOrNull { it.name == name }
    }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategoryName = null
    }

    LaunchedEffect(scrollToSection) {
        val requested = scrollToSection ?: return@LaunchedEffect
        val category = when (requested) {
            "App Theme", "UI Font Size" -> SettingsCategory.GENERAL
            "Editor Theme", "Editor", "Controls", "File Tree" -> SettingsCategory.EDITOR
            "Project Storage" -> SettingsCategory.PROJECTS
            "Permissions", "Folder Access", "Android App Permissions" -> SettingsCategory.SECURITY
            "Credentials", "Manage Credentials" -> SettingsCategory.CREDENTIALS
            "Terminal" -> SettingsCategory.TERMINAL
            "Browser" -> SettingsCategory.BROWSER
            else -> null
        }
        if (category != null) selectedCategoryName = category.name
        onScrollConsumed()
    }

    val colors = LocalIdeColors.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedCategory?.title ?: "Settings", color = colors.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = { onNavigationIconClick?.invoke() }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open sidebar", tint = colors.primary)
                    }
                },
                actions = {
                    if (selectedCategory != null) {
                        IconButton(onClick = { selectedCategoryName = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to settings", tint = colors.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
        containerColor = colors.background,
    ) { innerPadding ->
        if (selectedCategory == null) {
            SettingsCategoryList(
                modifier = Modifier.padding(innerPadding),
                onCategorySelected = { selectedCategoryName = it.name },
            )
        } else {
            SettingsCategoryContent(
                category = selectedCategory,
                uiState = uiState,
                ideViewModel = ideViewModel,
                applicationTheme = applicationTheme,
                onApplicationThemeChanged = onApplicationThemeChanged,
                terminalSettings = terminalSettings,
                onTerminalSettingsChanged = onTerminalSettingsChanged,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SettingsCategoryList(
    modifier: Modifier,
    onCategorySelected: (SettingsCategory) -> Unit,
) {
    val colors = LocalIdeColors.current
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Choose a settings category",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(SettingsCategory.values().toList()) { category ->
            Card(
                onClick = { onCategorySelected(category) },
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(category.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category.title, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(category.description, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryContent(
    category: SettingsCategory,
    uiState: IdeUiState,
    ideViewModel: IdeViewModel,
    applicationTheme: AppTheme,
    onApplicationThemeChanged: (AppTheme) -> Unit,
    terminalSettings: TerminalSettings,
    onTerminalSettingsChanged: (TerminalSettings) -> Unit,
    modifier: Modifier,
) {
    val s = uiState.editorSettings
    val colors = LocalIdeColors.current
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(category.description, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        when (category) {
            SettingsCategory.GENERAL -> item { GeneralSettings(applicationTheme, onApplicationThemeChanged, s, ideViewModel) }
            SettingsCategory.EDITOR -> item { EditorSettingsContent(uiState, s, ideViewModel) }
            SettingsCategory.PROJECTS -> item { ProjectsSettingsContent() }
            SettingsCategory.TERMINAL -> item { TerminalSettingsContent(terminalSettings, onTerminalSettingsChanged) }
            SettingsCategory.BROWSER -> item { BrowserSettingsContent() }
            SettingsCategory.CREDENTIALS -> item { DomainPlaceholder(category) }
            SettingsCategory.SECURITY -> item { StorageAccessSettings() }
        }
    }
}

@Composable
private fun StorageAccessSettings() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var grantedCount by rememberSaveable {
        mutableStateOf(resolver.persistedUriPermissions.count { it.isReadPermission || it.isWritePermission })
    }
    var permissionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.count { it.value }
        permissionMessage = if (granted == results.size) "Requested Android permissions granted" else "$granted of ${results.size} requested Android permissions granted"
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val result = runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            result.onSuccess {
                grantedCount = resolver.persistedUriPermissions.count { it.isReadPermission || it.isWritePermission }
                permissionMessage = "Folder access saved"
            }.onFailure {
                permissionMessage = "Android did not grant access to that folder"
            }
        } else {
            permissionMessage = "Folder access was not changed"
        }
    }
    SettingsCard {
        Text("Project folder access", style = MaterialTheme.typography.titleSmall)
        Text(
            "Android IDE uses Android’s folder access permission for project files. Grant access to a folder when Android asks, and the permission remains available after the app is reopened.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalIdeColors.current.textSecondary,
        )
        Text("$grantedCount saved folder permission(s)", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Grant folder access") }
        permissionMessage?.let { Text(it, color = LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
    SettingsCard {
        Text("Android app permissions", style = MaterialTheme.typography.titleSmall)
        Text(
            "Request notifications on Android 13+ and legacy storage access on older Android versions. Modern project access uses the folder picker above.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalIdeColors.current.textSecondary,
        )
        OutlinedButton(onClick = {
            val requested = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (requested.isEmpty()) permissionMessage = "No additional runtime permissions are required on this Android version"
            else androidPermissionLauncher.launch(requested.toTypedArray())
        }) { Text("Request available permissions") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            })
        }) { Text("Open Android app settings") }
    }
}

@Composable
private fun GeneralSettings(applicationTheme: AppTheme, onApplicationThemeChanged: (AppTheme) -> Unit, s: EditorSettings, ideViewModel: IdeViewModel) {
    SettingsCard {
        Text("App theme", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            AppThemeOption("Dark", AppTheme.DARK, applicationTheme, onApplicationThemeChanged)
            AppThemeOption("Light", AppTheme.LIGHT, applicationTheme, onApplicationThemeChanged)
            AppThemeOption("Follow device", AppTheme.DEVICE, applicationTheme, onApplicationThemeChanged)
        }
    }
    SettingsCard {
        Text("Interface text size", style = MaterialTheme.typography.titleSmall)
        Text("Affects menus, file lists, dialogs, and other interface text.", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
        val pct = (s.uiFontScale * 100f + 0.5f).toInt()
        Text("$pct%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.uiFontScale,
            onValueChange = { value ->
                val snapped = (value / EditorSettings.UI_FONT_SCALE_STEP).toInt() * EditorSettings.UI_FONT_SCALE_STEP
                ideViewModel.setEditorSettings(s.copy(uiFontScale = snapped.coerceIn(EditorSettings.UI_FONT_SCALE_MIN, EditorSettings.UI_FONT_SCALE_MAX)))
            },
            valueRange = EditorSettings.UI_FONT_SCALE_MIN..EditorSettings.UI_FONT_SCALE_MAX,
            steps = ((EditorSettings.UI_FONT_SCALE_MAX - EditorSettings.UI_FONT_SCALE_MIN) / EditorSettings.UI_FONT_SCALE_STEP).toInt() - 1,
        )
        TextButton(onClick = { ideViewModel.setEditorSettings(s.copy(uiFontScale = 1f)) }, enabled = s.uiFontScale != 1f) { Text("Reset") }
    }
}

@Composable
private fun EditorSettingsContent(uiState: IdeUiState, s: EditorSettings, ideViewModel: IdeViewModel) {
    val colors = LocalIdeColors.current
    SettingsCard {
        Text("Editor appearance", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            EditorThemeOption("Dark", "dark", s.editorTheme, s, ideViewModel)
            EditorThemeOption("Light", "light", s.editorTheme, s, ideViewModel)
            EditorThemeOption("Follow application", "application", s.editorTheme, s, ideViewModel)
            EditorThemeOption("Follow device", "device", s.editorTheme, s, ideViewModel)
        }
        HorizontalDivider(color = colors.separator)
        SettingStepper("Code font size", "${s.fontSize} sp", {
            if (s.fontSize > 10) ideViewModel.setEditorSettings(s.copy(fontSize = s.fontSize - 1))
        }, {
            if (s.fontSize < 28) ideViewModel.setEditorSettings(s.copy(fontSize = s.fontSize + 1))
        })
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Document information row", "Show line, column, spacing, language, and encoding below the editor.", s.showStatusBar, { ideViewModel.setEditorSettings(s.copy(showStatusBar = it)) })
    }
    SettingsCard {
        Text("Editing behavior", style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(color = colors.separator)
        Text("Tab size", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(2, 4, 8).forEach { size -> FilterChip(s.tabSize == size, { ideViewModel.setEditorSettings(s.copy(tabSize = size)) }, label = { Text("$size") }) } }
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Word wrap", "Wrap long lines inside the editor.", s.wordWrap, { ideViewModel.setEditorSettings(s.copy(wordWrap = it)) })
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Line numbers", "Show line numbers beside the code.", s.lineNumbers, { ideViewModel.setEditorSettings(s.copy(lineNumbers = it)) })
        HorizontalDivider(color = colors.separator)
        Text("Whitespace", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("None" to "none", "Selection" to "selection", "All" to "all").forEach { (label, value) -> FilterChip(s.renderWhitespace == value, { ideViewModel.setEditorSettings(s.copy(renderWhitespace = value)) }, label = { Text(label) }) } }
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Minimap", "Show a code overview at the edge of the editor.", s.minimapEnabled, { ideViewModel.setEditorSettings(s.copy(minimapEnabled = it)) })
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Scroll past the last line", "Allow the last line to sit at the centre of the view.", s.scrollBeyondLastLine, { ideViewModel.setEditorSettings(s.copy(scrollBeyondLastLine = it)) })
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Bracket pair colors", "Color nested bracket levels differently.", s.bracketPairColorization, { ideViewModel.setEditorSettings(s.copy(bracketPairColorization = it)) })
        HorizontalDivider(color = colors.separator)
        ChoiceSetting("Cursor style", listOf("Line" to "line", "Block" to "block", "Underline" to "underline"), s.cursorStyle) { ideViewModel.setEditorSettings(s.copy(cursorStyle = it)) }
        HorizontalDivider(color = colors.separator)
        ChoiceSetting("Auto-close brackets", listOf("Always" to "always", "Smart" to "languageDefined", "Never" to "never"), s.autoClosingBrackets) { ideViewModel.setEditorSettings(s.copy(autoClosingBrackets = it)) }
        HorizontalDivider(color = colors.separator)
        VisibilitySettingRow("Auto save", "Save changes shortly after editing.", s.autoSave, { ideViewModel.setEditorSettings(s.copy(autoSave = it)) })
    }
    SettingsCard {
        Text("Language intelligence", style = MaterialTheme.typography.titleSmall)
        VisibilitySettingRow(
            "Enable language servers",
            "Optional pinned TypeScript, Python, HTML, CSS, and JSON tools are installed only when enabled and internet is available.",
            s.languageServerEnabled,
            { ideViewModel.setEditorSettings(s.copy(languageServerEnabled = it)) },
        )
        if (s.languageServerEnabled) {
            uiState.languageServerPackageSetupMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
            }
            if (uiState.languageServerPackageSetupCanRetry) {
                OutlinedButton(onClick = ideViewModel::retryLanguageServerPackageInstallation) {
                    Text("Retry package setup")
                }
            }
        }
    }
    KeyboardSettingsContent(uiState, s, ideViewModel)
    FileTreeSettingsContent(s, ideViewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSettingsContent(uiState: IdeUiState, s: EditorSettings, ideViewModel: IdeViewModel) {
    var toolbarOrderSheetOpen by rememberSaveable { mutableStateOf(false) }
    var draftOrder by rememberSaveable { mutableStateOf(normalizeEditorToolbarOrder(s.keyboardToolbarOrder)) }
    val actionById = EDITOR_TOOLBAR_ACTIONS.associateBy { it.id }

    SettingsCard {
        Text("Keyboard and input", style = MaterialTheme.typography.titleSmall)
        VisibilitySettingRow(
            "Keyboard toolbar",
            "Show cursor, selection, and editing controls above the keyboard.",
            s.showKeyboardToolbar,
            { ideViewModel.setEditorSettings(s.copy(showKeyboardToolbar = it)) },
        )
        HorizontalDivider()
        VisibilitySettingRow(
            "Symbol bar",
            "Show one-tap common character shortcuts above the keyboard.",
            s.showSymbolBar,
            { ideViewModel.setEditorSettings(s.copy(showSymbolBar = it)) },
        )
        HorizontalDivider()
        Text("Volume keys in editor", style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            VolumeKeyOption("Cursor horizontal (move cursor left/right)", VolumeKeyMode.HORIZONTAL, uiState.volumeKeyMode, ideViewModel)
            VolumeKeyOption("Cursor vertical (move cursor up/down)", VolumeKeyMode.VERTICAL, uiState.volumeKeyMode, ideViewModel)
            VolumeKeyOption("Disabled (controls system volume)", VolumeKeyMode.DISABLED, uiState.volumeKeyMode, ideViewModel)
        }
        HorizontalDivider()
        OutlinedButton(onClick = {
            draftOrder = normalizeEditorToolbarOrder(s.keyboardToolbarOrder)
            toolbarOrderSheetOpen = true
        }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("Customize toolbar shortcuts", Modifier.weight(1f).padding(start = 10.dp))
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (toolbarOrderSheetOpen) {
        ModalBottomSheet(onDismissRequest = { toolbarOrderSheetOpen = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Customize keyboard toolbar", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Language-server shortcuts appear only when the active server advertises that feature and can access this file's language and project storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalIdeColors.current.textSecondary,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    item {
                        Text("Enabled shortcuts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                    itemsIndexed(draftOrder, key = { _, id -> "enabled-$id" }) { index, actionId ->
                        val action = actionById[actionId] ?: return@itemsIndexed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(draftOrder) {
                                    var currentIndex = index
                                    detectDragGesturesAfterLongPress(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val target = when {
                                                dragAmount.y > 18f -> currentIndex + 1
                                                dragAmount.y < -18f -> currentIndex - 1
                                                else -> currentIndex
                                            }.coerceIn(0, draftOrder.lastIndex)
                                            if (target != currentIndex) {
                                                val reordered = draftOrder.toMutableList()
                                                reordered[currentIndex] = reordered[target].also { reordered[target] = reordered[currentIndex] }
                                                draftOrder = reordered
                                                currentIndex = target
                                            }
                                        },
                                        onDragEnd = {},
                                        onDragCancel = {},
                                    )
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EditorToolbarGlyph("dragHandle", LocalIdeColors.current.textSecondary, Modifier.size(22.dp))
                            EditorToolbarGlyph(action.id, LocalIdeColors.current.textSecondary, Modifier.padding(start = 8.dp).size(22.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(action.label)
                                Text("Enabled · position ${index + 1}", style = MaterialTheme.typography.labelSmall, color = LocalIdeColors.current.textSecondary)
                                Text(action.description, style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
                                listOfNotNull(action.shortcut, action.requiredServerFeature?.let { "LSP: ${it.name.lowercase().replace('_', ' ')}" })
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(" · ")
                                    ?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LocalIdeColors.current.textSecondary) }
                            }
                            Switch(
                                checked = true,
                                onCheckedChange = { draftOrder = draftOrder - action.id },
                                modifier = Modifier.semantics { contentDescription = "Disable ${action.label}" },
                            )
                        }
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Available shortcuts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    items(
                        EDITOR_TOOLBAR_ACTIONS.filterNot { draftOrder.contains(it.id) },
                        key = { "available-${it.id}" },
                    ) { action ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EditorToolbarGlyph(action.id, LocalIdeColors.current.textSecondary, Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(action.label)
                                Text("Disabled · position not assigned", style = MaterialTheme.typography.labelSmall, color = LocalIdeColors.current.textSecondary)
                                Text(action.description, style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
                                listOfNotNull(action.shortcut, action.requiredServerFeature?.let { "LSP: ${it.name.lowercase().replace('_', ' ')}" })
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(" · ")
                                    ?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LocalIdeColors.current.textSecondary) }
                            }
                            Switch(
                                checked = false,
                                onCheckedChange = { draftOrder = draftOrder + action.id },
                                modifier = Modifier.semantics { contentDescription = "Enable ${action.label}" },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { draftOrder = EditorSettings.DEFAULT_KEYBOARD_TOOLBAR_ORDER }) { Text("Reset") }
                    TextButton(onClick = { toolbarOrderSheetOpen = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        ideViewModel.setEditorSettings(s.copy(keyboardToolbarOrder = normalizeEditorToolbarOrder(draftOrder)))
                        toolbarOrderSheetOpen = false
                    }) { Text("Done") }
                }
            }
        }
    }
}


@Composable
private fun FileTreeSettingsContent(s: EditorSettings, ideViewModel: IdeViewModel) {
    SettingsCard {
        Text("File tree", style = MaterialTheme.typography.titleSmall)
        VisibilitySettingRow("Hide .git folder", "Keep repository internals out of the file tree.", s.hideGitFolder, { ideViewModel.setEditorSettings(s.copy(hideGitFolder = it)) })
        HorizontalDivider()
        VisibilitySettingRow("Hide workspace metadata", "Keep Android IDE workspace metadata out of the file tree.", s.hideProjectMetadataFolder, { ideViewModel.setEditorSettings(s.copy(hideProjectMetadataFolder = it)) })
    }
}

@Composable
private fun ProjectsSettingsContent() {
    SettingsCard {
        Text("Storage locations are chosen during project creation.", style = MaterialTheme.typography.bodyMedium)
        Text("The editor does not keep a hidden default path. This avoids creating projects in an unexpected folder.", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
    }
}

@Composable
private fun TerminalSettingsContent(settings: TerminalSettings, onSettingsChanged: (TerminalSettings) -> Unit) {
    SettingsCard {
        Text("Terminal appearance", style = MaterialTheme.typography.titleSmall)
        Text("Choose how the terminal resolves its colors. This setting is independent from the editor and browser.", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
        Column(Modifier.selectableGroup()) {
            listOf("Dark" to "dark", "Light" to "light", "Follow application" to "application", "Follow device" to "device").forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().selectable(selected = settings.theme == value, role = Role.RadioButton) { onSettingsChanged(settings.copy(theme = value)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.theme == value, onClick = null)
                    Text(label)
                }
            }
        }
        HorizontalDivider()
        SettingStepper("Terminal text size", "${settings.textSize} sp", {
            onSettingsChanged(settings.copy(textSize = settings.textSize - 1))
        }, {
            onSettingsChanged(settings.copy(textSize = settings.textSize + 1))
        })
    }
    SettingsCard {
        Text("Terminal shortcut toolbar", style = MaterialTheme.typography.titleSmall)
        VisibilitySettingRow("Show shortcut toolbar", "Show terminal control and navigation shortcuts below the terminal.", settings.shortcutToolbarEnabled) {
            onSettingsChanged(settings.copy(shortcutToolbarEnabled = it))
        }
    }
}

@Composable
private fun BrowserSettingsContent() {
    val browser: BrowserViewModel = viewModel()
    val state by browser.uiState.collectAsState()
    val context = LocalContext.current
    var searchSheetOpen by rememberSaveable { mutableStateOf(false) }
    var homeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var themeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sitePermissionsOpen by rememberSaveable { mutableStateOf(false) }
    var searchDraft by rememberSaveable(state.settings.customSearchUrl) { mutableStateOf(state.settings.customSearchUrl) }
    var homeDraft by rememberSaveable(state.settings.homePage) { mutableStateOf(state.settings.homePage) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onSuccess {
            browser.setDownloadFolder(uri.toString())
            message = "Download folder saved"
        }.onFailure { message = "Android did not grant access to that folder" }
    }
    val browserRoleManager = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(RoleManager::class.java) else null
    }
    val browserRoleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        message = if (result.resultCode == Activity.RESULT_OK) "Android IDE is now the selected browser" else "Browser role was not changed"
    }
    if (sitePermissionsOpen) {
        BackHandler { sitePermissionsOpen = false }
        SitePermissionsSettingsScreen(
            state = state,
            onBack = { sitePermissionsOpen = false },
            onRevoke = browser::revokeSitePermission,
        )
        return
    }
    val searchLabels = mapOf("google" to "Google", "bing" to "Bing", "duckduckgo" to "DuckDuckGo", "brave" to "Brave Search", "startpage" to "Startpage", "custom" to "Custom")
    SettingsCard {
        Text("Search engine", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().clickable { searchSheetOpen = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(searchLabels[state.settings.searchEngine] ?: "Custom")
                Text("Tap to choose or configure", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
            }
            Text("Change", color = LocalIdeColors.current.textSecondary)
        }
    }
    SettingsCard {
        Text("Home page", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().clickable { homeSheetOpen = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.settings.homePage.ifBlank { "Blank tab" }, maxLines = 1)
                Text("Tap to edit", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
            }
            Text("Change", color = LocalIdeColors.current.textSecondary)
        }
    }
    SettingsCard {
        Text("Downloads", style = MaterialTheme.typography.titleSmall)
        Text(state.settings.downloadFolder ?: "Android Downloads (default)", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
            TextButton(onClick = { browser.setDownloadFolder(null); message = "Default Downloads folder restored" }) { Text("Reset") }
        }
        Text("Existing records are checked when Downloads opens. Duplicate names require confirmation.", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
    }
    SettingsCard {
        Text("Appearance and browsing", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().clickable { themeSheetOpen = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mapOf("system" to "Follow application", "application" to "Follow application", "device" to "Follow device", "light" to "Light", "dark" to "Dark")[state.settings.theme] ?: "Follow application")
                Text("Browser theme", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
            }
            Text("Change", color = LocalIdeColors.current.textSecondary)
        }
        VisibilitySettingRow("Desktop site", "Reload the current tab using the desktop user-agent.", state.settings.desktopSiteDefault, browser::setDesktopSiteDefault)
    }
    SettingsCard {
        Text("Default browser", style = MaterialTheme.typography.titleSmall)
        val roleAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            browserRoleManager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true
        val roleHeld = roleAvailable && browserRoleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        Text(
            if (roleHeld) "Android IDE is selected as the browser" else "Request Android’s default-browser role",
            style = MaterialTheme.typography.bodySmall,
            color = LocalIdeColors.current.textSecondary,
        )
        if (roleAvailable && !roleHeld) {
            OutlinedButton(onClick = {
                browserRoleManager?.let { browserRoleRequest.launch(it.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }
            }) { Text("Set as default browser") }
        }
    }
    SettingsCard {
        Text("Developer tools", style = MaterialTheme.typography.titleSmall)
        VisibilitySettingRow("Enable developer tools", "Allow the current console, elements, and network panels.", state.settings.developerToolsEnabled, browser::setDeveloperToolsEnabled)
    }
    SettingsCard {
        Text("Site permissions", style = MaterialTheme.typography.titleSmall)
        Text(
            "Website requests offer one-time or saved choices. Only saved choices appear here; Android may also show its own device-level options such as while using the app or limited access.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalIdeColors.current.textSecondary,
        )
        OutlinedButton(onClick = { sitePermissionsOpen = true }) { Text("Manage site permissions") }
    }
    SettingsCard {
        Text("Browsing data", style = MaterialTheme.typography.titleSmall)
        Text("Clear site data, browser history, and download records. Downloaded files in the selected folder are not deleted.", style = MaterialTheme.typography.bodySmall, color = LocalIdeColors.current.textSecondary)
        OutlinedButton(onClick = browser::clearBrowserData, enabled = !state.clearingBrowserData) {
            Text(if (state.clearingBrowserData) "Clearing browser data…" else "Clear browser data")
        }
        state.browserDataClearMessage?.let {
            Text(it, color = if (it.startsWith("Could not")) MaterialTheme.colorScheme.error else LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
    message?.let { Text(it, color = LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall) }

    if (searchSheetOpen) ModalBottomSheet(onDismissRequest = { searchSheetOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search engine", style = MaterialTheme.typography.titleLarge)
            searchLabels.filterKeys { it != "custom" }.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { browser.setSearchEngine(id); searchSheetOpen = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.settings.searchEngine == id, onClick = null)
                    Text(label)
                }
            }
            OutlinedTextField(value = searchDraft, onValueChange = { searchDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Custom URL template; use %s") })
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { browser.setSearchEngine("custom"); browser.setCustomSearchUrl(searchDraft); searchSheetOpen = false; message = "Custom search provider saved" }) { Text("Save custom") }
            }
        }
    }
    if (homeSheetOpen) ModalBottomSheet(onDismissRequest = { homeSheetOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Home page", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = homeDraft, onValueChange = { homeDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL; blank opens a blank tab") })
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { browser.setHomePage(homeDraft); homeSheetOpen = false; message = "Home page saved" }) { Text("Save") }
            }
        }
    }
    if (themeSheetOpen) ModalBottomSheet(onDismissRequest = { themeSheetOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Browser theme", style = MaterialTheme.typography.titleLarge)
            listOf("dark" to "Dark", "light" to "Light", "application" to "Follow application", "device" to "Follow device").forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { browser.setTheme(id); themeSheetOpen = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.settings.theme == id, onClick = null)
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun SitePermissionsSettingsScreen(
    state: dev.android.ide.browser.BrowserUiState,
    onBack: () -> Unit,
    onRevoke: (String, String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text("Site permissions", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            Text(
                "Saved website decisions are listed here. One-time decisions are discarded after the request. Android may separately show device-level choices such as while using the app or limited access.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalIdeColors.current.textSecondary,
            )
        }
        if (state.sitePermissions.isEmpty()) {
            item { Text("No site permissions have been saved.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            state.sitePermissions.groupBy { it.origin }.forEach { (origin, permissions) ->
                item(key = origin) {
                    SettingsCard {
                        Text(origin, style = MaterialTheme.typography.titleSmall)
                        permissions.forEach { permission ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${permission.permission}: ${permission.decision.name.lowercase().replaceFirstChar(Char::uppercase)}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalIdeColors.current.textSecondary,
                                )
                                TextButton(onClick = { onRevoke(permission.origin, permission.permission) }) { Text("Revoke") }
                            }
                        }
                        TextButton(onClick = { onRevoke(origin, null) }) { Text("Revoke all for site") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainPlaceholder(category: SettingsCategory) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(category.icon, contentDescription = null, tint = LocalIdeColors.current.primary, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(category.title, color = LocalIdeColors.current.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(category.description, color = LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text(
            text = "Settings for this area are not configured yet.",
            color = LocalIdeColors.current.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = LocalIdeColors.current.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingStepper(title: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(value, color = LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDecrease, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
            OutlinedButton(onClick = onIncrease, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
    }
}

@Composable
private fun VisibilitySettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().selectable(selected = checked, role = Role.Switch, onClick = { onCheckedChange(!checked) })) {
        Column(Modifier.weight(1f)) { Text(title, color = LocalIdeColors.current.textPrimary); Text(description, color = LocalIdeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceSetting(title: String, choices: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { choices.forEach { (label, value) -> FilterChip(selected == value, { onSelected(value) }, label = { Text(label) }) } }
    }
}

@Composable
private fun AppThemeOption(label: String, theme: AppTheme, current: AppTheme, onThemeChanged: (AppTheme) -> Unit) {
    val selected = theme == current
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { onThemeChanged(theme) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

@Composable
private fun EditorThemeOption(label: String, themeKey: String, currentKey: String, settings: EditorSettings, ideViewModel: IdeViewModel) {
    val selected = themeKey == currentKey
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { ideViewModel.setEditorSettings(settings.copy(editorTheme = themeKey)) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

@Composable
private fun VolumeKeyOption(label: String, mode: VolumeKeyMode, current: VolumeKeyMode, ideViewModel: IdeViewModel) {
    val selected = mode == current
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { ideViewModel.setVolumeKeyMode(mode) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
