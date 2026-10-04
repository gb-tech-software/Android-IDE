package dev.android.ide.ui.screen

import android.graphics.BitmapFactory
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.android.ide.browser.BrowserDownload
import dev.android.ide.browser.BrowserContextKind
import dev.android.ide.browser.BrowserContextMenu
import dev.android.ide.browser.BrowserTabUi
import dev.android.ide.browser.BrowserViewModel
import dev.android.ide.data.ThemeResolver
import dev.android.ide.data.ResolvedTheme
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

private const val MAX_VIEWPORT_WIDTH = 2400

/**
 * Keeps pull-to-refresh tied to the document rather than to GeckoView's native
 * view bounds. Gecko renders the document internally, so canScrollVertically()
 * alone can report that the view is at its top while the web page is still far
 * below its own top.
 */
private class BrowserRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    var pageScrollY: Int = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSurface(
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onApplicationBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val browser: BrowserViewModel = viewModel()
    val context = LocalContext.current
    val themeResolver = remember { ThemeResolver(context) }
    val state by browser.uiState.collectAsState()
    var addressText by remember { mutableStateOf(state.address) }
    var addressFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var viewportOpen by remember { mutableStateOf(false) }
    var viewportText by remember { mutableStateOf("") }
    var viewportError by remember { mutableStateOf<String?>(null) }
    var urlRowVisible by remember { mutableStateOf(true) }
    var errorVisible by remember { mutableStateOf(false) }
    var previousScrollY by remember { mutableIntStateOf(0) }
    var tabSearchQuery by remember { mutableStateOf("") }
    val visibleGeckoView = remember { mutableStateOf<org.mozilla.geckoview.GeckoView?>(null) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.address, addressFocused) { if (!addressFocused) addressText = state.address }
    LaunchedEffect(state.error) { if (state.error != null) errorVisible = true }
    BackHandler(enabled = state.selectedTabId != null) {
        if (addressFocused) {
            focusManager.clearFocus()
            addressText = state.address
            addressFocused = false
        } else {
            val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
            if (selectedTab?.canGoBack == true) browser.goBack() else onApplicationBack()
        }
    }

    val browserColorScheme = when (themeResolver.getBrowserTheme(state.settings.theme)) {
        ResolvedTheme.DARK -> darkColorScheme()
        ResolvedTheme.LIGHT -> lightColorScheme()
    }
    MaterialTheme(colorScheme = browserColorScheme) {
        BrowserSurfaceContent(modifier, state, browser, visibleGeckoView, addressText, { addressText = it }, addressFocused, { addressFocused = it }, state.history, browser::clearHistoryEntry, { browser.createTab() }, { state.selectedTabId?.let { browser.captureTabPreview(it, visibleGeckoView.value) }; tabsOpen = true }, { onOpenNavigation() }, { onOpenSettings() }, tabSearchQuery, { tabSearchQuery = it }, { urlRowVisible = it }, urlRowVisible, menuOpen, { menuOpen = it }, tabsOpen, { tabsOpen = it }, downloadsOpen, { downloadsOpen = it }, viewportOpen, { viewportOpen = it }, viewportText, { viewportText = it }, viewportError, { viewportError = it }, errorVisible, { errorVisible = it }, previousScrollY, { previousScrollY = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserSurfaceContent(
    modifier: Modifier,
    state: dev.android.ide.browser.BrowserUiState,
    browser: BrowserViewModel,
    visibleGeckoView: androidx.compose.runtime.MutableState<org.mozilla.geckoview.GeckoView?>,
    addressText: String,
    setAddressText: (String) -> Unit,
    addressFocused: Boolean,
    setAddressFocused: (Boolean) -> Unit,
    history: List<String>,
    clearHistoryEntry: (String) -> Unit,
    createTab: () -> Unit,
    openTabs: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenSettings: () -> Unit,
    tabSearchQuery: String,
    setTabSearchQuery: (String) -> Unit,
    setUrlRowVisible: (Boolean) -> Unit,
    urlRowVisible: Boolean,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    tabsOpen: Boolean,
    setTabsOpen: (Boolean) -> Unit,
    downloadsOpen: Boolean,
    setDownloadsOpen: (Boolean) -> Unit,
    viewportOpen: Boolean,
    setViewportOpen: (Boolean) -> Unit,
    viewportText: String,
    setViewportText: (String) -> Unit,
    viewportError: String?,
    setViewportError: (String?) -> Unit,
    errorVisible: Boolean,
    setErrorVisible: (Boolean) -> Unit,
    previousScrollY: Int,
    setPreviousScrollY: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Browser") },
            navigationIcon = { IconButton(onClick = onOpenNavigation) { Icon(Icons.Default.Menu, "Open Android IDE navigation") } },
            actions = {
                IconButton(onClick = { if (state.loading) browser.stop() else browser.reload() }, enabled = state.selectedTabId != null) {
                    Icon(Icons.Default.Refresh, if (state.loading) "Stop loading" else "Refresh")
                }
                IconButton(onClick = createTab) { Icon(Icons.Default.Add, "New tab") }
                IconButton(onClick = openTabs) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(state.tabs.size.toString(), style = MaterialTheme.typography.labelLarge)
                    }
                }
                IconButton(onClick = { setMenuOpen(true) }) { Icon(Icons.Default.MoreVert, "Browser menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.DeveloperMode, null) },
                        text = { Text(if (state.developerToolsOpen) "Hide developer tools" else "Show developer tools") },
                        onClick = { setMenuOpen(false); browser.toggleDeveloperTools() },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        text = { Text("Downloads") },
                        onClick = { setMenuOpen(false); browser.reconcileDownloads(); setDownloadsOpen(true) },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        text = { Text("Custom viewport") },
                        onClick = {
                            setMenuOpen(false)
                            setViewportText(state.viewportWidth?.toString().orEmpty())
                            setViewportError(null)
                            setViewportOpen(true)
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        text = { Text("Browser settings") },
                        onClick = { setMenuOpen(false); onOpenSettings() },
                    )
                }
            },
        )
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
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = setAddressText,
                        modifier = Modifier.weight(1f).onFocusChanged { focusState ->
                            if (focusState.isFocused && !addressFocused) {
                                setAddressFocused(true)
                                setAddressText("")
                            } else if (!focusState.isFocused && addressFocused) {
                                setAddressFocused(false)
                                setAddressText(state.address)
                            }
                        },
                        singleLine = true,
                        label = { Text("Search or enter address") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            browser.navigate(addressText)
                            focusManager.clearFocus()
                            setAddressFocused(false)
                        }),
                        trailingIcon = if (addressFocused && addressText.isNotEmpty()) ({
                            IconButton(onClick = { setAddressText("") }) { Icon(Icons.Default.Close, "Clear address") }
                        }) else null,
                    )
                    IconButton(onClick = browser::goBack, enabled = state.tabs.firstOrNull { it.id == state.selectedTabId }?.canGoBack == true) { Icon(Icons.Default.ArrowBack, "Back") }
                    IconButton(onClick = browser::goForward, enabled = state.tabs.firstOrNull { it.id == state.selectedTabId }?.canGoForward == true) { Icon(Icons.Default.ArrowForward, "Forward") }
                }
                if (addressFocused) {
                    val firstSuggestion = browser.addressEditSuggestion()
                    val normalizedQuery = addressText.trim()
                    val matchingHistory = history
                        .filter { it != firstSuggestion && (normalizedQuery.isBlank() || it.contains(normalizedQuery, ignoreCase = true)) }
                        .take(8)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            firstSuggestion?.let { value ->
                                AddressSuggestionRow(
                                    value = value,
                                    subtitle = state.tabs.firstOrNull { it.id == state.selectedTabId }?.title,
                                    onSelect = { setAddressText(value) },
                                )
                            }
                            if (normalizedQuery.isNotBlank() && normalizedQuery != firstSuggestion) {
                                AddressSuggestionRow(value = normalizedQuery, onSelect = { setAddressText(normalizedQuery) })
                            }
                            matchingHistory.forEach { entry ->
                                AddressSuggestionRow(
                                    value = entry,
                                    onSelect = { setAddressText(entry) },
                                    onRemove = { clearHistoryEntry(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.loading) Text("Loading ${state.loadProgress}%", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        state.downloadProgress?.let { progress ->
            val label = if (progress.totalBytes > 0) "Downloading ${progress.name} · ${(progress.bytes * 100 / progress.totalBytes).toInt()}%" else "Downloading ${progress.name}…"
            Text(label, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        }
        Box(modifier = Modifier.fillMaxSize().clickable {
            if (addressFocused) {
                focusManager.clearFocus()
                setAddressFocused(false)
                setAddressText(state.address)
            }
            setUrlRowVisible(true)
        }) {
            state.selectedTabId?.let { tabId ->
                AndroidView(
                    factory = { browserContext ->
                        BrowserRefreshLayout(browserContext).apply {
                            val geckoView = org.mozilla.geckoview.GeckoView(browserContext)
                            visibleGeckoView.value = geckoView
                            addView(geckoView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            setOnChildScrollUpCallback { _, _ ->
                                // GeckoView is not the document's scroll container. Its native
                                // canScrollVertically result is only a fallback; Gecko's scroll
                                // delegate supplies the authoritative page offset below.
                                pageScrollY > 0 || geckoView.canScrollVertically(-1)
                            }
                            setOnRefreshListener {
                                browser.reload()
                            }
                        }
                    },
                    update = { refreshContainer ->
                        val view = refreshContainer.getChildAt(0) as? org.mozilla.geckoview.GeckoView
                        if (view != null) {
                            visibleGeckoView.value = view
                            val desiredWidth = state.viewportWidth?.let { (it * density.density).roundToInt() }
                            val params = view.layoutParams
                            val targetWidth = desiredWidth ?: ViewGroup.LayoutParams.MATCH_PARENT
                            if (params.width != targetWidth) {
                                params.width = targetWidth
                                view.layoutParams = params
                                view.requestLayout()
                                refreshContainer.requestLayout()
                            }
                            browser.bind(view, tabId) { scrollY ->
                                // SwipeRefreshLayout decides whether to intercept before GeckoView
                                // receives a drag. Keep this value current so an upward/downward
                                // page scroll remains a page scroll everywhere except document top.
                                refreshContainer.pageScrollY = scrollY.coerceAtLeast(0)
                                setUrlRowVisible(when {
                                    scrollY <= 0 -> true
                                    scrollY > previousScrollY + 2 -> false
                                    scrollY < previousScrollY - 2 -> true
                                    else -> urlRowVisible
                                })
                                setPreviousScrollY(scrollY)
                            }
                        }
                        if (!state.loading) refreshContainer.isRefreshing = false
                    },
                    modifier = if (state.viewportWidth == null) Modifier.fillMaxSize() else Modifier.width(state.viewportWidth.dp).fillMaxHeight(),
                )
            }
        }
    }

    if (tabsOpen) {
        ModalBottomSheet(onDismissRequest = { setTabsOpen(false) }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open tabs (${state.tabs.size})", style = MaterialTheme.typography.titleLarge)
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
                    TabPreviewCard(tab, tab.id == state.selectedTabId, onSelect = { browser.selectTab(tab.id); setTabsOpen(false) }, onClose = { browser.closeTab(tab.id) })
                }
            }
            }
        }
    }
    if (downloadsOpen) DownloadsDialog(state.downloads, browser, onDismiss = { setDownloadsOpen(false) })
    if (viewportOpen) {
        AlertDialog(
            onDismissRequest = { setViewportOpen(false) },
            title = { Text("Custom viewport width") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current: ${state.viewportWidth?.let { "$it px" } ?: "Device width"}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = viewportText, onValueChange = { setViewportText(it.filter(Char::isDigit).take(4)); setViewportError(null) }, label = { Text("320–$MAX_VIEWPORT_WIDTH px") }, singleLine = true)
                    viewportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val width = viewportText.toIntOrNull()
                    if (width == null || width !in 320..MAX_VIEWPORT_WIDTH) setViewportError("Enter a width from 320 to $MAX_VIEWPORT_WIDTH pixels.")
                    else { browser.setViewportWidth(width); setViewportOpen(false) }
                }) { Text("Apply") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { browser.resetViewport(); setViewportOpen(false) }) { Text("Reset") }
                    TextButton(onClick = { setViewportOpen(false) }) { Text("Cancel") }
                }
            },
        )
    }
    state.permissionPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { browser.answerPermission(false) },
            title = { Text("Site permission") },
            text = { Text("${prompt.origin} requested ${prompt.permission}.") },
            confirmButton = { TextButton(onClick = { browser.answerPermission(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { browser.answerPermission(false) }) { Text("Deny") } },
        )
    }
    state.duplicateDownload?.let { prompt ->
        AlertDialog(
            onDismissRequest = { browser.confirmDuplicateDownload(false) },
            title = { Text("File already exists") },
            text = { Text("${prompt.name} is already present in the download list. Replace it?") },
            confirmButton = { TextButton(onClick = { browser.confirmDuplicateDownload(true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { browser.confirmDuplicateDownload(false) }) { Text("Keep existing") } },
        )
    }
    if (errorVisible) {
        AlertDialog(onDismissRequest = { setErrorVisible(false); browser.clearError() }, title = { Text("Browser error") }, text = { Text(state.error.orEmpty()) }, confirmButton = { TextButton(onClick = { setErrorVisible(false); browser.clearError() }) { Text("OK") } })
    }
    state.contextMenu?.let { contextMenu ->
        BrowserContextMenuSheet(
            menu = contextMenu,
            onDismiss = browser::dismissContextMenu,
            onOpenLinkInNewTab = browser::openContextLinkInNewTab,
            onOpenMediaInNewTab = browser::openContextMediaInNewTab,
            onOpenCurrent = browser::loadContextUrlInCurrentTab,
            onOpenMediaCurrent = browser::loadContextMediaInCurrentTab,
            onDownload = browser::downloadContextResource,
        )
    }
}

@Composable
private fun AddressSuggestionRow(
    value: String,
    subtitle: String? = null,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ArrowBack, "Use suggestion", modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(value, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onRemove?.let { remove ->
            IconButton(onClick = remove) { Icon(Icons.Default.Close, "Remove from history") }
        }
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
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val link = menu.linkUri
    val media = menu.mediaUri
    val primaryUrl = link ?: media
    fun copy(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
        onDismiss()
    }
    fun copyImage(value: String?) {
        if (value.isNullOrBlank()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newUri(context.contentResolver, "Image", Uri.parse(value)),
        )
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
        Card(
            Modifier.fillMaxWidth().padding(20.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
            Text(
                when (menu.kind) {
                    BrowserContextKind.LINK -> "Link"
                    BrowserContextKind.IMAGE -> "Image"
                    BrowserContextKind.VIDEO -> "Video"
                    BrowserContextKind.AUDIO -> "Audio"
                    BrowserContextKind.LINKED_MEDIA -> "Link and media"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                (menu.title ?: menu.altText ?: menu.textContent ?: primaryUrl ?: "Selected item").take(180),
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (link != null) {
                BrowserContextAction("Open in new tab", onOpenLinkInNewTab)
                BrowserContextAction("Preview page", onOpenCurrent)
                BrowserContextAction("Copy link address") { copy("Link address", link) }
                if (!menu.textContent.isNullOrBlank()) BrowserContextAction("Copy link text") { copy("Link text", menu.textContent) }
                BrowserContextAction("Download link", onDownload)
                BrowserContextAction("Share link") { share("Share link", link) }
            }
            if (media != null) {
                BrowserContextAction(if (menu.kind == BrowserContextKind.IMAGE) "Open image in new tab" else "Open media in new tab", onOpenMediaInNewTab)
                BrowserContextAction(if (menu.kind == BrowserContextKind.IMAGE) "Preview image" else "Play media", onOpenMediaCurrent)
                if (menu.kind == BrowserContextKind.IMAGE) BrowserContextAction("Copy image") { copyImage(media) }
                BrowserContextAction(if (menu.kind == BrowserContextKind.IMAGE) "Copy image address" else "Copy media address") { copy("Media address", media) }
                BrowserContextAction(if (menu.kind == BrowserContextKind.IMAGE) "Download image" else "Download media", onDownload)
                BrowserContextAction(if (menu.kind == BrowserContextKind.IMAGE) "Share image" else "Share media") { share("Share media", media) }
                if (menu.kind == BrowserContextKind.IMAGE) BrowserContextAction("Search this image") { searchImage(media) }
            }
            BrowserContextAction("Open with…") { openWith(primaryUrl) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Cancel") }
        }
    }
}

@Composable
private fun BrowserContextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TabPreviewCard(tab: BrowserTabUi, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val preview = remember(tab.previewPath) {
        tab.previewPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Card(onClick = onSelect, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().height(235.dp)) {
            Box(
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
            ) {
                if (preview != null) {
                    Image(preview, contentDescription = "Snapshot of ${tab.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Text("Preview not available", style = MaterialTheme.typography.labelMedium)
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
private fun DownloadsDialog(downloads: List<BrowserDownload>, browser: BrowserViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            if (downloads.isEmpty()) {
                Text("No downloads have been recorded.", modifier = Modifier.padding(vertical = 24.dp))
            } else {
                downloads.asReversed().take(50).forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(56.dp).height(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Downloaded file")
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                Text(if (item.available) "Available" else "Unavailable", style = MaterialTheme.typography.labelMedium, color = if (item.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                Text(item.mimeType, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (item.available) {
                                        TextButton(onClick = {
                                            val intent = browser.openDownload(item.id)
                                            if (intent == null) browser.reportError("The downloaded file is no longer available")
                                            else runCatching { context.startActivity(intent) }.onFailure { browser.reportError("No application can open this file") }
                                        }) { Text("Open") }
                                        TextButton(onClick = { browser.deleteDownload(item.id) }) { Text("Delete") }
                                    } else {
                                        TextButton(onClick = { browser.removeUnavailableDownload(item.id) }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}
