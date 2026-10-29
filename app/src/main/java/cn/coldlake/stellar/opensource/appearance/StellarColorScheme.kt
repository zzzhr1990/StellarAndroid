package cn.coldlake.stellar.opensource.appearance

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Stellar Indigo: #0088FF actions, muted blue-gray / violet accents, neutral surfaces.
// Both appearances preserve the brand blue. See docs/design/APPEARANCE.md.
private val StellarLight = lightColorScheme(
    primary = Color(0xFF0088FF), onPrimary = Color(0xFF001A33),
    primaryContainer = Color(0xFFDCEBFF), onPrimaryContainer = Color(0xFF00325C),
    inversePrimary = Color(0xFF6EA8E6),
    secondary = Color(0xFF426C9B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEAFA), onSecondaryContainer = Color(0xFF25476C),
    tertiary = Color(0xFF6E5CE6), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE7F7), onTertiaryContainer = Color(0xFF463660),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE0E3E9), onSurfaceVariant = Color(0xFF545D6B),
    surfaceTint = Color(0xFF0088FF),
    inverseSurface = Color(0xFF2B2F37), inverseOnSurface = Color(0xFFF1F3F7),
    outline = Color(0xFF727B89), outlineVariant = Color(0xFFC8CDD5),
    surfaceBright = Color(0xFFFAFAFA), surfaceDim = Color(0xFFDADDE3),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F5F7),
    surfaceContainer = Color(0xFFEEEFF2), surfaceContainerHigh = Color(0xFFE7E9EE),
    surfaceContainerHighest = Color(0xFFE0E3E9),
    primaryFixed = Color(0xFFDCEBFF), primaryFixedDim = Color(0xFFA9D1FF),
    onPrimaryFixed = Color(0xFF001A33), onPrimaryFixedVariant = Color(0xFF00325C),
    secondaryFixed = Color(0xFFDEEAFA), secondaryFixedDim = Color(0xFFB2CAE8),
    onSecondaryFixed = Color(0xFF10243A), onSecondaryFixedVariant = Color(0xFF25476C),
    tertiaryFixed = Color(0xFFEDE7F7), tertiaryFixedDim = Color(0xFFD3C4EB),
    onTertiaryFixed = Color(0xFF1A102D), onTertiaryFixedVariant = Color(0xFF463660),
)

private val StellarDark = darkColorScheme(
    primary = Color(0xFF0088FF), onPrimary = Color(0xFF001A33),
    primaryContainer = Color(0xFF17334F), onPrimaryContainer = Color(0xFFBDD9F5),
    inversePrimary = Color(0xFF005DB8),
    secondary = Color(0xFF6096D2), onSecondary = Color(0xFF10243A),
    secondaryContainer = Color(0xFF1E3552), onSecondaryContainer = Color(0xFFB5CDEC),
    tertiary = Color(0xFF9685C7), onTertiary = Color(0xFF1A102D),
    tertiaryContainer = Color(0xFF332D43), onTertiaryContainer = Color(0xFFCCC0E0),
    background = Color(0xFF121212), onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF121212), onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF343943), onSurfaceVariant = Color(0xFFB7BDC8),
    surfaceTint = Color(0xFF0088FF),
    inverseSurface = Color(0xFFE2E4E9), inverseOnSurface = Color(0xFF2B2F37),
    outline = Color(0xFF747C8B), outlineVariant = Color(0xFF3D424C),
    surfaceBright = Color(0xFF363941), surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0E0F11), surfaceContainerLow = Color(0xFF1C1E24),
    surfaceContainer = Color(0xFF23262D), surfaceContainerHigh = Color(0xFF2B2F37),
    surfaceContainerHighest = Color(0xFF343943),
    primaryFixed = Color(0xFFDCEBFF), primaryFixedDim = Color(0xFFA9D1FF),
    onPrimaryFixed = Color(0xFF001A33), onPrimaryFixedVariant = Color(0xFF00325C),
    secondaryFixed = Color(0xFFDEEAFA), secondaryFixedDim = Color(0xFFB2CAE8),
    onSecondaryFixed = Color(0xFF10243A), onSecondaryFixedVariant = Color(0xFF25476C),
    tertiaryFixed = Color(0xFFEDE7F7), tertiaryFixedDim = Color(0xFFD3C4EB),
    onTertiaryFixed = Color(0xFF1A102D), onTertiaryFixedVariant = Color(0xFF463660),
)

internal fun stellarColorScheme(dark: Boolean) = if (dark) StellarDark else StellarLight
