package com.stellar.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object PlayerColors {
    val Detail: Color @Composable get() = MaterialTheme.colorScheme.background
    val Panel = Color(0xF2222230)
    val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val Secondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Track = Color(0x66FFFFFF)
}

@Composable
fun PlayerTheme(colorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF2B7DFF), background = Color(0xFF1E1D2B), surface = PlayerColors.Panel,
    onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFACB4C3),
), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme, content = content,
    )
}

fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "--:--"
    val total = milliseconds.coerceAtLeast(0) / 1000
    return if (total >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}
