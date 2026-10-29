package cn.coldlake.stellar.opensource.appearance

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

internal enum class CustomColorRole { PRIMARY, SECONDARY, TERTIARY, BACKGROUND }

internal data class CustomColors(val primary: Int, val secondary: Int, val tertiary: Int, val background: Int) {
    fun color(role: CustomColorRole): Int = when (role) {
        CustomColorRole.PRIMARY -> primary
        CustomColorRole.SECONDARY -> secondary
        CustomColorRole.TERTIARY -> tertiary
        CustomColorRole.BACKGROUND -> background
    }
    fun withColor(role: CustomColorRole, color: Int): CustomColors = when (role) {
        CustomColorRole.PRIMARY -> copy(primary = color)
        CustomColorRole.SECONDARY -> copy(secondary = color)
        CustomColorRole.TERTIARY -> copy(tertiary = color)
        CustomColorRole.BACKGROUND -> copy(background = color)
    }
    fun values() = listOf(primary, secondary, tertiary, background)
}

private fun stellarColors(dark: Boolean) = stellarColorScheme(dark).let {
    CustomColors(it.primary.toArgb(), it.secondary.toArgb(), it.tertiary.toArgb(), it.background.toArgb())
}

internal data class CustomPalette(
    val light: CustomColors = stellarColors(false),
    val dark: CustomColors = stellarColors(true),
) {
    fun colors(isDark: Boolean) = if (isDark) dark else light
    fun withColor(isDark: Boolean, role: CustomColorRole, color: Int): CustomPalette =
        if (isDark) copy(dark = dark.withColor(role, color)) else copy(light = light.withColor(role, color))
    fun encode() = (light.values() + dark.values()).joinToString(",")

    companion object {
        fun decode(value: String?): CustomPalette {
            val values = value?.split(',')?.map { it.toIntOrNull() } ?: return CustomPalette()
            if (values.size != 8 || values.any { it == null || it ushr 24 != 255 }) return CustomPalette()
            val colors = values.filterNotNull()
            return CustomPalette(CustomColors(colors[0], colors[1], colors[2], colors[3]),
                CustomColors(colors[4], colors[5], colors[6], colors[7]))
        }
    }
}

/** Opaque text/checkmarks use whichever foreground has the greater WCAG contrast. */
internal fun readableOn(color: Color): Color =
    if ((color.luminance() + .05f) / .05f >= 1.05f / (color.luminance() + .05f)) Color.Black else Color.White

/** Unedited roles retain Stellar's exact values, including its original tonal containers. */
internal fun customColorScheme(palette: CustomPalette, dark: Boolean): ColorScheme {
    val colors = palette.colors(dark)
    val original = stellarColors(dark)
    var scheme = stellarColorScheme(dark)
    fun container(color: Color) = lerp(color, if (dark) Color.Black else Color.White, if (dark) .65f else .85f)
    fun fixed(color: Color) = lerp(color, Color.White, .85f)
    fun fixedDim(color: Color) = lerp(color, Color.White, .65f)
    if (colors.primary != original.primary) {
        val color = Color(colors.primary)
        val container = container(color)
        scheme = scheme.copy(primary = color, onPrimary = readableOn(color), surfaceTint = color,
            primaryContainer = container, onPrimaryContainer = readableOn(container),
            inversePrimary = Color(palette.colors(!dark).primary),
            primaryFixed = fixed(color), primaryFixedDim = fixedDim(color),
            onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color.Black)
    }
    if (colors.secondary != original.secondary) {
        val color = Color(colors.secondary)
        val container = container(color)
        scheme = scheme.copy(secondary = color, onSecondary = readableOn(color),
            secondaryContainer = container, onSecondaryContainer = readableOn(container),
            secondaryFixed = fixed(color), secondaryFixedDim = fixedDim(color),
            onSecondaryFixed = Color.Black, onSecondaryFixedVariant = Color.Black)
    }
    if (colors.tertiary != original.tertiary) {
        val color = Color(colors.tertiary)
        val container = container(color)
        scheme = scheme.copy(tertiary = color, onTertiary = readableOn(color),
            tertiaryContainer = container, onTertiaryContainer = readableOn(container),
            tertiaryFixed = fixed(color), tertiaryFixedDim = fixedDim(color),
            onTertiaryFixed = Color.Black, onTertiaryFixedVariant = Color.Black)
    }
    if (colors.background != original.background) {
        val bg = Color(colors.background)
        val foreground = readableOn(bg)
        fun surface(amount: Float): Color {
            val candidate = lerp(bg, foreground, amount)
            val light = maxOf(candidate.luminance(), foreground.luminance())
            val dim = minOf(candidate.luminance(), foreground.luminance())
            return if ((light + .05f) / (dim + .05f) >= 4.5f) candidate else bg
        }
        scheme = scheme.copy(background = bg, onBackground = foreground, surface = bg, onSurface = foreground,
            surfaceVariant = surface(.16f), onSurfaceVariant = foreground,
            surfaceContainerLowest = bg, surfaceContainerLow = surface(.03f), surfaceContainer = surface(.06f),
            surfaceContainerHigh = surface(.09f), surfaceContainerHighest = surface(.12f),
            surfaceBright = surface(.14f), surfaceDim = bg,
            outline = surface(.55f), outlineVariant = surface(.3f),
            inverseSurface = foreground, inverseOnSurface = bg)
    }
    return scheme
}

internal fun appearanceColorScheme(appearance: Appearance, dark: Boolean): ColorScheme =
    if (appearance.palette == CUSTOM_PALETTE_INDEX) customColorScheme(appearance.customPalette, dark)
    else presetScheme(appearance.palette, dark)
