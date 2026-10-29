package cn.coldlake.stellar.opensource.medialibrary.emby

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cn.coldlake.stellar.opensource.medialibrary.*

internal typealias EmbyStatusBarStyle = MediaStatusBarStyle
internal val LocalEmbyStatusBar = LocalMediaStatusBar
@Composable internal fun EmbyStatusBar(pageKey: Any, overArtwork: Boolean, bodyColor: Color) =
    MediaStatusBar(pageKey, overArtwork, bodyColor)
