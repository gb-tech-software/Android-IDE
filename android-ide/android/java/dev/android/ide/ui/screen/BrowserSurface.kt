package dev.android.ide.ui.screen

import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.StatFs
import android.widget.MediaController
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.TextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import dev.android.ide.app.BrowserNavigationRequest
import dev.android.ide.browser.BrowserDownload
import dev.android.ide.browser.BrowserDownloadStatus
import dev.android.ide.browser.DuplicateDownloadPrompt
import dev.android.ide.browser.BrowserWebsitePromptAnswer
import dev.android.ide.browser.BrowserWebsitePromptKind
import dev.android.ide.browser.BrowserWebsitePromptRequest
import dev.android.ide.browser.BrowserContextKind
import dev.android.ide.browser.BrowserContextMenu
import dev.android.ide.browser.BrowserNavigationFailure
import dev.android.ide.browser.BrowserTabUi
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.browser.BrowserPermissionAnswer
import dev.android.ide.data.ThemeResolver
import dev.android.ide.ui.theme.AndroidIDETheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSurface(
    browser: BrowserViewModel,
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onApplicationBack: () -> Unit = {},
    initialNavigation: BrowserNavigationRequest? = null,
    onInitialNavigationConsumed: (String) -> Unit = {},
    floating: Boolean = false,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val registerScrollListener = remember(browser) { browser::setScrollListener }
    val themeResolver = remember { ThemeResolver(context) }
    val state by browser.uiState.collectAsState()
    var activeFilePromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var fileChooserPromptId by remember { mutableStateOf<String?>(null) }
    var pendingCameraPromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val singleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val promptId = activeFilePromptId
        activeFilePromptId = null
        val prompt = browser.uiState.value.websitePrompt
        if (promptId != null && prompt?.id == promptId && prompt.kind == BrowserWebsitePromptKind.FILE) {
            browser.answerWebsitePrompt(
                promptId,
                BrowserWebsitePromptAnswer(accepted = uri != null, selectedFileUris = listOfNotNull(uri?.toString())),
            )
        }
    }
    val multipleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val promptId = activeFilePromptId
        activeFilePromptId = null
        val prompt = browser.uiState.value.websitePrompt
        if (promptId != null && prompt?.id == promptId && prompt.kind == BrowserWebsitePromptKind.FILE) {
            browser.answerWebsitePrompt(
                promptId,
                BrowserWebsitePromptAnswer(accepted = uris.isNotEmpty(), selectedFileUris = uris.map(Uri::toString)),
            )
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val capturePromptId = pendingCameraPromptId
        val captureUri = pendingCameraUri?.let(Uri::parse)
        val captureFile = captureUri?.lastPathSegment?.let { name ->
            File(File(context.cacheDir, "browser_form_captures"), name)
        }
        pendingCameraPromptId = null
        pendingCameraUri = null
        val promptId = activeFilePromptId
        activeFilePromptId = null
        val prompt = browser.uiState.value.websitePrompt
        if (captured && captureUri != null && capturePromptId != null && promptId == capturePromptId && prompt?.id == capturePromptId) {
            browser.answerWebsitePrompt(
                capturePromptId,
                BrowserWebsitePromptAnswer(
                    accepted = true,
                    selectedFileUris = listOf(captureUri.toString()),
                    temporaryFileUris = listOf(captureUri.toString()),
                ),
            )
        } else {
            captureFile?.delete()
            if (promptId != null && prompt?.id == promptId) {
                browser.answerWebsitePrompt(promptId, BrowserWebsitePromptAnswer(accepted = false))
            }
        }
    }
    var addressValue by remember {
        mutableStateOf(TextFieldValue(state.address, TextRange(state.address.length)))
    }
    var debouncedSuggestionQuery by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var addressSuggestionsOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var urlRowVisible by remember { mutableStateOf(true) }
    var previousScrollY by remember { mutableIntStateOf(0) }
    var tabSearchQuery by remember { mutableStateOf("") }
    val visibleBrowserSurface = remember { mutableStateOf<BrowserSurfaceBinding?>(null) }
    val focusManager = LocalFocusManager.current

    DisposableEffect(browser, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) browser.cancelPermissionPrompts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            browser.cancelPermissionPrompts()
            browser.dismissWebsitePrompt()
            visibleBrowserSurface.value = null
        }
    }

    LaunchedEffect(initialNavigation?.id) {
        val request = initialNavigation ?: return@LaunchedEffect
        browser.navigateRequest(request.url, request.openInNewTab, request.reuseKey)
        onInitialNavigationConsumed(request.id)
    }

    LaunchedEffect(state.address, addressFocused) {
        if (!addressFocused) addressValue = TextFieldValue(state.address, TextRange(state.address.length))
    }
    LaunchedEffect(state.selectedTabId) {
        // Scroll visibility belongs to the selected tab's surface. Do not carry
        // a hidden address row from one tab into another or back into a tab that
        // was temporarily detached while it was not selected.
        previousScrollY = 0
        urlRowVisible = true
    }
    LaunchedEffect(addressValue.text, addressFocused, addressSuggestionsOpen) {
        if (!addressFocused || !addressSuggestionsOpen) {
            debouncedSuggestionQuery = ""
        } else {
            val query = addressValue.text.trim()
            delay(150)
            debouncedSuggestionQuery = query
        }
    }
    LaunchedEffect(state.websitePrompt?.id) {
        val prompt = state.websitePrompt
        if (prompt?.kind == BrowserWebsitePromptKind.FILE) {
            activeFilePromptId = prompt.id
            fileChooserPromptId = prompt.id
        } else {
            fileChooserPromptId = null
            activeFilePromptId = null
        }
    }
    BackHandler(enabled = floating || state.downloadsPageOpen || state.historyPageOpen || state.selectedTabId != null) {
        if (floating) {
            onDismiss()
        } else if (state.downloadsPageOpen) {
            browser.hideDownloadsPage()
        } else if (state.historyPageOpen) {
            browser.hideHistoryPage()
        } else if (addressFocused) {
            focusManager.clearFocus()
            addressValue = TextFieldValue(state.address, TextRange(state.address.length))
            addressFocused = false
            addressSuggestionsOpen = false
        } else {
            val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
            if (selectedTab?.canGoBack == true) browser.goBack() else onApplicationBack()
        }
    }

    AndroidIDETheme(resolvedTheme = themeResolver.getBrowserTheme(state.settings.theme)) {
        BrowserSurfaceContent(
            modifier = modifier,
            state = state,
            browser = browser,
            registerScrollListener = registerScrollListener,
            visibleBrowserSurface = visibleBrowserSurface,
            addressValue = addressValue,
            debouncedSuggestionQuery = debouncedSuggestionQuery,
            setAddressValue = { addressValue = it },
            addressFocused = addressFocused,
            setAddressFocused = { addressFocused = it },
            addressSuggestionsOpen = addressSuggestionsOpen,
            setAddressSuggestionsOpen = { addressSuggestionsOpen = it },
            clearHistoryEntry = browser::clearHistoryUrl,
            createTab = { browser.createTab() },
            openTabs = {
                state.selectedTabId?.let { browser.captureTabThumbnail(it, visibleBrowserSurface.value?.thumbnailView()) }
                tabsOpen = true
            },
            onOpenNavigation = if (floating) onDismiss else onOpenNavigation,
            onOpenSettings = onOpenSettings,
            floating = floating,
            onDismiss = onDismiss,
            tabSearchQuery = tabSearchQuery,
            setTabSearchQuery = { tabSearchQuery = it },
            setUrlRowVisible = { urlRowVisible = it },
            urlRowVisible = urlRowVisible,
            tabsOpen = tabsOpen,
            setTabsOpen = { tabsOpen = it },
            previousScrollY = previousScrollY,
            setPreviousScrollY = { previousScrollY = it },
        )
        val filePrompt = state.websitePrompt?.takeIf { it.kind == BrowserWebsitePromptKind.FILE }
        if (filePrompt != null && fileChooserPromptId == filePrompt.id) {
            BrowserFileSourceSheet(
                canCapture = supportsCameraCapture(filePrompt.mimeTypes),
                onDismiss = {
                    fileChooserPromptId = null
                    activeFilePromptId = null
                    browser.answerWebsitePrompt(filePrompt.id, BrowserWebsitePromptAnswer(accepted = false))
                },
                onCamera = {
                    fileChooserPromptId = null
                    activeFilePromptId = filePrompt.id
                    val directory = File(context.cacheDir, "browser_form_captures").apply { mkdirs() }
                    val file = runCatching { File.createTempFile("capture_", ".jpg", directory) }.getOrNull()
                    val target = file?.let { captureFile ->
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.browser.fileprovider",
                                captureFile,
                            )
                            BrowserCaptureTarget(captureFile, uri, filePrompt.id)
                        }.getOrElse {
                            captureFile.delete()
                            null
                        }
                    }
                    if (target == null) {
                        activeFilePromptId = null
                        browser.answerWebsitePrompt(filePrompt.id, BrowserWebsitePromptAnswer(accepted = false))
                    } else {
                        pendingCameraPromptId = target.promptId
                        pendingCameraUri = target.uri.toString()
                        runCatching { cameraPicker.launch(target.uri) }.onFailure {
                            pendingCameraPromptId = null
                            pendingCameraUri = null
                            activeFilePromptId = null
                            target.file.delete()
                            browser.answerWebsitePrompt(filePrompt.id, BrowserWebsitePromptAnswer(accepted = false))
                        }
                    }
                },
                onMediaPicker = {
                    fileChooserPromptId = null
                    activeFilePromptId = filePrompt.id
                    val mimeTypes = filePrompt.mimeTypes.filter(String::isNotBlank).toTypedArray().ifEmpty { arrayOf("*/*") }
                    runCatching {
                        if (filePrompt.multiple) multipleFilePicker.launch(mimeTypes)
                        else singleFilePicker.launch(mimeTypes)
                    }.onFailure {
                        activeFilePromptId = null
                        browser.answerWebsitePrompt(filePrompt.id, BrowserWebsitePromptAnswer(accepted = false))
                    }
                },
            )
        }
    }
}

/** Browser-owned presentation host for previewing content above another domain. */
@Composable
fun BrowserFloatingPanel(
    browser: BrowserViewModel,
    initialNavigation: BrowserNavigationRequest?,
    onInitialNavigationConsumed: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        BrowserSurface(
            browser = browser,
            onOpenNavigation = {},
            onOpenSettings = onOpenSettings,
            initialNavigation = initialNavigation,
            onInitialNavigationConsumed = onInitialNavigationConsumed,
            floating = true,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserSurfaceContent(
    modifier: Modifier,
    state: dev.android.ide.browser.BrowserUiState,
    browser: BrowserViewModel,
    registerScrollListener: (String, ((Int) -> Unit)?) -> Unit,
    visibleBrowserSurface: androidx.compose.runtime.MutableState<BrowserSurfaceBinding?>,
    addressValue: TextFieldValue,
    debouncedSuggestionQuery: String,
    setAddressValue: (TextFieldValue) -> Unit,
    addressFocused: Boolean,
    setAddressFocused: (Boolean) -> Unit,
    addressSuggestionsOpen: Boolean,
    setAddressSuggestionsOpen: (Boolean) -> Unit,
    clearHistoryEntry: (String) -> Unit,
    createTab: () -> Unit,
    openTabs: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit,
    floating: Boolean,
    onDismiss: () -> Unit,
    tabSearchQuery: String,
    setTabSearchQuery: (String) -> Unit,
    setUrlRowVisible: (Boolean) -> Unit,
    urlRowVisible: Boolean,
    tabsOpen: Boolean,
    setTabsOpen: (Boolean) -> Unit,
    previousScrollY: Int,
    setPreviousScrollY: (Int) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val activeDownloadCount = state.downloads.count { it.status == BrowserDownloadStatus.DOWNLOADING }
    LaunchedEffect(state.downloadFeedback) {
        state.downloadFeedback?.let { message ->
            snackbarHostState.showSnackbar(message)
            browser.clearDownloadFeedback()
        }
    }
    BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }
    fun closeDrawerThen(action: () -> Unit) {
        drawerScope.launch {
            drawerState.close()
            action()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BoxWithConstraints {
                val drawerWidth = minOf(maxWidth * 0.82f, 360.dp)
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth).fillMaxHeight(),
                    drawerShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val activeTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
                        val canGoBack = activeTab?.canGoBack == true
                        val canGoForward = activeTab?.canGoForward == true

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrowserDrawerQuickAction(
                                icon = Icons.Default.ArrowBack,
                                label = "Back",
                                enabled = canGoBack,
                                onClick = { closeDrawerThen(browser::goBack) },
                            )
                            BrowserDrawerQuickAction(
                                icon = Icons.Default.ArrowForward,
                                label = "Forward",
                                enabled = canGoForward,
                                onClick = { closeDrawerThen(browser::goForward) },
                            )
                            BrowserDrawerQuickAction(
                                icon = Icons.Default.Download,
                                label = "Downloads",
                                activeCount = activeDownloadCount,
                                onClick = { closeDrawerThen(browser::showDownloadsPage) },
                            )
                            BrowserDrawerQuickAction(
                                icon = if (state.loading) Icons.Default.Stop else Icons.Default.Refresh,
                                label = if (state.loading) "Stop loading" else "Refresh",
                                enabled = state.selectedTabId != null,
                                onClick = { closeDrawerThen { if (state.loading) browser.stop() else browser.reload() } },
                            )
                            BrowserDrawerQuickAction(
                                icon = Icons.Default.Settings,
                                label = "Browser settings",
                                onClick = { closeDrawerThen(onOpenSettings) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 8.dp))

                        Text(
                            activeTab?.host?.takeIf { it.isNotBlank() }
                                ?: activeTab?.title?.takeIf { it.isNotBlank() }
                                ?: "No page open",
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        val externalUrl = activeTab?.url?.takeIf { value ->
                            runCatching { Uri.parse(value).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
                        }
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                            label = { Text("Open in another app") },
                            selected = false,
                            onClick = {
                                if (externalUrl != null) closeDrawerThen {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
                                    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            label = { Text("New tab") },
                            selected = false,
                            onClick = { closeDrawerThen { browser.createTab() } },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        NavigationDrawerItem(
                            icon = { DownloadActivityIcon(activeDownloadCount) },
                            label = { Text(if (activeDownloadCount > 0) "Downloads · $activeDownloadCount active" else "Downloads") },
                            selected = false,
                            onClick = { closeDrawerThen(browser::showDownloadsPage) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text("History") },
                            selected = false,
                            onClick = { closeDrawerThen(browser::showHistoryPage) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                closeDrawerThen { browser.setDesktopSiteDefault(!state.settings.desktopSiteDefault) }
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.LaptopChromebook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text("Desktop site")
                                Text("Request desktop pages for browser tabs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = state.settings.desktopSiteDefault, onCheckedChange = { enabled -> closeDrawerThen { browser.setDesktopSiteDefault(enabled) } })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                closeDrawerThen {
                                    if (!state.settings.developerToolsEnabled) browser.setDeveloperToolsEnabled(true)
                                    browser.toggleDeveloperTools()
                                }
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Developer tools", modifier = Modifier.weight(1f).padding(start = 16.dp))
                            Switch(checked = state.developerToolsOpen, onCheckedChange = { open ->
                                if (open != state.developerToolsOpen) closeDrawerThen {
                                    if (open && !state.settings.developerToolsEnabled) browser.setDeveloperToolsEnabled(true)
                                    browser.toggleDeveloperTools()
                                }
                            })
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                }
            }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(modifier.fillMaxSize()) {
                Column(modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(when {
                    state.downloadsPageOpen -> "Downloads"
                    state.historyPageOpen -> "History"
                    else -> "Browser"
                }) },
                navigationIcon = {
                    IconButton(onClick = onOpenNavigation) {
                        Icon(
                            if (floating) Icons.Default.Close else Icons.Default.Menu,
                            if (floating) "Close floating browser" else "Open Android IDE navigation",
                        )
                    }
                },
                actions = {
                    if (state.downloadsPageOpen) {
                        IconButton(onClick = browser::reconcileDownloads) { Icon(Icons.Default.Refresh, "Refresh downloads") }
                    } else if (!state.historyPageOpen) {
                        IconButton(onClick = createTab) { Icon(Icons.Default.Add, "New tab") }
                        IconButton(onClick = browser::showDownloadsPage) {
                            DownloadActivityIcon(activeDownloadCount)
                        }
                        IconButton(onClick = openTabs) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(state.tabs.size.toString(), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.MoreVert, "Browser menu")
                        }
                    }
                },
            )
            if (state.downloadsPageOpen) {
                DownloadsScreen(
                    downloads = state.downloads,
                    browser = browser,
                    modifier = Modifier.weight(1f),
                )
            } else if (state.historyPageOpen) {
                HistoryScreen(
                    history = state.history,
                    browser = browser,
                    modifier = Modifier.weight(1f),
                )
            } else {
                AnimatedVisibility(
                    visible = urlRowVisible,
                    enter = slideInVertically(initialOffsetY = { -it }),
                    exit = slideOutVertically(targetOffsetY = { -it }),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = browser::goHome) { Icon(Icons.Default.Home, "Home page") }
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = addressValue,
                                    onValueChange = {
                                        setAddressValue(it)
                                        setAddressSuggestionsOpen(true)
                                    },
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                                        if (focusState.isFocused && !addressFocused) {
                                            setAddressFocused(true)
                                            setAddressSuggestionsOpen(true)
                                            // Keep the current page available as the first
                                            // suggestion, but give the user a truly empty
                                            // field for a new search or address.
                                            setAddressValue(TextFieldValue("", TextRange.Zero))
                                        } else if (!focusState.isFocused && addressFocused) {
                                            setAddressFocused(false)
                                            setAddressSuggestionsOpen(false)
                                            setAddressValue(TextFieldValue(state.address, TextRange(state.address.length)))
                                        }
                                    },
                                    singleLine = true,
                                    placeholder = { Text("Search or enter address") },
                                    leadingIcon = if (state.loading || !addressFocused) ({
                                        val transition = rememberInfiniteTransition(label = "browser-load-control")
                                        val flashingAlpha by transition.animateFloat(
                                            initialValue = 0.45f,
                                            targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(650),
                                                repeatMode = RepeatMode.Reverse,
                                            ),
                                            label = "browser-load-alpha",
                                        )
                                        IconButton(
                                            onClick = if (state.loading) browser::stop else browser::reload,
                                        ) {
                                            Icon(
                                                imageVector = if (state.loading) Icons.Default.Stop else Icons.Default.Refresh,
                                                contentDescription = if (state.loading) "Stop loading" else "Reload page",
                                                modifier = Modifier.alpha(if (state.loading) flashingAlpha else 1f),
                                            )
                                        }
                                    }) else null,
                                    shape = RoundedCornerShape(26.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        browser.navigate(addressValue.text)
                                        focusManager.clearFocus()
                                        setAddressFocused(false)
                                        setAddressSuggestionsOpen(false)
                                    }),
                                    trailingIcon = if (addressFocused && addressValue.text.isNotEmpty()) ({
                                        IconButton(onClick = { setAddressValue(TextFieldValue("", TextRange.Zero)) }) { Icon(Icons.Default.Close, "Clear address") }
                                    }) else null,
                                )
                            }
                            IconButton(onClick = browser::goBack, enabled = state.tabs.firstOrNull { it.id == state.selectedTabId }?.canGoBack == true) { Icon(Icons.Default.ArrowBack, "Back") }
                            IconButton(onClick = browser::goForward, enabled = state.tabs.firstOrNull { it.id == state.selectedTabId }?.canGoForward == true) { Icon(Icons.Default.ArrowForward, "Forward") }
                        }
                    }
                }
                if (state.loading) Text("Loading ${state.loadProgress}%", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                // Do not put a Compose click target over GeckoView: it can compete with
                // page controls and the floating DevTools panel for touch ownership.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // Keep the visible host at the device's available size.
                    state.navigationFailure?.let { failure ->
                        BrowserNavigationErrorPage(
                            failure = failure,
                            onRetry = browser::retryNavigationFailure,
                        )
                    } ?: state.selectedTabId?.let { tabId ->
                        AndroidView(
                            factory = { browserContext ->
                                BrowserSurfaceBinding(browserContext).apply {
                                    visibleBrowserSurface.value = this
                                }
                            },
                            update = { surfaceBinding: BrowserSurfaceBinding ->
                                visibleBrowserSurface.value = surfaceBinding
                                browser.engineSession(tabId)?.let { engineSession ->
                                    surfaceBinding.bind(tabId, engineSession, registerScrollListener) { scrollY ->
                                        setUrlRowVisible(when {
                                            scrollY <= 0 -> true
                                            scrollY > previousScrollY + 2 -> false
                                            scrollY < previousScrollY - 2 -> true
                                            else -> urlRowVisible
                                        })
                                        setPreviousScrollY(scrollY)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (state.loading && state.navigationFailure == null) {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.72f))
                                    Text("Loading…", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                }
                if (addressFocused && addressSuggestionsOpen) {
                    val currentPage = browser.addressEditSuggestion()
                    val normalizedQuery = addressValue.text.trim()
                    val activeTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
                    val matchingHistory = state.history
                        .asSequence()
                        .filter { normalizedQuery.isBlank() || it.url.contains(normalizedQuery, true) || it.title.contains(normalizedQuery, true) }
                        .filterNot { it.url == currentPage }
                        .distinctBy { it.url }
                        .take(8)
                        .toList()

                    fun insertSuggestion(value: String) {
                        setAddressValue(TextFieldValue(value, TextRange(0, value.length)))
                        setAddressSuggestionsOpen(true)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        currentPage?.let { value ->
                            AddressSuggestionRow(
                                icon = Icons.Default.Language,
                                value = activeTab?.title?.takeIf { it.isNotBlank() } ?: value,
                                subtitle = value,
                                onSelect = { insertSuggestion(value) },
                                onCopy = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Address", value))
                                },
                            )
                        }
                        if (normalizedQuery.isNotBlank()) {
                            AddressSuggestionRow(
                                icon = Icons.Default.Search,
                                value = normalizedQuery,
                                onSelect = { insertSuggestion(normalizedQuery) },
                            )
                        }
                        matchingHistory.forEach { entry ->
                            AddressSuggestionRow(
                                icon = Icons.Default.Language,
                                value = entry.title,
                                subtitle = entry.url,
                                onSelect = { insertSuggestion(entry.url) },
                                onRemove = { clearHistoryEntry(entry.url) },
                            )
                        }
                    }
                }
            }
        }
        if (drawerState.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable { drawerScope.launch { drawerState.close() } },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.fillMaxWidth().padding(16.dp).zIndex(3f),
        )
        }
    }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    if (tabsOpen) {
        ModalBottomSheet(onDismissRequest = { setTabsOpen(false) }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Open tabs (${state.tabs.size})", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { createTab(); setTabsOpen(false) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("New tab")
                    }
                }
                TextField(
                    value = tabSearchQuery,
                    onValueChange = setTabSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search your tabs") },
                )
                val visibleTabs = state.tabs.filter { tab ->
                    val query = tabSearchQuery.trim()
                    query.isBlank() || tab.title.contains(query, ignoreCase = true) || tab.host.contains(query, ignoreCase = true) || tab.url.contains(query, ignoreCase = true)
                }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleTabs, key = { it.id }) { tab ->
                    TabThumbnailCard(
                        tab,
                        tab.id == state.selectedTabId,
                        onSelect = { browser.selectTab(tab.id); setTabsOpen(false) },
                        onClose = { if (browser.closeTab(tab.id)) setTabsOpen(false) },
                    )
                }
            }
            }
        }
    }
    state.permissionPrompt?.takeIf { it.tabId == state.selectedTabId }?.let { prompt ->
        val answer: (BrowserPermissionAnswer) -> Unit = { browser.answerPermission(prompt.id, it) }
        when (prompt.permission) {
            "notifications" -> Dialog(onDismissRequest = { answer(BrowserPermissionAnswer.DENY_ONCE) }) {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 360.dp).clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(24.dp))
                        Text(sitePermissionMessage(prompt.origin, " wants to send you notifications"), style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { answer(BrowserPermissionAnswer.DENY_ALWAYS) }) { Text("Block") }
                        TextButton(onClick = { answer(BrowserPermissionAnswer.ALLOW_ALWAYS) }) { Text("Allow") }
                    }
                }
            }
            "location", "camera", "microphone", "camera and microphone" -> {
                val requestText = when (prompt.permission) {
                    "location" -> " wants to use your device's location"
                    "camera" -> " wants to use your camera"
                    "microphone" -> " wants to use your microphone"
                    else -> " wants to use your camera and microphone"
                }
                Dialog(onDismissRequest = { answer(BrowserPermissionAnswer.DENY_ONCE) }) {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 360.dp).clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (prompt.permission) {
                                "location" -> Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(32.dp))
                                "camera" -> Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(32.dp))
                                "microphone" -> Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(32.dp))
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(24.dp))
                                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xff1a73e8), modifier = Modifier.size(24.dp))
                                }
                            }
                            Text(sitePermissionMessage(prompt.origin, requestText), style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            "Allow while visiting the site" to BrowserPermissionAnswer.ALLOW_WHILE_VISITING,
                            "Allow this time" to BrowserPermissionAnswer.ALLOW_ONCE,
                            "Never allow" to BrowserPermissionAnswer.DENY_ALWAYS,
                        ).forEach { (label, choice) ->
                            Button(
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xffd8e5ff),
                                    contentColor = Color(0xff174ea6),
                                ),
                                onClick = { answer(choice) },
                            ) { Text(label) }
                        }
                    }
                }
            }
            else -> AlertDialog(
                onDismissRequest = { answer(BrowserPermissionAnswer.DENY_ONCE) },
                title = { Text("Site permission") },
                text = { Text("${prompt.origin} requested ${prompt.permission}.") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { answer(BrowserPermissionAnswer.ALLOW_ONCE) }) { Text("Allow this time") }
                        TextButton(onClick = { answer(BrowserPermissionAnswer.ALLOW_ALWAYS) }) { Text("Allow and remember") }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { answer(BrowserPermissionAnswer.DENY_ONCE) }) { Text("Deny this time") }
                        TextButton(onClick = { answer(BrowserPermissionAnswer.DENY_ALWAYS) }) { Text("Deny and remember") }
                    }
                },
            )
        }
    }
    state.duplicateDownload?.let { prompt ->
        DuplicateDownloadDialog(prompt, browser::confirmDuplicateDownload)
    }
    state.websitePrompt?.takeUnless { it.kind == BrowserWebsitePromptKind.FILE }?.let { prompt ->
        NativeWebsitePromptHostComposable(prompt, browser::answerWebsitePrompt)
    }
    state.error?.let { message ->
        InlineBrowserNotice(message = message, onDismiss = browser::clearError)
    }
    state.contextMenu?.let { contextMenu ->
        BrowserContextMenuSheet(
            menu = contextMenu,
            onDismiss = browser::dismissContextMenu,
            onOpenLinkInNewTab = browser::openContextLinkInNewTab,
            onOpenMediaInNewTab = browser::openContextMediaInNewTab,
            onOpenCurrent = browser::loadContextUrlInCurrentTab,
            onOpenMediaCurrent = browser::loadContextMediaInCurrentTab,
            onDownloadLink = { browser.downloadContextResource(contextMenu.linkUri) },
            onDownloadMedia = { browser.downloadContextResource(contextMenu.mediaUri) },
        )
    }
}

}

}

}

@Composable
private fun BrowserNavigationErrorPage(
    failure: BrowserNavigationFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(failure.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(failure.message, style = MaterialTheme.typography.bodyLarge)
        if (failure.address.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(failure.address, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Reload") }
    }
}

private data class BrowserCaptureTarget(val file: File, val uri: Uri, val promptId: String)

private fun supportsCameraCapture(mimeTypes: List<String>): Boolean = mimeTypes.isEmpty() || mimeTypes.any {
    val type = it.trim().lowercase()
    type == "*/*" || type == "image/*" || type == "image/jpeg" || type == "image/jpg" || type == "image/pjpeg"
}

private fun sitePermissionMessage(origin: String, suffix: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(origin.ifBlank { "This site" }) }
    append(suffix)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserFileSourceSheet(
    canCapture: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onMediaPicker: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text("Choose a source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (canCapture) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onCamera).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Text("Camera", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onMediaPicker).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Icon(Icons.Filled.ImageIcon, contentDescription = null)
                Text("Media picker", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun InlineBrowserNotice(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DuplicateDownloadDialog(
    prompt: DuplicateDownloadPrompt,
    onResolve: (String?, Boolean) -> Unit,
) {
    var fileName by remember(prompt) { mutableStateOf(prompt.name) }
    var autoRenameFutureConflicts by remember(prompt.sourceUrl) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onResolve(null, false) },
        title = { Text("File already exists") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A file named ${prompt.name} already exists in the selected download folder. Choose another name, or cancel this download.")
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Download file name") },
                    singleLine = true,
                )
                prompt.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoRenameFutureConflicts,
                        onCheckedChange = { autoRenameFutureConflicts = it },
                    )
                    Text("Always rename future duplicates automatically")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fileName.isNotBlank(),
                onClick = { onResolve(fileName, autoRenameFutureConflicts) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = { onResolve(null, false) }) { Text("Cancel") } },
    )
}

@Composable
private fun AddressSuggestionRow(
    icon: ImageVector,
    value: String,
    subtitle: String? = null,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(horizontal = 12.dp).size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(value, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            onRemove?.let { remove ->
                IconButton(onClick = remove) { Icon(Icons.Default.Close, "Remove from history") }
            }
            onCopy?.let { copy ->
                IconButton(onClick = copy) { Icon(Icons.Default.ContentCopy, "Copy address") }
            }
            IconButton(onClick = onSelect) { Icon(Icons.Default.NorthWest, "Insert suggestion") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BrowserContextMenuSheet(
    menu: BrowserContextMenu,
    onDismiss: () -> Unit,
    onOpenLinkInNewTab: () -> Unit,
    onOpenMediaInNewTab: () -> Unit,
    onOpenCurrent: () -> Unit,
    onOpenMediaCurrent: () -> Unit,
    onDownloadLink: () -> Unit,
    onDownloadMedia: () -> Unit,
) {
    val context = LocalContext.current
    val link = menu.linkUri
    val media = menu.mediaUri
    val primaryUrl = media ?: link
    val mediaType = contextResourceType(menu.kind, primaryUrl)
    val name = menu.title?.takeIf(String::isNotBlank)
        ?: menu.altText?.takeIf(String::isNotBlank)
        ?: primaryUrl?.let { Uri.parse(it).lastPathSegment?.let(Uri::decode) }?.takeIf(String::isNotBlank)
        ?: "Selected ${mediaType.lowercase()}"
    val pageUri = Uri.parse(menu.pageUrl ?: primaryUrl.orEmpty())
    val host = pageUri.host?.removePrefix("www.")
    val faviconUrl = pageUri.buildUpon().encodedPath("/favicon.ico").clearQuery().fragment(null).build().toString()
    var mediaPreview by remember(media, mediaType) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pageIcon by remember(host) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(media, mediaType) {
        mediaPreview = if (mediaType == "Image") media?.let { loadContextPreview(it, 2 * 1024 * 1024) } else null
    }
    LaunchedEffect(menu.pageUrl, host) {
        pageIcon = host?.let { loadContextPreview(faviconUrl, 256 * 1024) }
    }

    fun copy(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
        onDismiss()
    }
    fun share(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
        runCatching { context.startActivity(Intent.createChooser(intent, label)) }
        onDismiss()
    }
    fun openWith(value: String?) {
        if (value.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value))
        runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        onDismiss()
    }
    fun searchImage(value: String?) {
        if (value.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/uploadbyurl?url=${Uri.encode(value)}"))
        runCatching { context.startActivity(intent) }
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(28.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.width(76.dp).height(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mediaPreview != null) {
                            Image(mediaPreview!!, contentDescription = "Preview of $name", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(contextResourceIcon(mediaType), contentDescription = mediaType, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(name.take(140), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (pageIcon != null) Image(pageIcon!!, contentDescription = "Site icon", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else Icon(Icons.Default.Language, contentDescription = "Website", modifier = Modifier.size(16.dp))
                            }
                            Text(host ?: "Web resource", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        menu.pageTitle?.takeIf { it.isNotBlank() && it != name }?.let {
                            Text(it, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (mediaType == "Image" && mediaPreview != null) {
                    Image(
                        bitmap = mediaPreview!!,
                        contentDescription = "Preview of $name",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                when {
                    mediaType == "Video" && !media.isNullOrBlank() -> VideoResourcePreview(media)
                    mediaType == "Audio" && !media.isNullOrBlank() -> AudioResourcePreview(media)
                }
                if (link != null) {
                    ContextMenuSectionLabel("LINK")
                    BrowserContextAction(Icons.Default.OpenInNew, "Open link in new tab", onOpenLinkInNewTab)
                    BrowserContextAction(Icons.Default.ArrowForward, "Open link in current tab", onOpenCurrent)
                    BrowserContextAction(Icons.Default.Link, "Copy link address") { copy("Link address", link) }
                    if (!menu.textContent.isNullOrBlank()) BrowserContextAction(Icons.Default.ContentCopy, "Copy link text") { copy("Link text", menu.textContent) }
                    BrowserContextAction(Icons.Default.Download, "Download link", onDownloadLink)
                    BrowserContextAction(Icons.Default.Share, "Share link") { share("Share link", link) }
                }
                if (media != null) {
                    if (link != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ContextMenuSectionLabel(if (mediaType == "Image") "IMAGE" else "MEDIA")
                    BrowserContextAction(Icons.Default.OpenInNew, "Open ${mediaType.lowercase()} in new tab", onOpenMediaInNewTab)
                    BrowserContextAction(if (mediaType == "Image") Icons.Default.ImageIcon else Icons.Default.OpenInNew, if (mediaType == "Image") "Open image in browser" else "Open media in browser", onOpenMediaCurrent)
                    BrowserContextAction(Icons.Default.Link, if (mediaType == "Image") "Copy image address" else "Copy media address") { copy("Media address", media) }
                    BrowserContextAction(Icons.Default.Download, "Download ${mediaType.lowercase()}", onDownloadMedia)
                    BrowserContextAction(Icons.Default.Share, if (mediaType == "Image") "Share image link" else "Share media link") { share("Share media link", media) }
                    BrowserContextAction(Icons.Default.OpenInNew, "Open in another app…") { openWith(media) }
                    if (mediaType == "Image") BrowserContextAction(Icons.Default.Search, "Search this image") { searchImage(media) }
                }
                if (primaryUrl != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    BrowserContextAction(Icons.Default.OpenInNew, "Open externally…") { openWith(primaryUrl) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun AudioResourcePreview(url: String) {
    val context = LocalContext.current
    val player = remember(url) { MediaPlayer() }
    var prepared by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }

    DisposableEffect(player, url) {
        player.setOnPreparedListener {
            prepared = true
            failed = false
        }
        player.setOnCompletionListener {
            playing = false
            runCatching { player.seekTo(0) }
        }
        player.setOnErrorListener { _, _, _ ->
            failed = true
            prepared = false
            playing = false
            true
        }
        runCatching {
            player.setDataSource(context, Uri.parse(url))
            player.prepareAsync()
        }.onFailure {
            failed = true
        }
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                enabled = prepared && !failed,
                onClick = {
                    runCatching {
                        if (player.isPlaying) {
                            player.pause()
                            playing = false
                        } else {
                            player.start()
                            playing = true
                        }
                    }.onFailure { failed = true }
                },
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause audio" else "Play audio",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(
                enabled = prepared && !failed,
                onClick = {
                    runCatching { player.pause(); player.seekTo(0) }
                    playing = false
                },
            ) { Icon(Icons.Default.Stop, contentDescription = "Stop audio") }
            Text(
                when {
                    failed -> "Playback unavailable"
                    !prepared -> "Preparing audio…"
                    playing -> "Playing audio"
                    else -> "Audio ready"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoResourcePreview(url: String) {
    val context = LocalContext.current
    val videoView = remember(url) { VideoView(context) }
    var prepared by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    AndroidView(
        factory = { parent ->
            val controller = MediaController(parent)
            videoView.apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setMediaController(controller)
                controller.setAnchorView(this)
                setOnPreparedListener {
                    prepared = true
                    failed = false
                }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    prepared = false
                    playing = false
                    true
                }
                setVideoURI(Uri.parse(url))
            }
        },
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = prepared && !failed,
            onClick = {
                runCatching {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        playing = false
                    } else {
                        videoView.start()
                        playing = true
                    }
                }.onFailure { failed = true }
            },
        ) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause video" else "Play video", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            enabled = prepared && !failed,
            onClick = {
                runCatching { videoView.pause(); videoView.seekTo(0) }
                playing = false
            },
        ) { Icon(Icons.Default.Stop, contentDescription = "Stop video") }
        Text(
            when {
                failed -> "Playback unavailable"
                !prepared -> "Preparing video…"
                playing -> "Playing video"
                else -> "Video ready"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DisposableEffect(videoView) {
        onDispose { runCatching { videoView.stopPlayback() } }
    }
}

@Composable
private fun ContextMenuSectionLabel(label: String) {
    Text(label, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BrowserContextAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun contextResourceType(kind: BrowserContextKind, url: String?): String {
    val path = url?.let { Uri.parse(it).lastPathSegment.orEmpty().lowercase() }.orEmpty()
    val extension = path.substringAfterLast('.', "")
    return when {
        kind == BrowserContextKind.IMAGE || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "svg") -> "Image"
        kind == BrowserContextKind.VIDEO || extension in setOf("mp4", "webm", "mkv", "mov", "m4v", "avi") -> "Video"
        kind == BrowserContextKind.AUDIO || extension in setOf("mp3", "m4a", "wav", "ogg", "opus", "flac") -> "Audio"
        kind == BrowserContextKind.LINKED_MEDIA -> "Media"
        kind == BrowserContextKind.LINK -> if (extension in setOf("pdf", "zip", "gz", "apk", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")) "File" else "Link"
        else -> "File"
    }
}

private fun contextResourceIcon(type: String) = when (type) {
    "Image" -> Icons.Default.ImageIcon
    "Video" -> Icons.Default.VideoLibrary
    "Audio" -> Icons.Default.MusicNote
    "Link" -> Icons.Default.Link
    else -> Icons.Default.InsertDriveFile
}

private suspend fun loadContextPreview(url: String, maxBytes: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
    val uri = Uri.parse(url)
    if (uri.scheme !in setOf("http", "https")) return@withContext null
    val connection = runCatching { URL(url).openConnection() as? HttpURLConnection }.getOrNull() ?: return@withContext null
    try {
        connection.connectTimeout = 2_500
        connection.readTimeout = 2_500
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "image/*")
        connection.connect()
        if (!connection.contentType.orEmpty().startsWith("image/", ignoreCase = true)) return@withContext null
        if (connection.contentLengthLong > maxBytes) return@withContext null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return@withContext null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options()
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512) sampleSize *= 2
        options.inSampleSize = sampleSize
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun TabThumbnailCard(tab: BrowserTabUi, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val thumbnail = remember(tab.thumbnailPath) {
        tab.thumbnailPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Card(onClick = onSelect, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().height(235.dp)) {
            Box(
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
            ) {
                if (thumbnail != null) {
                    Image(thumbnail, contentDescription = "Thumbnail of ${tab.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Text("Thumbnail not available", style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Close tab") }
            }
            Text(tab.title.ifBlank { "New tab" }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 2, style = MaterialTheme.typography.titleSmall)
            Text(tab.host, modifier = Modifier.padding(horizontal = 10.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BrowserDrawerQuickAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    activeCount: Int = 0,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
    ) {
        if (activeCount > 0) DownloadActivityIcon(activeCount) else Icon(icon, contentDescription = label)
    }
}

@Composable
private fun DownloadActivityIcon(activeCount: Int) {
    val iconAlpha = if (activeCount > 0) {
        val transition = rememberInfiniteTransition(label = "active-download-pulse")
        val pulse by transition.animateFloat(
            initialValue = 0.62f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse),
            label = "active-download-alpha",
        )
        pulse
    } else {
        1f
    }
    BadgedBox(
        badge = {
            if (activeCount > 0) {
                Badge { Text(if (activeCount > 99) "99+" else activeCount.toString()) }
            }
        },
    ) {
        Icon(
            imageVector = if (activeCount > 0) Icons.Default.Downloading else Icons.Default.Download,
            contentDescription = if (activeCount > 0) "$activeCount active downloads" else "Downloads",
            tint = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(iconAlpha),
        )
    }
}

@Composable
private fun HistoryScreen(
    history: List<dev.android.ide.browser.BrowserHistoryEntry>,
    browser: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No browsing history", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(history, key = { "history-page-${it.id}" }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).clickable {
                        browser.hideHistoryPage()
                        browser.navigate(entry.url)
                    }.padding(vertical = 12.dp)) {
                        Text(entry.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text(entry.url, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(historyTime(entry.visitedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { browser.clearHistoryEntry(entry.id) }) {
                        Icon(Icons.Default.Close, "Remove from history")
                    }
                }
            }
        }
    }
}

private fun historyTime(timestamp: Long): String =
    if (timestamp <= 0L) "Previously visited"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun DownloadsScreen(
    downloads: List<BrowserDownload>,
    browser: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    var sort by remember { mutableStateOf(DownloadSort.DATE) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var selectionActionsOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteConfirmationIds by remember { mutableStateOf<List<String>?>(null) }
    var cancelConfirmationIds by remember { mutableStateOf<List<String>?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val selectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = selectionMode || searchOpen) {
        when {
            selectionMode -> selectedIds = emptySet()
            searchOpen -> { searchOpen = false; searchQuery = "" }
        }
    }
    val liveIds = downloads.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(liveIds) { selectedIds = selectedIds intersect liveIds }
    val ordered = when (sort) {
        DownloadSort.DATE -> downloads.sortedByDescending { it.timestamp }
        DownloadSort.NAME -> downloads.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        DownloadSort.SIZE -> downloads.sortedByDescending { it.size.coerceAtLeast(it.downloadedBytes) }
    }
    val selected = ordered.filter { it.id in selectedIds }
    val shareable = selected.filter { it.status == BrowserDownloadStatus.COMPLETED && it.available }
    val running = selected.filter { it.status == BrowserDownloadStatus.DOWNLOADING }
    val paused = selected.filter { it.status == BrowserDownloadStatus.PAUSED }

    fun shareSelected() {
        val items = shareable
        if (items.isEmpty()) return
        val intent = if (items.size == 1) {
            browser.shareDownload(items.single().id)
        } else {
            val uris = items.map { Uri.parse(it.uri) }
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonDownloadMimeType(items.map { it.mimeType })
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newUri(context.contentResolver, "Downloaded files", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (intent == null) {
            browser.reportError("One or more selected files are no longer available")
            return
        }
        runCatching { context.startActivity(Intent.createChooser(intent, if (items.size == 1) "Share download" else "Share downloads")) }
            .onFailure { browser.reportError("No application can share the selected file${if (items.size == 1) "" else "s"}") }
    }

    fun openSelected() {
        val item = shareable.singleOrNull() ?: return
        val intent = browser.openDownload(item.id)
        if (intent == null) browser.reportError("The downloaded file is no longer available")
        else runCatching { context.startActivity(intent) }.onFailure { browser.reportError("No application can open this file") }
    }

    fun confirmDelete(ids: List<String>) {
        val targets = downloads.filter { it.id in ids }
        targets.forEach { item ->
            val removed = when {
                item.status == BrowserDownloadStatus.DOWNLOADING || item.status == BrowserDownloadStatus.PAUSED -> browser.deleteDownload(item.id)
                item.status == BrowserDownloadStatus.COMPLETED && item.available -> browser.deleteDownload(item.id)
                else -> browser.removeUnavailableDownload(item.id)
            }
            if (!removed) browser.reportError("Could not remove ${item.name}")
        }
        selectedIds = emptySet()
        deleteConfirmationIds = null
    }

    fun confirmCancel(ids: List<String>) {
        ids.forEach { id -> browser.cancelDownload(id) }
        selectedIds = selectedIds - ids.toSet()
        cancelConfirmationIds = null
    }

    val filterOptions = listOf(
        DownloadFilter.ALL to "All",
        DownloadFilter.AUDIO to "Audio",
        DownloadFilter.IMAGES to "Images",
        DownloadFilter.OTHER to "Other",
    )
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    val filtered = ordered.filter {
        filter.accepts(it) && (
            searchQuery.isBlank() ||
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.sourceUrl.contains(searchQuery, ignoreCase = true) ||
                it.mimeType.contains(searchQuery, ignoreCase = true)
            )
    }
    LaunchedEffect(searchQuery, filter, filtered.map { it.id }) {
        val visibleIds = filtered.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds intersect visibleIds
    }
    val previewCandidates = filtered.filter {
        it.mimeType.startsWith("image/") &&
            it.status == BrowserDownloadStatus.COMPLETED &&
            it.available
    }
    val availablePreviews by produceState<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap(), previewCandidates.map { it.id }) {
        val decoded = linkedMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
        for (item in previewCandidates) {
            loadDownloadThumbnail(context, item.uri)?.let { decoded[item.id] = it }
            if (decoded.size == 4) break
        }
        value = decoded
    }
    val grouped = filtered.groupBy { downloadDateLabel(it.timestamp) }
    val storage = remember(context.filesDir.absolutePath) {
        runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
    }
    val totalStorage = storage?.totalBytes ?: 0L
    val availableStorage = storage?.availableBytes ?: 0L
    val usedStorage = (totalStorage - availableStorage).coerceAtLeast(0L)
    val downloadedBytes = downloads.filter { it.status == BrowserDownloadStatus.COMPLETED }
        .sumOf { it.size.coerceAtLeast(0L) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (selectionMode) "${selectedIds.size} selected" else "Downloads", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (totalStorage > 0L) "Using ${formatDownloadBytes(usedStorage)} of ${formatDownloadBytes(totalStorage)}"
                        else "${formatDownloadBytes(downloadedBytes)} downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    if (!selectionMode) IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Default.Search, if (searchOpen) "Close search" else "Search downloads")
                    }
                    IconButton(onClick = if (selectionMode) ({ selectedIds = emptySet() }) else browser::hideDownloadsPage) {
                        Icon(if (selectionMode) Icons.Default.Close else Icons.Default.Close, if (selectionMode) "Clear selection" else "Close downloads")
                    }
                }
            }
        }
        if (searchOpen) {
            item {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search downloads") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) ({
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    }) else null,
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterOptions.forEach { (option, label) ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(label) },
                        leadingIcon = if (filter == option) ({ Icon(Icons.Default.Download, null, Modifier.size(16.dp)) }) else null,
                    )
                }
            }
        }
        if (availablePreviews.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Available previews", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previewCandidates.filter { it.id in availablePreviews }.take(2).forEach { item ->
                            DownloadPreviewTile(item, availablePreviews.getValue(item.id), browser, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (selectedIds.isEmpty()) "${filtered.size} files" else "${selectedIds.size} selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (filtered.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == filtered.size) emptySet() else filtered.mapTo(mutableSetOf()) { it.id }
                        }) { Text(if (selectedIds.size == filtered.size) "Deselect all" else "Select all") }
                    }
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) { Text("Sort") }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DownloadSort.values().forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenuOpen = false })
                            }
                        }
                    }
                }
            }
        }
        if (selectedIds.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box {
                        IconButton(onClick = { selectionActionsOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Selected download actions")
                        }
                        DropdownMenu(expanded = selectionActionsOpen, onDismissRequest = { selectionActionsOpen = false }) {
                            if (selected.size == 1 && shareable.size == 1) DropdownMenuItem(
                                text = { Text("Open") },
                                leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                                onClick = { selectionActionsOpen = false; openSelected() },
                            )
                            if (shareable.isNotEmpty()) DropdownMenuItem(
                                text = { Text("Share (${shareable.size})") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { selectionActionsOpen = false; shareSelected() },
                            )
                            if (running.isNotEmpty()) DropdownMenuItem(
                                text = { Text("Pause (${running.size})") },
                                leadingIcon = { Icon(Icons.Default.Pause, null) },
                                onClick = { selectionActionsOpen = false; running.forEach { browser.pauseDownload(it.id) } },
                            )
                            if (paused.isNotEmpty()) DropdownMenuItem(
                                text = { Text("Resume (${paused.size})") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { selectionActionsOpen = false; paused.forEach { browser.resumeDownload(it.id) } },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete (${selected.size})", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { selectionActionsOpen = false; deleteConfirmationIds = selected.map { it.id } },
                            )
                        }
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (downloads.isEmpty()) "No downloads yet" else "No matching downloads", style = MaterialTheme.typography.titleMedium)
                    Text("Downloaded files will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            grouped.forEach { (label, entries) ->
                item { Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(entries, key = { it.id }) { item ->
                    DownloadRow(
                        download = item,
                        browser = browser,
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onSelectedChange = { checked -> selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id },
                        onClick = {
                            if (selectionMode) {
                                val checked = item.id !in selectedIds
                                selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                            } else {
                                val intent = browser.openDownload(item.id)
                                if (intent == null) browser.reportError("The downloaded file is no longer available")
                                else runCatching { context.startActivity(intent) }.onFailure { browser.reportError("No application can open this file") }
                            }
                        },
                        onLongClick = { selectedIds = selectedIds + item.id },
                        onCancel = { cancelConfirmationIds = listOf(item.id) },
                        onDelete = { deleteConfirmationIds = listOf(item.id) },
                    )
                }
            }
        }
    }

    deleteConfirmationIds?.let { ids ->
        val targets = downloads.filter { it.id in ids }
        val activeCount = targets.count { it.status == BrowserDownloadStatus.DOWNLOADING || it.status == BrowserDownloadStatus.PAUSED }
        AlertDialog(
            onDismissRequest = { deleteConfirmationIds = null },
            title = { Text(if (targets.size == 1) "Delete download?" else "Delete ${targets.size} downloads?") },
            text = {
                Text(buildString {
                    append("This permanently removes the selected file${if (targets.size == 1) "" else "s"} from the device when present and removes ${if (targets.size == 1) "its" else "their"} entries from this list. This cannot be undone.")
                    if (activeCount > 0) append(" $activeCount active transfer${if (activeCount == 1) " will" else "s will"} also be stopped.")
                })
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete(ids) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (targets.size == 1) "Delete" else "Delete all")
                }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmationIds = null }) { Text("Cancel") } },
        )
    }
    cancelConfirmationIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { cancelConfirmationIds = null },
            title = { Text(if (ids.size == 1) "Cancel download?" else "Cancel ${ids.size} downloads?") },
            text = { Text("The selected transfer${if (ids.size == 1) "" else "s"} will stop. Any partial file may remain in Downloads and can be removed separately.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel(ids) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (ids.size == 1) "Cancel download" else "Cancel downloads")
                }
            },
            dismissButton = { TextButton(onClick = { cancelConfirmationIds = null }) { Text("Keep downloading") } },
        )
    }
}

private enum class DownloadSort(val label: String) { DATE("Date"), NAME("Name"), SIZE("Size") }

private enum class DownloadFilter {
    ALL,
    AUDIO,
    IMAGES,
    OTHER;

    fun accepts(download: BrowserDownload): Boolean = when (this) {
        ALL -> true
        AUDIO -> download.mimeType.startsWith("audio/")
        IMAGES -> download.mimeType.startsWith("image/")
        OTHER -> !download.mimeType.startsWith("audio/") && !download.mimeType.startsWith("image/")
    }
}

private fun downloadDateLabel(timestamp: Long): String {
    if (timestamp <= 0L) return "Earlier"
    val date = Date(timestamp)
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { time = date }
    val sameDay = now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "Just now"
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        yesterday.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
}

private suspend fun loadDownloadThumbnail(
    context: android.content.Context,
    uriValue: String,
): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val uri = Uri.parse(uriValue)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
        }
    }.getOrNull()
}

@Composable
private fun DownloadPreviewTile(
    download: BrowserDownload,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap,
    browser: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.height(116.dp).clickable {
            val intent = browser.openDownload(download.id)
            if (intent == null) browser.reportError("The downloaded file is no longer available")
            else runCatching { context.startActivity(intent) }.onFailure { browser.reportError("No application can open this file") }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(thumbnail, contentDescription = download.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                download.name,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.58f)).padding(horizontal = 8.dp, vertical = 5.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun commonDownloadMimeType(values: List<String>): String {
    val mimeTypes = values.map { it.substringBefore(';').trim().ifBlank { "*/*" } }.distinct()
    if (mimeTypes.size == 1) return mimeTypes.single()
    val topLevelTypes = mimeTypes.map { it.substringBefore('/') }.distinct()
    return if (topLevelTypes.size == 1 && topLevelTypes.single() != "*") "${topLevelTypes.single()}/*" else "*/*"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    download: BrowserDownload,
    browser: BrowserViewModel,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var rowActionsOpen by remember(download.id) { mutableStateOf(false) }
    val icon = when {
        download.mimeType.startsWith("image/") -> Icons.Default.ImageIcon
        download.mimeType.startsWith("video/") -> Icons.Default.VideoLibrary
        download.mimeType.startsWith("audio/") -> Icons.Default.MusicNote
        else -> Icons.Default.InsertDriveFile
    }
    val statusLabel = when (download.status) {
        BrowserDownloadStatus.DOWNLOADING -> "Downloading"
        BrowserDownloadStatus.PAUSED -> "Paused · this session only"
        BrowserDownloadStatus.COMPLETED -> if (download.available) "Completed" else "File unavailable"
        BrowserDownloadStatus.FAILED -> "Failed${download.error?.let { " · $it" }.orEmpty()}"
        BrowserDownloadStatus.INTERRUPTED -> "Interrupted · retry starts over"
        BrowserDownloadStatus.CANCELED -> "Canceled"
    }
    val progress = if (download.totalBytes > 0L) (download.downloadedBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f) else null

    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectionMode) Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = download.mimeType, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(download.name, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    Text(statusLabel, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = if (download.status == BrowserDownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    val host = Uri.parse(download.sourceUrl).host
                    if (!host.isNullOrBlank()) Text(host, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { rowActionsOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${download.name}")
                    }
                    DropdownMenu(expanded = rowActionsOpen, onDismissRequest = { rowActionsOpen = false }) {
                        when (download.status) {
                            BrowserDownloadStatus.DOWNLOADING -> {
                                DropdownMenuItem(
                                    text = { Text("Pause") },
                                    leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; browser.pauseDownload(download.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancel") },
                                    leadingIcon = { Icon(Icons.Default.Cancel, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; onCancel() },
                                )
                            }
                            BrowserDownloadStatus.PAUSED -> {
                                DropdownMenuItem(
                                    text = { Text("Resume") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; browser.resumeDownload(download.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancel") },
                                    leadingIcon = { Icon(Icons.Default.Cancel, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; onCancel() },
                                )
                            }
                            BrowserDownloadStatus.COMPLETED -> if (download.available) {
                                DropdownMenuItem(
                                    text = { Text("Open") },
                                    leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        rowActionsOpen = false
                                        val intent = browser.openDownload(download.id)
                                        if (intent == null) browser.reportError("The downloaded file is no longer available")
                                        else runCatching { context.startActivity(intent) }.onFailure { browser.reportError("No application can open this file") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        rowActionsOpen = false
                                        val intent = browser.shareDownload(download.id)
                                        if (intent == null) browser.reportError("The downloaded file is no longer available")
                                        else runCatching { context.startActivity(Intent.createChooser(intent, "Share download")) }
                                    },
                                )
                            } else if (download.sourceUrl.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Retry") },
                                    leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; browser.retryDownload(download.id) },
                                )
                            }
                            BrowserDownloadStatus.FAILED, BrowserDownloadStatus.INTERRUPTED, BrowserDownloadStatus.CANCELED -> if (download.sourceUrl.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Retry") },
                                    leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                                    onClick = { rowActionsOpen = false; browser.retryDownload(download.id) },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { rowActionsOpen = false; onDelete() },
                        )
                    }
                }
            }
            if (download.status == BrowserDownloadStatus.DOWNLOADING || download.status == BrowserDownloadStatus.PAUSED) {
                if (progress != null) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val sizeLabel = if (download.totalBytes > 0L) "${formatDownloadBytes(download.downloadedBytes)} of ${formatDownloadBytes(download.totalBytes)}" else "${formatDownloadBytes(download.downloadedBytes)} downloaded"
                Text(sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (download.status == BrowserDownloadStatus.COMPLETED && download.available) {
                Text("${formatDownloadBytes(download.size)} · ${download.mimeType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDownloadBytes(value: Long): String {
    val amount = value.coerceAtLeast(0L).toDouble()
    return when {
        amount >= 1024.0 * 1024 * 1024 -> "%.1f GB".format(amount / (1024.0 * 1024 * 1024))
        amount >= 1024.0 * 1024 -> "%.1f MB".format(amount / (1024.0 * 1024))
        amount >= 1024.0 -> "%.0f KB".format(amount / 1024.0)
        else -> "${value.coerceAtLeast(0L)} B"
    }
}
