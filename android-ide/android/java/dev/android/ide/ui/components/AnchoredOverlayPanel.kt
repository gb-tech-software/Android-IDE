package dev.android.ide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed-edge panel that resizes along its anchored edge; it can never be dragged to a free position. */
enum class AnchoredPanelPlacement { BOTTOM, RIGHT }

@Composable
fun AnchoredOverlayPanel(
    modifier: Modifier = Modifier,
    visible: Boolean,
    placement: AnchoredPanelPlacement,
    bottomInset: Dp = 0.dp,
    widthFraction: Float = 1f,
    initialHeightFraction: Float = 0.48f,
    minHeight: Dp = 220.dp,
    content: @Composable () -> Unit,
) {
    var savedHeight by rememberSaveable { mutableFloatStateOf(0f) }
    var savedWidth by rememberSaveable { mutableFloatStateOf(0f) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val imeInset = with(density) { androidx.compose.foundation.layout.WindowInsets.ime.getBottom(this).toDp() }
        val maxHeightDp = (maxHeight - bottomInset - imeInset).coerceAtLeast(0.dp)
        val maxWidthDp = maxWidth
        val minWidthDp = (maxWidthDp * 0.55f).coerceAtMost(maxWidthDp)
        val defaultWidth = (maxWidthDp * widthFraction.coerceIn(0.55f, 1f)).coerceAtLeast(280.dp).coerceAtMost(maxWidthDp)
        val panelWidth = if (placement == AnchoredPanelPlacement.RIGHT && savedWidth > 0f) {
            savedWidth.dp.coerceIn(minWidthDp, maxWidthDp)
        } else {
            defaultWidth
        }
        val minHeightDp = minHeight.coerceAtMost(maxHeightDp)
        val collapsedHeight = if (savedHeight > 0f) savedHeight.dp.coerceIn(minHeightDp, maxHeightDp)
            else (maxHeightDp * initialHeightFraction).coerceIn(minHeightDp, maxHeightDp)
        val panelHeight = if (expanded) maxHeightDp else collapsedHeight
        val edgeAlignment = if (placement == AnchoredPanelPlacement.BOTTOM) Alignment.BottomCenter else Alignment.BottomEnd
        val enterTransition = if (placement == AnchoredPanelPlacement.BOTTOM) {
            slideInVertically(animationSpec = tween(240)) { it }
        } else {
            slideInHorizontally(animationSpec = tween(240)) { it }
        }
        val exitTransition = if (placement == AnchoredPanelPlacement.BOTTOM) {
            slideOutVertically(animationSpec = tween(180)) { it }
        } else {
            slideOutHorizontally(animationSpec = tween(180)) { it }
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(edgeAlignment).padding(bottom = bottomInset).imePadding(),
            enter = enterTransition,
            exit = exitTransition,
        ) {
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .shadow(12.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.weight(1f).height(34.dp).pointerInput(panelHeight, panelWidth, expanded, placement) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                expanded = false
                                if (placement == AnchoredPanelPlacement.BOTTOM) {
                                    val delta = with(density) { dragAmount.y.toDp().value }
                                    savedHeight = (panelHeight.value - delta).coerceIn(minHeightDp.value, maxHeightDp.value)
                                } else {
                                    val delta = with(density) { dragAmount.x.toDp().value }
                                    savedWidth = (panelWidth.value - delta).coerceIn(minWidthDp.value, maxWidthDp.value)
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = if (placement == AnchoredPanelPlacement.BOTTOM) "Resize panel height" else "Resize panel width",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            expanded = !expanded
                            if (!expanded && savedHeight <= 0f) savedHeight = collapsedHeight.value
                        },
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (expanded) "Reduce panel height" else "Expand panel height",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    content = content,
                )
            }
        }
    }
}
