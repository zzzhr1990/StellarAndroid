package cn.coldlake.stellar.opensource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.ui.PlayerColors
import com.stellar.player.ui.R as PlayerR
import kotlinx.coroutines.delay

/** Expire only the feedback; playback and progress reporting retain the actual seek result. */
@Composable
internal fun SeekFailureFeedback(sessionId: Long, failedSeekId: Long?, modifier: Modifier = Modifier) {
    var visible by rememberSaveable(sessionId, failedSeekId) { mutableStateOf(failedSeekId != null) }
    LaunchedEffect(sessionId, failedSeekId) {
        if (failedSeekId != null) {
            delay(3_500)
            visible = false
        }
    }
    if (visible) Text(stringResource(PlayerR.string.seek_failed), color = Color.White, fontSize = 12.sp,
        modifier = modifier.background(PlayerColors.Panel).padding(8.dp).testTag("seek-result"))
}
