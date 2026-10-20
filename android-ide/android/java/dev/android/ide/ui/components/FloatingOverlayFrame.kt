package dev.android.ide.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A movable floating overlay frame. It is deliberately not a bottom-sheet
 * component: bottom anchoring is only the initial placement, while the frame
 * remains free to move and resize in both dimensions.
 */
@Composable
fun FloatingOverlayFrame(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    initialWidth: Float,
    initialHeight: Float,
    minWidth: Float = 280f,
    minHeight: Float = 220f,
    maxHeightDp: Float = 760f,
    startFromRight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var width by rememberSaveable { mutableFloatStateOf(initialWidth) }
    var height by rememberSaveable { mutableFloatStateOf(initialHeight) }
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var positioned by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth.value
        val availableHeight = maxHeight.value
        val maxWidth = availableWidth.coerceAtLeast(minWidth)
        val maxHeight = maxHeightDp.coerceAtMost(availableHeight).coerceAtLeast(minHeight)
        val resolvedWidth = width.coerceIn(minWidth, maxWidth)
        val resolvedHeight = height.coerceIn(minHeight, maxHeight)
        val maxX = (availableWidth - resolvedWidth).coerceAtLeast(0f)
        val maxY = (availableHeight - resolvedHeight).coerceAtLeast(0f)

        LaunchedEffect(availableWidth, availableHeight) {
            if (!positioned) {
                width = resolvedWidth
                height = resolvedHeight
                x = if (startFromRight) maxX else ((availableWidth - resolvedWidth) / 2f).coerceAtLeast(0f)
                y = maxY
                positioned = true
            } else {
                x = x.coerceIn(0f, maxX)
                y = y.coerceIn(0f, maxY)
            }
        }

        val animatedX by animateFloatAsState(
            targetValue = if (!positioned && startFromRight) availableWidth else x,
            animationSpec = tween(durationMillis = 260),
            label = "floating overlay horizontal entrance",
        )

        if (visible) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            with(density) { animatedX.dp.roundToPx() },
                            with(density) { y.dp.roundToPx() },
                        )
                    }
                    .size(resolvedWidth.dp, resolvedHeight.dp),
            ) {
                Box(Modifier.fillMaxSize().padding(top = 30.dp, bottom = 18.dp)) { content() }
                OverlayPositionBar(
                    expanded = expanded,
                    onToggleExpanded = {
                        expanded = !expanded
                        height = if (expanded) maxHeight else minHeight
                        y = y.coerceIn(0f, (availableHeight - height).coerceAtLeast(0f))
                    },
                    onDrag = { dx, dy ->
                        x = (x + with(density) { dx.toDp().value }).coerceIn(0f, maxX)
                        y = (y + with(density) { dy.toDp().value }).coerceIn(0f, maxY)
                    },
                )
                ResizeHandle(
                    onDrag = { dx, dy ->
                        width = (width + with(density) { dx.toDp().value }).coerceIn(minWidth, maxWidth)
                        height = (height + with(density) { dy.toDp().value }).coerceIn(minHeight, maxHeight)
                        x = x.coerceIn(0f, (availableWidth - width).coerceAtLeast(0f))
                        y = y.coerceIn(0f, (availableHeight - height).coerceAtLeast(0f))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OverlayPositionBar(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = "Move overlay", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        IconButton(onClick = onToggleExpanded, modifier = Modifier.size(30.dp)) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (expanded) "Collapse overlay" else "Expand overlay",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResizeHandle(onDrag: (Float, Float) -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(30.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = "Resize overlay", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
