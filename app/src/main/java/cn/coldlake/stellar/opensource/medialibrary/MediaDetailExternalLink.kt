package cn.coldlake.stellar.opensource.medialibrary

import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

/** A local, untinted brand mark in the same 40dp circular container as Settings. */
@Composable internal fun MediaDetailExternalLink(name: String, url: String, onClick: () -> Unit) {
    val host = url.toUri().host.orEmpty().lowercase(java.util.Locale.ROOT)
    fun domain(value: String) = host == value || host.endsWith(".$value")
    val (icon, background) = when {
        domain("imdb.com") -> R.drawable.ic_link_imdb to Color(0xFFF5C518)
        domain("themoviedb.org") -> R.drawable.ic_rating_tmdb to Color(0xFF032541)
        domain("thetvdb.com") -> R.drawable.ic_link_tvdb to Color(0xFF173D30)
        domain("trakt.tv") -> R.drawable.ic_link_trakt to Color(0xFF6438D0)
        else -> null to MaterialTheme.colorScheme.secondaryContainer
    }
    TextButton(onClick, Modifier.heightIn(min = 48.dp).testTag("external-link-$host"),
        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
        Box(Modifier.size(40.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) Image(painterResource(icon), null, Modifier.size(when {
                domain("imdb.com") || domain("trakt.tv") -> 40.dp
                domain("thetvdb.com") -> 32.dp
                else -> 28.dp
            }))
            else Icon(painterResource(R.drawable.material_symbol_language), null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(8.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
