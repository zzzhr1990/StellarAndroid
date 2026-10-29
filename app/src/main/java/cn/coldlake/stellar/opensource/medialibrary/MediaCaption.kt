package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/** One-line minimum shared by captions and placeholders, never a glyph-height ceiling. */
@Composable internal fun mediaCaptionMinHeight(style: TextStyle): Dp = with(LocalDensity.current) {
    // These theme roles specify fontSize and lineHeight. Preserve their ratio under
    // Android's nonlinear font scaling, including paragraph ascent/descent rounding.
    // No sample paragraphs or fixed dp height; fallback glyphs may grow naturally.
    // CJK role labels exceed the labelMedium line-height on the device. Reserve at
    // least 1.5 em for both blank and populated captions so horizontal scrolling
    // does not change the row height. Taller fallback fonts are still unconstrained.
    val lineHeightPx = style.fontSize.toPx() * maxOf(style.lineHeight.value / style.fontSize.value, 1.5f)
    (ceil(lineHeightPx).toInt() + 1).toDp()
}
