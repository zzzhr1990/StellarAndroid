package cn.coldlake.stellar.opensource.appearance

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearanceBackground(store: AppearanceStore, enabled: Boolean) {
    val appearance = store.state
    val file = if (enabled && appearance.background == BackgroundStyle.IMAGE) store.file(appearance.imageFile) else null
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = null
        if (file != null) value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    image?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag("appearance-background-image"))
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .86f)))
    }
}
