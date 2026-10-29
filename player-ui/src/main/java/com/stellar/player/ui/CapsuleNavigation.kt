package com.stellar.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

data class CapsuleDestination(val id: String, val label: String, val icon: ImageVector)

/** Stateless application navigation. The host owns destinations and system insets. */
@Composable
fun CapsuleNavigation(
    destinations: List<CapsuleDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    navigationTag: String = "home-capsule",
    destinationTagPrefix: String = "home-tab",
) {
    if (destinations.isEmpty()) return
    val shape = RoundedCornerShape(50)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val desiredItemWidth = if (showLabels) with(density) {
        destinations.maxOf { textMeasurer.measure(AnnotatedString(it.label), labelStyle, softWrap = false).size.width }
            .toDp().plus(24.dp).coerceAtLeast(80.dp)
    } else 60.dp
    val desiredWidth = desiredItemWidth * destinations.size + 4.dp
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = desiredWidth.coerceAtMost(maxWidth)
        val itemWidth = (width - 4.dp) / destinations.size
        val labelHeight = if (showLabels) with(density) {
            val textWidth = (itemWidth - 12.dp).roundToPx().coerceAtLeast(1)
            destinations.maxOf { textMeasurer.measure(AnnotatedString(it.label), labelStyle,
                constraints = Constraints(maxWidth = textWidth)).size.height }.toDp()
        } else 0.dp
        val height = if (showLabels) (24.dp + 2.dp + labelHeight + 16.dp).coerceAtLeast(56.dp) else 56.dp
        val selectedIndex = destinations.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val indicatorOffset by animateDpAsState(itemWidth * selectedIndex,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "capsule-selection")
        Box(Modifier.width(width).height(height)
            .shadow(6.dp, shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(2.dp)
            .selectableGroup().testTag(navigationTag)) {
            Box(Modifier.offset(x = indicatorOffset).width(itemWidth).fillMaxHeight()
                .clip(shape).background(MaterialTheme.colorScheme.secondaryContainer))
            Row(Modifier.fillMaxSize()) {
                destinations.forEach { destination ->
                    val selected = destination.id == selectedId
                    val tint by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        MaterialTheme.motionScheme.fastEffectsSpec(), label = "capsule-icon-color")
                    Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(destination.id) })
                        .testTag("$destinationTagPrefix-${destination.id}").padding(horizontal = 6.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(destination.icon, contentDescription = if (showLabels) null else destination.label,
                                modifier = Modifier.size(24.dp), tint = tint)
                            if (showLabels) Text(destination.label, style = labelStyle, color = tint,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
