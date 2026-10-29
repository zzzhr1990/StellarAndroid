package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection

/** Hills' hard-stop background, using the existing saved playback position only. */
@Composable
internal fun PlaybackProgressButton(
    onClick: () -> Unit,
    positionMs: Long,
    durationMs: Long?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (positionMs > 0 && durationMs != null && durationMs > 0)
        (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else 0f
    // Flutter Color.lerp(primary, surface, .15) interpolates sRGB channels.
    val mixed = colors.surface.copy(alpha = .15f).compositeOver(colors.primary)
    // Keep the watched portion darker in both local light and dark palettes.
    val primaryIsDarker = colors.primary.luminance() <= mixed.luminance()
    val played = if (primaryIsDarker) colors.primary else mixed
    val remaining = if (primaryIsDarker) mixed else colors.primary
    val disabled = ButtonDefaults.buttonColors().disabledContainerColor
    val shape = ButtonDefaults.shape
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = colors.onPrimary,
            disabledContainerColor = Color.Transparent,
        ),
        modifier = modifier.clip(shape).drawBehind {
            drawRect(if (!enabled) disabled else if (fraction > 0f) remaining else colors.primary)
            if (enabled && fraction > 0f) {
                val width = size.width * fraction
                drawRect(played, topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                    size = Size(width, size.height))
            }
        },
        content = content,
    )
}
