package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.materialkolor.dynamiccolor.ColorSpec.SpecVersion
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/** Image-local Material roles; never changes the user's application theme. */
internal fun mediaArtworkColorScheme(seed: Int, dark: Boolean, base: ColorScheme): ColorScheme {
    // The older Material spec matches the Flutter generation used by the reference.
    // Do not silently switch to the dependency's newer default spec on upgrade.
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0, SpecVersion.SPEC_2021)
    return base.copy(
        primary = Color(scheme.primary), onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer), onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary), onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer), onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary), onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer), onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background), onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface), onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant), onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint), inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        surfaceDim = Color(scheme.surfaceDim), surfaceBright = Color(scheme.surfaceBright),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest), surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainer = Color(scheme.surfaceContainer), surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        outline = Color(scheme.outline), outlineVariant = Color(scheme.outlineVariant),
        error = Color(scheme.error), onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer), onErrorContainer = Color(scheme.onErrorContainer),
        scrim = Color(scheme.scrim),
        primaryFixed = Color(scheme.primaryFixed), primaryFixedDim = Color(scheme.primaryFixedDim),
        onPrimaryFixed = Color(scheme.onPrimaryFixed), onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
        secondaryFixed = Color(scheme.secondaryFixed), secondaryFixedDim = Color(scheme.secondaryFixedDim),
        onSecondaryFixed = Color(scheme.onSecondaryFixed), onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
        tertiaryFixed = Color(scheme.tertiaryFixed), tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
        onTertiaryFixed = Color(scheme.onTertiaryFixed), onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
    )
}

/** Flutter Color.lerp interpolates sRGB channels; Compose Color.lerp uses Oklab. */
internal fun mediaArtworkBackground(colors: ColorScheme, surfaceFraction: Float): Color =
    Color(ColorUtils.blendARGB(colors.surfaceTint.toArgb(), colors.surface.toArgb(), surfaceFraction))
