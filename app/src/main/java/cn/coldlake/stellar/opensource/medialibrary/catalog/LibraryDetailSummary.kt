package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun LibraryDetailSummary(data: MediaPresentationData, header: HomeCard, rich: LibraryDetails, chosen: HomeVersion?,
    selectionFailed: Boolean, wide: Boolean, actionWidth: Dp,
    ratings: List<LibraryRating> = rich.ratings, ratingsForSeries: Boolean = false,
    onMore: () -> Unit, onWatched: () -> Unit, onPlay: (CatalogCard, Long) -> Unit) {
    val version = chosen
    val position = version?.takeIf { it.resumeEligible }?.position ?: 0L
    MediaDetailSummary(wide, actionWidth, controls = { modifier ->
        MediaDetailPrimaryControls(data, modifier.testTag("home-detail-controls"), rich.logo != null,
            positionMs = position, durationMs = version?.duration?.takeIf { it > 0 },
            playEnabled = version != null && !selectionFailed && CatalogServerLocator.resolved(version.card.uri),
            playTag = "scan-play", onPlay = { version?.let { onPlay(it.card, position) } }, title = {
                if (!wide) LibraryDetailEpisode(version, data)
            }, actions = {
                val clear = version?.let { it.watched || it.position > 0 } == true
                MediaDetailIcon(R.drawable.material_symbol_visibility_off,
                    if (clear) R.string.emby_detail_clear_progress else R.string.emby_ui_mark_played,
                    "home-detail-watched", Modifier.weight(1f), enabled = version != null && !selectionFailed,
                    selected = version?.watched == true, onClick = onWatched)
                MediaDetailIcon(R.drawable.ic_detail_download, R.string.emby_detail_download_unavailable,
                    "home-download", Modifier.weight(1f), enabled = false, onClick = {})
                MediaDetailIcon(R.drawable.material_symbol_more_vert, R.string.emby_ui_more,
                    "home-options", Modifier.weight(1f), enabled = version != null, onClick = onMore)
            }, footer = {
                if (version?.uncertain == true) Text(stringResource(R.string.scan_source_unavailable), style = MaterialTheme.typography.bodySmall)
            })
    }, information = { modifier ->
        Column(modifier.testTag("home-detail-information"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (wide) LibraryDetailEpisode(version, data)
        MediaDetailInformation(data, Modifier, "home-overview", "home-full-overview",
            overviewFallback = rich.tagline.orEmpty(), metadata = {
                val baseline = Modifier.alignByBaseline()
                val premiere = rich.premiere
                if (data.kind != "series" && premiere != null) Text(mediaDetailDate(premiere), baseline,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else data.year?.let { Text(it.toString(), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                data.metadataDurationMs?.takeIf { it > 0 }?.let { duration ->
                    Text(if (duration >= 3_600_000) stringResource(R.string.emby_detail_duration, duration / 3_600_000, duration / 60_000 % 60)
                        else stringResource(R.string.emby_minutes, duration / 60_000), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                rich.officialRating?.let { MediaDetailCertification(it, baseline) }
                Text(stringResource(R.string.home_version_count, header.versions), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, ratings = {
                MediaDetailRatings(ratings, Modifier.testTag("home-ratings"), series = ratingsForSeries)

            })
        }
    })
}

@Composable private fun LibraryDetailEpisode(version: HomeVersion?, data: MediaPresentationData) {
    val season = version?.card?.season ?: data.season ?: return
    val episode = version?.card?.episode ?: data.episode
    Text(if (episode != null) stringResource(R.string.detail_episode_title, season, episode, version?.card?.title ?: data.title)
        else stringResource(R.string.home_season, season), style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("home-detail-episode"))
}
