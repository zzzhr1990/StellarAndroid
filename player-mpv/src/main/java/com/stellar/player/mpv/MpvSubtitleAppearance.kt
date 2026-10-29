package com.stellar.player.mpv

import com.stellar.player.contract.SubtitleAppearanceOption
import com.stellar.player.contract.SubtitleAppearanceOption.*

/** Translate the shared product settings; ASS keeps authored styles, bitmap subs only scale. */
internal fun mpvSubtitleAppearance(values: Map<SubtitleAppearanceOption, String>, closedCaption: Boolean = false): Map<String, String> {
    fun value(option: SubtitleAppearanceOption) = option.validated(values[option])
    fun flag(option: SubtitleAppearanceOption) = if (value(option) == "1") "yes" else "no"
    fun color(option: SubtitleAppearanceOption): String {
        val rgba = value(option).removePrefix("0x").padStart(8, '0')
        return "#${rgba.takeLast(2)}${rgba.take(6)}" // Stored RGBA -> mpv ARGB, including transparent black.
    }
    val area = value(SIZE)
    val shadow = value(SHADOW).toFloat()
    val box = value(BOX).toFloat()
    val backgroundBox = shadow < 0 || (box < 0 && shadow > 0)
    val padding = if (box < 0) shadow.coerceAtLeast(0f) else box
    return linkedMapOf(
        "sub-ass-override" to "scale",
        "sub-scale" to value(SCALE),
        "sub-use-margins" to if (area == "video") "no" else "yes",
        "sub-scale-with-window" to if (area == "surface") "yes" else "no",
        "sub-scale-by-window" to "yes",
        "sub-visibility" to if (closedCaption && value(CC) == "0") "no" else "yes",
        "sub-font-size" to value(FONT_SIZE),
        "sub-spacing" to value(SPACING),
        "sub-bold" to flag(BOLD), "sub-italic" to flag(ITALIC),
        "sub-color" to color(COLOR), "sub-outline-color" to color(OUTLINE_COLOR),
        "sub-back-color" to color(BACKGROUND_COLOR),
        "sub-outline-size" to value(BORDER), "sub-blur" to value(BLUR),
        "sub-border-style" to if (backgroundBox) "background-box" else "outline-and-shadow",
        "sub-shadow-offset" to (if (backgroundBox) padding else shadow).toString(),
        "sub-align-x" to when (value(ALIGNMENT_X)) { "-1" -> "left"; "1" -> "right"; else -> "center" },
        "sub-align-y" to when (value(ALIGNMENT_Y)) { "-1" -> "top"; "0" -> "center"; else -> "bottom" },
        "sub-margin-x" to value(MARGIN_X), "sub-margin-y" to value(MARGIN_Y),
    )
}
