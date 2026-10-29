package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.catalog.MediaPresentationData

/** The same primary action group for local files and server playback targets. */
@Composable internal fun MediaDetailPrimaryControls(
    data: MediaPresentationData, modifier: Modifier, hasLogo: Boolean,
    positionMs: Long, durationMs: Long?, playVisible: Boolean = true, playEnabled: Boolean,
    playTag: String, onPlay: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (title != null) title()
        else if (!hasLogo) Text(data.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (playVisible) PlaybackProgressButton(onPlay, enabled = playEnabled, positionMs = positionMs, durationMs = durationMs,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(playTag)) {
            Text(if (positionMs > 0) stringResource(R.string.emby_resume_at, mediaDetailTime(positionMs))
                else stringResource(R.string.emby_ui_play), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        footer()
    }
}

@Composable internal fun MediaDetailIcon(icon: Int, label: Int, tag: String, modifier: Modifier,
    enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledTonalIconButton(onClick, enabled = enabled, modifier = modifier.height(48.dp).testTag(tag),
        shape = ButtonDefaults.shape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) colors.primaryContainer else colors.onSurface.copy(alpha = .1f),
            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
            disabledContainerColor = colors.onSurface.copy(alpha = .06f), disabledContentColor = colors.onSurface.copy(alpha = .3f))) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp)
            .then(if (icon == R.drawable.material_symbol_more_vert) Modifier.rotate(90f) else Modifier))
    }
}

/** Metadata-only content. File duration/progress belongs exclusively to the play action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MediaDetailInformation(
    data: MediaPresentationData, modifier: Modifier, overviewTag: String, fullOverviewTag: String,
    overviewFallback: String = "", onGenre: ((String) -> Unit)? = null,
    metadata: @Composable FlowRowScope.() -> Unit,
    ratings: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(data.key) { mutableStateOf(false) }
    val compact = LocalMediaDetailLayout.current.portraitHeader
    val overview = data.overview?.takeIf(String::isNotBlank) ?: overviewFallback
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (overview.isNotBlank()) Column(Modifier.fillMaxWidth().clickable { expanded = true }.testTag(overviewTag)) {
            Text(overview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(Modifier.fillMaxWidth().testTag("$overviewTag-metadata"),
            horizontalArrangement = Arrangement.spacedBy(10.dp, if (compact) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = if (compact) 1 else Int.MAX_VALUE) {
            metadata()
            data.genres.forEach { genre -> Text(genre,
                Modifier.alignByBaseline().then(if (onGenre != null) Modifier.clickable { onGenre(genre) } else Modifier),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        ratings()
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        text = { Text(overview, Modifier.verticalScroll(rememberScrollState()).testTag(fullOverviewTag)) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.emby_ui_close)) } })
}

/** The hero has one title: artwork when available, otherwise readable text in the same position. */
@Composable internal fun MediaDetailHeroTitle(title: String, modifier: Modifier) {
    val portrait = LocalMediaDetailLayout.current.portraitHeader
    Box(modifier, contentAlignment = if (portrait) Alignment.Center else androidx.compose.ui.AbsoluteAlignment.CenterLeft) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (portrait) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Left)
    }
}
