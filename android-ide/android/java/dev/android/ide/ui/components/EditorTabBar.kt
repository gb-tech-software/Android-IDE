// android-ide/android/java/dev/android/ide/ui/components/EditorTabBar.kt
//
// Horizontal scrollable tab bar showing open editor files.
// Each tab has a ••• overflow button with Locate / Save / Close / Close others / Close all.
// A "+" button at the far right opens a new blank tab.

package dev.android.ide.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.android.ide.ui.theme.LocalIdeColors
import dev.android.ide.viewmodel.model.EditorTab

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorTabBar(
    tabs: List<EditorTab>,
    onTabSelected: (String) -> Unit,
    onTabCloseSafe: (String) -> Unit,
    onTabSave: (String) -> Unit,
    onTabPin: (String) -> Unit,
    onTabLocate: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit,
    onNewBlankTab: () -> Unit,
    allowManualSave: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIdeColors.current
    Row(
        modifier = modifier
            .height(36.dp)
            .fillMaxWidth()
            .background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Scrollable tab list
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                EditorTabItem(
                    tab          = tab,
                    onSelected   = { onTabSelected(tab.id) },
                    onCloseSafe  = { onTabCloseSafe(tab.id) },
                    onSave       = { onTabSave(tab.id) },
                    onPin        = { onTabPin(tab.id) },
                    onLocate     = { onTabLocate(tab.id) },
                    onCloseOthers = { onCloseOthers(tab.id) },
                    onCloseAll   = onCloseAll,
                    allowManualSave = allowManualSave,
                )
            }
        }

        // "+" new blank tab button
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(colors.separator),
        )
        IconButton(
            onClick  = onNewBlankTab,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Add,
                contentDescription = "New tab",
                tint               = colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
        }
    }
}

// ── Tab item ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorTabItem(
    tab: EditorTab,
    onSelected: () -> Unit,
    onCloseSafe: () -> Unit,
    onSave: () -> Unit,
    onPin: () -> Unit,
    onLocate: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit,
    allowManualSave: Boolean,
) {
    val colors  = LocalIdeColors.current
    val bgColor = when {
        tab.isDirty && tab.isActive -> colors.modified.copy(alpha = 0.22f)
        tab.isDirty -> colors.modified.copy(alpha = 0.10f)
        tab.isActive -> colors.background
        else -> colors.surface
    }
    var menuOpen by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(tab.id, tab.isActive, tab.displayName, tab.isDirty) {
        if (tab.isActive) bringIntoViewRequester.bringIntoView()
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .background(bgColor)
            .bringIntoViewRequester(bringIntoViewRequester)
            .clickable(onClick = onSelected),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 10.dp, end = 2.dp),
        ) {
            // Dirty / saving indicator
            when {
                tab.isSaving -> CircularProgressIndicator(
                    modifier  = Modifier.size(8.dp).padding(end = 2.dp),
                    strokeWidth = 1.5.dp,
                    color     = colors.primary,
                )
                tab.isDirty -> Text(
                    text  = "\u25cf ",  // ●
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.modified,
                )
                else -> Spacer(Modifier.width(0.dp))
            }

            if (tab.isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned tab",
                    tint = colors.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
            FileTypeBadge(tab.displayName)
            Spacer(Modifier.width(4.dp))
            Text(
                text     = tab.displayName,
                style    = MaterialTheme.typography.labelMedium.copy(
                    fontStyle = if (tab.isTemporary) FontStyle.Italic else FontStyle.Normal,
                ),
                color    = if (tab.isActive) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 100.dp),
            )

            Spacer(Modifier.width(2.dp))

            // ••• overflow button
            Box {
                IconButton(
                    onClick  = { menuOpen = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.MoreVert,
                        contentDescription = "Tab options",
                        tint               = if (tab.isActive) colors.textSecondary else colors.textDisabled,
                        modifier           = Modifier.size(14.dp),
                    )
                }

                DropdownMenu(
                    expanded         = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (tab.isTemporary) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                            text    = { Text(if (tab.isPinned) "Unpin" else "Pin") },
                            onClick = { menuOpen = false; onPin() },
                        )
                    }
                    if (tab.isBlank && allowManualSave && !tab.isReadOnly) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                            text = { Text("Save") },
                            onClick = { menuOpen = false; onSave() },
                        )
                    } else if (!tab.isBlank && !tab.isImagePreview) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                            text    = { Text("Locate") },
                            onClick = { menuOpen = false; onLocate() },
                        )
                        if (allowManualSave && !tab.isReadOnly) DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                            text    = { Text("Save") },
                            onClick = { menuOpen = false; onSave() },
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        text    = { Text("Close") },
                        onClick = { menuOpen = false; onCloseSafe() },
                    )
                    DropdownMenuItem(
                        text    = { Text("Close others") },
                        onClick = { menuOpen = false; onCloseOthers() },
                    )
                    DropdownMenuItem(
                        text    = { Text("Close all") },
                        onClick = { menuOpen = false; onCloseAll() },
                    )
                }
            }
        }

        // Active indicator bar at bottom
        if (tab.isActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(colors.primary)
                    .align(Alignment.BottomCenter),
            )
        }

        // Right separator
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(colors.separator)
                .align(Alignment.CenterEnd),
        )
    }
}
