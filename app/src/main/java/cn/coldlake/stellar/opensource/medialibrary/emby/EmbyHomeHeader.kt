package cn.coldlake.stellar.opensource.medialibrary.emby

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.coldlake.stellar.opensource.medialibrary.MediaHomeHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EmbyHomeHeader(state: TopAppBarState, scroll: LazyListState?, hasHero: Boolean,
    pageKey: Any, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    MediaHomeHeader(state, scroll, hasHero, pageKey, modifier, content)
