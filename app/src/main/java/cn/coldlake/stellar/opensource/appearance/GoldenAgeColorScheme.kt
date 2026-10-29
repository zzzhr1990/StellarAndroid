package cn.coldlake.stellar.opensource.appearance

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Golden Age: champagne / antique-gold surfaces, gold actions, bronze and olive accents.
private val GoldenAgeLight = lightColorScheme(
    primary = Color(0xFF7A5900), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2D98E), onPrimaryContainer = Color(0xFF342600),
    inversePrimary = Color(0xFFD9B45B),
    secondary = Color(0xFF755C3A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAD8B5), onSecondaryContainer = Color(0xFF423118),
    tertiary = Color(0xFF58653C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE6C6), onTertiaryContainer = Color(0xFF1F2A0C),
    background = Color(0xFFF7E8BE), onBackground = Color(0xFF2B2415),
    surface = Color(0xFFF7E8BE), onSurface = Color(0xFF2B2415),
    surfaceVariant = Color(0xFFE4D4AC), onSurfaceVariant = Color(0xFF62563C),
    surfaceTint = Color(0xFF7A5900),
    inverseSurface = Color(0xFF352D1D), inverseOnSurface = Color(0xFFF5E8C8),
    outline = Color(0xFF847454), outlineVariant = Color(0xFFC8B78C),
    surfaceBright = Color(0xFFFFF2D4), surfaceDim = Color(0xFFDACA9E),
    surfaceContainerLowest = Color(0xFFFFF7E3), surfaceContainerLow = Color(0xFFF2E0AD),
    surfaceContainer = Color(0xFFECD69A), surfaceContainerHigh = Color(0xFFE6CB87),
    surfaceContainerHighest = Color(0xFFDFC075),
    primaryFixed = Color(0xFFF2D98E), primaryFixedDim = Color(0xFFD9B45B),
    onPrimaryFixed = Color(0xFF2A1D00), onPrimaryFixedVariant = Color(0xFF514017),
    secondaryFixed = Color(0xFFEAD8B5), secondaryFixedDim = Color(0xFFCBB48D),
    onSecondaryFixed = Color(0xFF2B210E), onSecondaryFixedVariant = Color(0xFF574224),
    tertiaryFixed = Color(0xFFDDE6C6), tertiaryFixedDim = Color(0xFFBECAA1),
    onTertiaryFixed = Color(0xFF1F2A0C), onTertiaryFixedVariant = Color(0xFF3E4A23),
)

private val GoldenAgeDark = darkColorScheme(
    primary = Color(0xFFD9B45B), onPrimary = Color(0xFF2A1D00),
    primaryContainer = Color(0xFF514017), onPrimaryContainer = Color(0xFFF6DFA6),
    inversePrimary = Color(0xFF7A5900),
    secondary = Color(0xFFC0A47B), onSecondary = Color(0xFF2B210E),
    secondaryContainer = Color(0xFF463821), onSecondaryContainer = Color(0xFFE7D1AA),
    tertiary = Color(0xFFAAB78C), onTertiary = Color(0xFF1F2A0C),
    tertiaryContainer = Color(0xFF3E4A23), onTertiaryContainer = Color(0xFFDDE6C6),
    background = Color(0xFF211B0F), onBackground = Color(0xFFEDE0BE),
    surface = Color(0xFF211B0F), onSurface = Color(0xFFEDE0BE),
    surfaceVariant = Color(0xFF4A402A), onSurfaceVariant = Color(0xFFD0C2A3),
    surfaceTint = Color(0xFFD9B45B),
    inverseSurface = Color(0xFFEDE0BE), inverseOnSurface = Color(0xFF352D1D),
    outline = Color(0xFF9A8C6F), outlineVariant = Color(0xFF4A402A),
    surfaceBright = Color(0xFF4B4029), surfaceDim = Color(0xFF211B0F),
    surfaceContainerLowest = Color(0xFF181309), surfaceContainerLow = Color(0xFF2F2513),
    surfaceContainer = Color(0xFF382D18), surfaceContainerHigh = Color(0xFF42361F),
    surfaceContainerHighest = Color(0xFF4D4028),
    primaryFixed = Color(0xFFF2D98E), primaryFixedDim = Color(0xFFD9B45B),
    onPrimaryFixed = Color(0xFF2A1D00), onPrimaryFixedVariant = Color(0xFF514017),
    secondaryFixed = Color(0xFFEAD8B5), secondaryFixedDim = Color(0xFFCBB48D),
    onSecondaryFixed = Color(0xFF2B210E), onSecondaryFixedVariant = Color(0xFF574224),
    tertiaryFixed = Color(0xFFDDE6C6), tertiaryFixedDim = Color(0xFFBECAA1),
    onTertiaryFixed = Color(0xFF1F2A0C), onTertiaryFixedVariant = Color(0xFF3E4A23),
)

internal fun goldenAgeColorScheme(dark: Boolean) = if (dark) GoldenAgeDark else GoldenAgeLight
