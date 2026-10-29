package com.stellar.player.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Theme-aware buffering with Material's built-in wave animation and subdued circular track. */
@Composable
internal fun PlaybackBufferingIndicator() {
    val description = stringResource(R.string.state_buffering)
    CircularWavyProgressIndicator(
        modifier = Modifier.size(48.dp).testTag("buffering-indicator").semantics {
            contentDescription = description
        },
        color = PlayerColors.Accent,
    )
}
