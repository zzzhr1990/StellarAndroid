package cn.coldlake.stellar.opensource.appearance

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Custom colors keep persisted index 5 and are rendered from the user's saved palette.
internal val palettePreview = mapOf(
    0 to Color(0xFFC2C1FF), 1 to Color(0xFFAAC7FF),
    2 to Color(0xFFA8D5BA), 3 to Color(0xFFF3B7C8),
    STELLAR_PALETTE_INDEX to stellarColorScheme(dark = true).primary,
    GOLDEN_AGE_PALETTE_INDEX to goldenAgeColorScheme(dark = true).primary,
    RED_IMPRESSIONS_PALETTE_INDEX to redImpressionsColorScheme(dark = true).primary,
)
internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalAppearanceDark = staticCompositionLocalOf { true }

/** Native Material 3 palettes. Presets are our design, not recovered Hills constants. */
internal fun presetScheme(index: Int, dark: Boolean): ColorScheme {
    if (index == STELLAR_PALETTE_INDEX) return stellarColorScheme(dark)
    if (index == GOLDEN_AGE_PALETTE_INDEX) return goldenAgeColorScheme(dark)
    if (index == RED_IMPRESSIONS_PALETTE_INDEX) return redImpressionsColorScheme(dark)
    val primary = if (dark) palettePreview.getValue(index.coerceIn(0, 3)) else
        listOf(Color(0xFF565A92), Color(0xFF415F91), Color(0xFF346B50), Color(0xFF8E495F))[index.coerceIn(0, 3)]
    // Tone 20 of each preset's primary hue/chroma. Hills uses onPrimary for volume boost.
    val onPrimary = if (dark)
        listOf(Color(0xFF2B2A5E), Color(0xFF0C305F), Color(0xFF0C3825), Color(0xFF4B2432))[index.coerceIn(0, 3)]
    else Color.White
    val neutral = if (dark) Color(0xFF131318) else Color(0xFFFAF8FC)
    val background = if (index == 0) neutral else lerp(neutral, primary, if (dark) .025f else .015f)
    val onBackground = if (dark) Color(0xFFE4E1E9) else Color(0xFF1B1B21)
    val container = if (index == 0 && dark) Color(0xFF292D48) else
        lerp(background, primary, if (dark) .18f else .15f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = onBackground,
        secondary = primary, onSecondary = if (dark) Color(0xFF202333) else Color.White,
        secondaryContainer = container, onSecondaryContainer = if (dark) Color(0xFFDFE0FC) else Color(0xFF23263B),
        tertiary = primary, onTertiary = if (dark) Color(0xFF202333) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = onBackground,
        background = background, onBackground = onBackground, surface = background, onSurface = onBackground,
        surfaceVariant = if (dark) Color(0xFF34343C) else Color(0xFFE6E2EC),
        onSurfaceVariant = if (dark) Color(0xFFC7C5D0) else Color(0xFF48464F),
        outline = if (dark) Color(0xFF92919A) else Color(0xFF77757F),
        outlineVariant = if (dark) Color(0xFF3A3941) else Color(0xFFCBC6D1),
        surfaceContainerLowest = if (dark) Color(0xFF0E0E13) else Color.White,
        surfaceContainerLow = if (dark) lerp(background, Color.White, .015f) else Color(0xFFF4F1F7),
        surfaceContainer = if (dark) lerp(background, Color.White, .035f) else Color(0xFFEFECF2),
        surfaceContainerHigh = if (dark) lerp(background, Color.White, .07f) else Color(0xFFE9E6EC),
        surfaceContainerHighest = if (dark) lerp(background, Color.White, .10f) else Color(0xFFE3E0E6),
    )
}

@Composable
internal fun AppearanceTheme(store: AppearanceStore, content: @Composable () -> Unit) {
    val value = store.state
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = when (value.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Refresh wallpaper-derived colors after returning from Android personalization.
    val owner = LocalLifecycleOwner.current
    var systemRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) systemRevision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val scheme = remember(value.dynamic, value.palette, value.customPalette, dark, configuration, systemRevision) {
        if (value.dynamic && Build.VERSION.SDK_INT >= 31) {
            runCatching { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
                .getOrElse { appearanceColorScheme(value, dark) }
        } else appearanceColorScheme(value, dark)
    }
    val colors = if (value.background == BackgroundStyle.DEFAULT) scheme.copy(
        background = if (dark) Color(0xFF121212) else Color(0xFFFAFAFA),
        surface = if (dark) Color(0xFF121212) else Color(0xFFFAFAFA),
    ) else scheme
    val family = remember(value.customFont, value.fontFile) {
        if (value.customFont) store.file(value.fontFile)?.let { file ->
            runCatching { FontFamily(Font(file)) }.getOrNull()
        } else null
    }
    val typography = remember(family) {
        // The font-family constructor also covers Expressive's emphasized text styles.
        if (family == null) Typography() else Typography(fontFamily = family)
    }
    CompositionLocalProvider(LocalAppearance provides value, LocalAppearanceDark provides dark) {
        MaterialExpressiveTheme(colorScheme = colors, typography = typography, content = content)
    }
}
