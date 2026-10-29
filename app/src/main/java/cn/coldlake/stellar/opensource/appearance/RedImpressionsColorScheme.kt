package cn.coldlake.stellar.opensource.appearance

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Match the blue/green/pink presets' subtle tint over a near-white / near-black page.
private val RedImpressionsLightBackground = lerp(Color(0xFFFAF8FC), Color(0xFFA82435), .015f)
private val RedImpressionsDarkBackground = lerp(Color(0xFF131318), Color(0xFFE68289), .025f)

// Red Impressions: crimson actions, golden-yellow accents, subtly tinted page backgrounds.
private val RedImpressionsLight = lightColorScheme(
    primary = Color(0xFFA82435), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DB), onPrimaryContainer = Color(0xFF671627),
    inversePrimary = Color(0xFFE68289),
    secondary = Color(0xFF7A5B00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E4A9), onSecondaryContainer = Color(0xFF3F3000),
    tertiary = Color(0xFF80553F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6DDC9), onTertiaryContainer = Color(0xFF51311F),
    background = RedImpressionsLightBackground, onBackground = Color(0xFF2D1B1C),
    surface = RedImpressionsLightBackground, onSurface = Color(0xFF2D1B1C),
    surfaceVariant = Color(0xFFECDDDA), onSurfaceVariant = Color(0xFF685153),
    surfaceTint = Color(0xFFA82435),
    inverseSurface = Color(0xFF3D2C30), inverseOnSurface = Color(0xFFFAE9E6),
    outline = Color(0xFF907477), outlineVariant = Color(0xFFD4BEC0),
    surfaceBright = Color(0xFFFFFAF6), surfaceDim = Color(0xFFDECBC6),
    surfaceContainerLowest = Color(0xFFFFFCF8), surfaceContainerLow = Color(0xFFF9EBE5),
    surfaceContainer = Color(0xFFF2E1DA), surfaceContainerHigh = Color(0xFFEAD5CE),
    surfaceContainerHighest = Color(0xFFE2C9C2),
    primaryFixed = Color(0xFFFFD9DB), primaryFixedDim = Color(0xFFEDADB3),
    onPrimaryFixed = Color(0xFF3C0C16), onPrimaryFixedVariant = Color(0xFF742B3B),
    secondaryFixed = Color(0xFFF6E4A9), secondaryFixedDim = Color(0xFFD9BD70),
    onSecondaryFixed = Color(0xFF2A2106), onSecondaryFixedVariant = Color(0xFF5D4814),
    tertiaryFixed = Color(0xFFF6DDC9), tertiaryFixedDim = Color(0xFFDDBBA0),
    onTertiaryFixed = Color(0xFF2C1B10), onTertiaryFixedVariant = Color(0xFF65432B),
)

private val RedImpressionsDark = darkColorScheme(
    primary = Color(0xFFE68289), onPrimary = Color(0xFF3C0C16),
    primaryContainer = Color(0xFF5B2833), onPrimaryContainer = Color(0xFFFFD9DB),
    inversePrimary = Color(0xFFA82435),
    secondary = Color(0xFFD0AE50), onSecondary = Color(0xFF2A2106),
    secondaryContainer = Color(0xFF493A16), onSecondaryContainer = Color(0xFFF2DEA0),
    tertiary = Color(0xFFC99B7F), onTertiary = Color(0xFF2C1B10),
    tertiaryContainer = Color(0xFF493326), onTertiaryContainer = Color(0xFFEBCBB4),
    background = RedImpressionsDarkBackground, onBackground = Color(0xFFF0DCDA),
    surface = RedImpressionsDarkBackground, onSurface = Color(0xFFF0DCDA),
    surfaceVariant = Color(0xFF4B3A3E), onSurfaceVariant = Color(0xFFD4BCC0),
    surfaceTint = Color(0xFFE68289),
    inverseSurface = Color(0xFFF0DCDA), inverseOnSurface = Color(0xFF3D2C30),
    outline = Color(0xFFA78C91), outlineVariant = Color(0xFF554146),
    surfaceBright = Color(0xFF48363B), surfaceDim = RedImpressionsDarkBackground,
    surfaceContainerLowest = Color(0xFF120B0D), surfaceContainerLow = Color(0xFF261B1E),
    surfaceContainer = Color(0xFF332429), surfaceContainerHigh = Color(0xFF403035),
    surfaceContainerHighest = Color(0xFF4C3B40),
    primaryFixed = Color(0xFFFFD9DB), primaryFixedDim = Color(0xFFEDADB3),
    onPrimaryFixed = Color(0xFF3C0C16), onPrimaryFixedVariant = Color(0xFF742B3B),
    secondaryFixed = Color(0xFFF6E4A9), secondaryFixedDim = Color(0xFFD9BD70),
    onSecondaryFixed = Color(0xFF2A2106), onSecondaryFixedVariant = Color(0xFF5D4814),
    tertiaryFixed = Color(0xFFF6DDC9), tertiaryFixedDim = Color(0xFFDDBBA0),
    onTertiaryFixed = Color(0xFF2C1B10), onTertiaryFixedVariant = Color(0xFF65432B),
)

internal fun redImpressionsColorScheme(dark: Boolean) = if (dark) RedImpressionsDark else RedImpressionsLight
