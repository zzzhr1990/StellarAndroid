package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.BitmapImage
import coil3.request.allowHardware
import coil3.request.ImageRequest
import coil3.size.Precision
import cn.coldlake.stellar.opensource.cache.AppCaches
import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyItem
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import kotlinx.coroutines.*

@Composable internal fun LibraryPoster(card: CatalogCard, runtime: LocalLibraryRuntime, modifier: Modifier = Modifier,
    onColor: ((Int) -> Unit)? = null, scale: ContentScale = ContentScale.Crop, alignment: Alignment = Alignment.Center, wholeImageColor: Boolean = false) {
    val serverArtwork = remember(card.poster) { card.poster?.takeIf { it.startsWith("server-artwork:") }?.split(':',limit=7) }
    val profile = if(serverArtwork!=null) runtime.serverProfiles.collectAsState().value[serverArtwork.getOrNull(1)] else null
    // A different work/source/account must never inherit the previous painter or its color sample.
    key(card.id, card.poster, profile) {
        LibraryPosterImage(card, runtime, modifier, onColor, scale, alignment, serverArtwork, profile, wholeImageColor)
    }
}

@Composable private fun LibraryPosterImage(card: CatalogCard, runtime: LocalLibraryRuntime, modifier: Modifier,
    onColor: ((Int) -> Unit)?, scale: ContentScale, alignment: Alignment, serverArtwork: List<String>?,
    profile: cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile?, wholeImageColor: Boolean) {
    val context = LocalContext.current
    val publicArtwork = remember(card.poster) { card.poster?.let(PresentationArtwork::parse) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val sampleColor = onColor != null
    val request by produceState<ImageRequest?>(null, card.poster, card.posterVersion, size, sampleColor, profile, scale) {
        val previous = value
        val poster = card.poster ?: return@produceState
        if (size.width <= 0 || size.height <= 0) return@produceState
        val next = try { withContext(Dispatchers.IO) {
            if (poster.startsWith("server-artwork:")) {
                val parts = serverArtwork ?: return@withContext null
                val profile = profile ?: return@withContext null
                if (parts.size != 7 || profile.embyServerId != parts[2] || profile.embyUserId != parts[3] || profile.updatedAt.toString() != parts[4]) return@withContext null
                val typed = parts[6].split('/', limit = 2)
                val type = if (typed.size == 2) typed[0] else "Primary"
                if (type !in setOf("Primary", "Backdrop", "Logo")) return@withContext null
                val tag = typed.last()
                requireNotNull(AppCaches.current).images.request(profile, !MediaSecuritySettings(context).ignoreCertificateErrors,
                    EmbyItem(parts[5], "", "Movie", false, imageTag = tag, backdropTags = if (type == "Backdrop") listOf(tag) else emptyList(), logoTag = if (type == "Logo") tag else ""),
                    size.width, size.height, type = type, software = sampleColor)
            } else if (poster.startsWith("stellar-artwork:")) {
                val url = runtime.metadata.provider.artwork(poster.removePrefix("stellar-artwork:"))
                requireNotNull(AppCaches.current).images.libraryRequest(poster, card.posterVersion, url, size.width, size.height, software = sampleColor)
            } else if (publicArtwork != null) {
                requireNotNull(AppCaches.current).images.presentationRequest(publicArtwork,
                    size.width, size.height, fit = scale == ContentScale.Fit, software = sampleColor)
            } else {
                val width = cn.coldlake.stellar.opensource.cache.AppImageLoader.widthBucket(size.width.toDouble())
                val height = (width * size.height.toDouble() / size.width).toInt().coerceAtLeast(1)
                val data: Any = when {
                    NasLocator.isNas(poster) || cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(poster) -> NasArtwork(poster, card.posterVersion)
                    else -> poster
                }
                val key = "library:$poster:${card.posterVersion}:$width:$height"
                ImageRequest.Builder(context).data(data).diskCacheKey(key).memoryCacheKey(if (sampleColor) "$key:sw:1" else key)
                    .allowHardware(!sampleColor)
                    .size(width, height).precision(Precision.INEXACT).apply { extras[cn.coldlake.stellar.opensource.cache.libraryImageExtra] = key }.build()
            }
        } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        // Keep the old request while resolving the URL; reuse its cached painter during a size/version update.
        if (next != null) value = next.newBuilder().placeholderMemoryCacheKey(previous?.memoryCacheKey).build()
    }
    var bitmap by remember(request) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var displayed by remember { mutableStateOf<androidx.compose.ui.graphics.painter.Painter?>(null) }
    val callback by rememberUpdatedState(onColor)
    LaunchedEffect(request, bitmap, wholeImageColor) {
        val image = bitmap ?: return@LaunchedEffect
        val color = requireNotNull(AppCaches.current).images.color(request ?: return@LaunchedEffect, image, wholeImage = wholeImageColor)
        callback?.invoke(color)
    }
    Box(modifier.onSizeChanged {
        if (it.width > 0 && it.height > 0) {
            if (publicArtwork != null) {
                // Preserve the public role's network variant and bucket decode dimensions too.
                // Small layout changes within a bucket share both the request and sampled color.
                val width = publicArtwork.widthBucket(it.width.toDouble())
                val height = cn.coldlake.stellar.opensource.cache.AppImageLoader.widthBucket(width * it.height.toDouble() / it.width)
                size = IntSize(width, height)
            } else {
                val width = cn.coldlake.stellar.opensource.cache.AppImageLoader.widthBucket(it.width.toDouble())
                // Aspect ratio follows the fixed image slot; one-pixel measurement changes share a request.
                val height = (width * it.height.toDouble() / it.width).toInt().coerceAtLeast(1)
                size = IntSize(width, height)
            }
        }
    }) {
        request?.let { AsyncImage(it, contentDescription = null, imageLoader = requireNotNull(AppCaches.current).images.loader,
            transform = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> state.copy(painter = displayed ?: state.painter)
                    is AsyncImagePainter.State.Error -> state.copy(painter = displayed ?: state.painter)
                    else -> state
                }
            },
            onState = { state -> if (state is AsyncImagePainter.State.Success) {
                displayed = state.painter
                if (sampleColor) bitmap = (state.result.image as? BitmapImage)?.bitmap
            } },
            contentScale = scale, alignment = alignment, modifier = Modifier.fillMaxSize()) }
    }
}
