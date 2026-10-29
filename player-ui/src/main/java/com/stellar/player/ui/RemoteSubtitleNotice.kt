package com.stellar.player.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus

@Composable
internal fun RemoteSubtitleNotice(snapshot: PlaybackSnapshot) {
    Text(stringResource(when {
        snapshot.status == PlaybackStatus.BUFFERING -> R.string.remote_subtitles_loading
        snapshot.remoteSubtitleFailed -> R.string.remote_subtitles_failed
        !snapshot.capabilities.canImportSubtitle -> R.string.remote_subtitles_unavailable
        else -> R.string.remote_subtitles_hint
    }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp).testTag("remote-subtitle-notice"))
}
