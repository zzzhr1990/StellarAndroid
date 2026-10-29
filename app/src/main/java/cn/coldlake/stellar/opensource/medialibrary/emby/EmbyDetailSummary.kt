package cn.coldlake.stellar.opensource.medialibrary.emby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import cn.coldlake.stellar.opensource.medialibrary.catalog.presentationData
import cn.coldlake.stellar.opensource.medialibrary.catalog.MediaPresentationData
import cn.coldlake.stellar.opensource.cache.cacheNamespace

/** Two intrinsic-height groups; the caller keeps page identity, requests and scroll state. */
@Composable internal fun EmbyDetailSummary(state: EmbyBrowserState, controller: EmbyBrowserController,
    onPlay: (EmbyPlayback) -> Unit, wide: Boolean, actionWidth: Dp, onMore: () -> Unit) {
    val item = state.detail ?: return
    val data = remember(item, state.profile) { item.presentationData(state.profile?.cacheNamespace().orEmpty()) }
    val target = if (item.playable) item else state.nextEpisode
    val enabled = !state.loading && !state.preparing && state.error == null && !state.unsupported && target != null
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        val resume = target?.resumeMs ?: 0
        MediaDetailPrimaryControls(data, modifier.testTag("emby-detail-controls"), item.logoTag.isNotBlank(),
            positionMs = resume, durationMs = target?.durationMs,
            playVisible = item.playable || item.type in setOf("Series", "Season"), playEnabled = enabled,
            playTag = if (resume > 0) "emby-resume" else "emby-play", onPlay = { controller.play(true, onPlay) }, title = {
            if (!wide) EmbyDetailEpisode(item, target, controller)
            }, actions = {
                val clear = item.played || item.resumeMs > 0
                MediaDetailIcon(R.drawable.material_symbol_visibility_off,
                    if (clear) R.string.emby_detail_clear_progress else R.string.emby_ui_mark_played,
                    "emby-detail-watched", Modifier.weight(1f), !state.loading && !state.preparing,
                    selected = item.played, onClick = { controller.flag(false, if (clear) false else true) })
                MediaDetailIcon(R.drawable.ic_detail_download, R.string.emby_detail_download_unavailable,
                    "emby-download", Modifier.weight(1f), enabled = false, onClick = {})
                MediaDetailIcon(R.drawable.material_symbol_favorite,
                    if (item.favorite) R.string.emby_ui_favorite_remove else R.string.emby_ui_favorite_add,
                    "emby-detail-favorite", Modifier.weight(1f), !state.loading && !state.preparing,
                    selected = item.favorite, onClick = { controller.flag(true) })
                MediaDetailIcon(R.drawable.material_symbol_more_vert, R.string.emby_ui_more,
                    "emby-options", Modifier.weight(1f), onClick = onMore)
            }, footer = {
            if (state.unsupported) Text(stringResource(R.string.emby_unsupported), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("emby-unsupported"))
        })
    }
    MediaDetailSummary(wide, actionWidth, controls) { modifier ->
        Column(modifier.testTag("emby-detail-information"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wide) EmbyDetailEpisode(item, target, controller)
            EmbyDetailInformation(data, item, state.selectedVersion, controller, state.ratings, state.ratingsForSeries)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EmbyDetailInformation(data: MediaPresentationData, item: EmbyItem, selectedVersion: String?, controller: EmbyBrowserController,
    ratings: List<cn.coldlake.stellar.opensource.medialibrary.catalog.LibraryRating>, ratingsForSeries: Boolean, modifier: Modifier = Modifier) {
    val source = item.sources.firstOrNull { it.id == selectedVersion } ?: item.sources.firstOrNull()
    val video = source?.tracks?.firstOrNull { it.type == "Video" }
    MediaDetailInformation(data, modifier, "emby-overview", "emby-full-overview",
        overviewFallback = item.tagline, onGenre = { genre -> controller.navigate(
            EmbyLocation(EmbySection.RELATED, title = genre, genre = genre)) }, metadata = {
            val baseline = Modifier.alignByBaseline()
            val duration = item.durationMs
            if (duration != null && duration > 0) Text(if (duration >= 3600000)
                stringResource(R.string.emby_detail_duration, duration / 3600000, duration / 60000 % 60)
                else stringResource(R.string.emby_minutes, duration / 60000), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.year != null) Text(item.year.toString(), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (item.premiere.isNotBlank()) Text(embyDate(item.premiere), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.officialRating.isNotBlank()) MediaDetailCertification(item.officialRating, baseline)
            video?.let { track ->
                embyVideoBadges(track).forEach { badge -> Text(badge, baseline, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (item.type == "Series" && item.seasonCount > 0) Text(pluralStringResource(R.plurals.emby_ui_season_count,
                item.seasonCount, item.seasonCount), baseline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, ratings = {
            MediaDetailRatings(ratings.ifEmpty { embyFallbackRatings(item) }, Modifier.testTag("emby-detail-ratings"), series = ratingsForSeries)

    })
}

@Composable private fun EmbyDetailEpisode(item: EmbyItem, target: EmbyItem?, controller: EmbyBrowserController) {
    val episode = if (item.type == "Episode") item else target?.takeIf { it.type == "Episode" }
    val text = when {
        episode?.seasonIndex != null && episode.index != null -> stringResource(R.string.detail_episode_title, episode.seasonIndex, episode.index, episode.name)
        episode != null -> episode.name
        item.type == "Season" -> item.name
        else -> return
    }
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("emby-detail-episode").then(
            if (item.seriesId.isNotBlank()) Modifier.clickable { controller.select(EmbyItem(item.seriesId, item.seriesName, "Series", true)) }
            else Modifier))
}

internal fun embyFallbackRatings(item: EmbyItem) = buildList {
    item.rating?.let { add(cn.coldlake.stellar.opensource.medialibrary.catalog.LibraryRating("community", it)) }
    item.criticRating?.let { add(cn.coldlake.stellar.opensource.medialibrary.catalog.LibraryRating("critic", it, 100.0)) }
}

/** Source capabilities only. Bit depth alone never implies HDR or Dolby Vision. */
internal fun embyVideoBadges(track: EmbyTrack): List<String> = buildList {
    when {
        track.width >= 7600 || track.height >= 4320 -> add("8K")
        track.width >= 3800 || track.height >= 2160 -> add("4K")
        track.width >= 1900 || track.height >= 1080 -> add("1080p")
        track.width >= 1260 || track.height >= 720 -> add("720p")
        track.height > 0 -> add("${track.height}p")
    }
    val range = "${track.videoRange} ${track.videoRangeType} ${track.extendedVideoType}".uppercase(java.util.Locale.ROOT)
    val dolby = track.dvProfile > 0 || "DOVI" in range || "DOLBY" in range
    when {
        "HDR10+" in range || "HDR10PLUS" in range -> add("HDR10+")
        "HDR10" in range -> add("HDR10")
        "HLG" in range || "HYPERLOGGAMMA" in range || track.colorTransfer.equals("arib-std-b67", true) -> add("HLG")
        "HDR" in range || track.colorTransfer.equals("smpte2084", true) -> add("HDR")
    }
    if (dolby) add("Dolby Vision")
}
