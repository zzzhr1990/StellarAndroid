package cn.coldlake.stellar.opensource.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R

@Composable
internal fun rememberAboutLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    val failure = stringResource(R.string.about_link_failed)
    return remember(handler, context, failure) {
        { url ->
            try { handler.openUri(url) }
            catch (_: IllegalArgumentException) { Toast.makeText(context, failure, Toast.LENGTH_LONG).show() }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, failure, Toast.LENGTH_LONG).show() }
            catch (_: SecurityException) { Toast.makeText(context, failure, Toast.LENGTH_LONG).show() }
        }
    }
}

private data class AboutEntry(val title: Int, val tag: String, val subtitle: String? = null, val action: () -> Unit)

@Composable
internal fun AboutPage(onDeviceInfo: () -> Unit, onLicenses: () -> Unit, onVersion: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val openLink = rememberAboutLinkOpener()
    val privacy = stringResource(R.string.about_privacy_url)
    val terms = stringResource(R.string.about_terms_url)
    val website = stringResource(R.string.about_website_url)
    val source = stringResource(R.string.about_source_url)
    val entries = listOf(
        AboutEntry(R.string.hs_version, "VERSION", version, onVersion),
        AboutEntry(R.string.hs_privacy, "PRIVACY") { openLink(privacy) },
        AboutEntry(R.string.about_terms, "TERMS") { openLink(terms) },
        AboutEntry(R.string.about_website, "WEBSITE") { openLink(website) },
        AboutEntry(R.string.about_licenses, "OPEN_SOURCE", action = onLicenses),
        AboutEntry(R.string.about_source, "SOURCE") { openLink(source) },
        AboutEntry(R.string.di_title, "DEVICE_INFO", action = onDeviceInfo),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("about-list")) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_stellar_about), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(120.dp))
            Text(stringResource(R.string.hs_brand), fontSize = 32.sp, lineHeight = 40.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
        }
        entries.forEachIndexed { index, entry ->
            SegmentedListItem(onClick = entry.action,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().testTag("settings-open-${entry.tag}"),
                shapes = ListItemDefaults.segmentedShapes(index, entries.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                supportingContent = entry.subtitle?.let { { Text(it) } },
            ) { Text(stringResource(entry.title), style = MaterialTheme.typography.bodyLargeEmphasized) }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Official gradient and aspect ratio are retained; never tint with the app theme.
            Surface(onClick = { openLink("https://www.themoviedb.org") }, color = Color(0xFF032541),
                shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("about-tmdb")) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.tmdb_logo), contentDescription = stringResource(R.string.about_tmdb_name),
                        modifier = Modifier.width(112.dp).aspectRatio(273.42f / 35.52f))
                }
            }
            Text(stringResource(R.string.about_tmdb_source), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_tmdb_notice), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("about-tmdb-notice"))
        }
    }
}
