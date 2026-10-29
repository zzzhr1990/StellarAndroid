package com.stellar.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SubtitleDelayControl(delayMs: Int, actions: PlayerPanelActions, enabled: Boolean) {
    val value = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1; isGroupingUsed = false
    }.format(delayMs / 1000.0)
    val decrease = stringResource(R.string.subtitle_delay_decrease)
    val increase = stringResource(R.string.subtitle_delay_increase)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        IconButton(onClick = { actions.setSubtitleDelay(delayMs - 100) }, enabled = enabled && delayMs > -60_000,
            modifier = Modifier.size(48.dp).testTag("subtitle-delay-minus").semantics { contentDescription = decrease }) {
            Text("−", fontSize = 20.sp)
        }
        Text(value, fontSize = 14.sp, maxLines = 1,
            modifier = Modifier.widthIn(min = 28.dp).testTag("subtitle-delay-value"))
        IconButton(onClick = { actions.setSubtitleDelay(delayMs + 100) }, enabled = enabled && delayMs < 60_000,
            modifier = Modifier.size(48.dp).testTag("subtitle-delay-plus").semantics { contentDescription = increase }) {
            Text("+", fontSize = 20.sp)
        }
    }
}
