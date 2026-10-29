package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.catalog.LibraryRating
import java.text.NumberFormat
import java.util.Locale

internal fun ratingValue(rating: LibraryRating, locale: Locale): String {
    if (rating.source in setOf("rotten_tomatoes", "critic"))
        return NumberFormat.getPercentInstance(locale).format(rating.value / rating.max)
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = if (rating.max == 10.0) 1 else 0
        maximumFractionDigits = 1
    }
    val value = number.format(rating.value)
    return if (rating.max == 10.0) value else "$value/${number.format(rating.max)}"
}

/** Text attribution keeps every score linked to its actual source and metric. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MediaDetailRatings(ratings: List<LibraryRating>, modifier: Modifier = Modifier, series: Boolean = false) {
    if (ratings.isEmpty()) return
    val displayed = ratings
    val locale = LocalConfiguration.current.locales[0]
    val order = listOf("tmdb/rating", "imdb/rating", "rotten_tomatoes/tomatometer", "rotten_tomatoes/audience", "trakt/rating", "metascore/rating", "mal/rating", "letterboxd/rating")
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp,
        if (LocalMediaDetailLayout.current.portraitHeader) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        displayed.sortedBy { order.indexOf("${it.source}/${it.metric}").takeIf { n -> n >= 0 } ?: Int.MAX_VALUE }.forEach { rating ->
            val label = when (rating.source) {
                "tmdb" -> "TMDB"
                "imdb" -> "IMDb"
                "trakt" -> "Trakt"
                "metascore" -> "Metascore"
                "mal" -> "MyAnimeList"
                "letterboxd" -> "Letterboxd"
                "rotten_tomatoes" -> stringResource(if (rating.metric == "audience") R.string.detail_rating_audience else R.string.detail_rating_tomatometer)
                "critic" -> stringResource(R.string.emby_ui_critic)
                else -> stringResource(R.string.home_rating_community)
            }
            val value = ratingValue(rating, locale)
            val description = stringResource(if (series) R.string.detail_rating_series_description else R.string.detail_rating_description, label, value)
            Row(Modifier.testTag("rating-${rating.source}-${rating.metric}").clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}
