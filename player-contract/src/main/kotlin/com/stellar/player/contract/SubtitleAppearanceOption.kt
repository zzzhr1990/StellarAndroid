package com.stellar.player.contract

/** Shared appearance preferences. Names are persisted keys; bounds are product limits. */
enum class SubtitleAppearanceOption(
    val defaultValue: String,
    val range: ClosedFloatingPointRange<Float>? = null, val step: Float = 1f,
    val choices: List<String> = emptyList(), val toggle: Boolean = false,
) {
    SCALE("1", .5f..3f, .1f),
    SIZE("video", choices = listOf("video", "fit", "surface")),
    CC("1", toggle = true),
    FONT_SIZE("22", 8f..72f),
    SPACING("0", -5f..20f, .5f),
    BOLD("0", toggle = true),
    ITALIC("0", toggle = true),
    COLOR("0xffffffff", choices = listOf("0xffffffff", "0xffff00ff", "0xff0000ff", "0x00ff00ff", "0x00ffffff", "0x000000ff")),
    OUTLINE_COLOR("0x000000ff", choices = listOf("0x000000ff", "0xffffffff", "0xffff00ff", "0xff0000ff", "0x00ff00ff", "0x00ffffff")),
    BACKGROUND_COLOR("0", choices = listOf("0", "0x00000080", "0x000000ff", "0xffffff80", "0xffffffff")),
    BORDER("1.2", 0f..10f, .1f),
    SHADOW("0", -1f..10f, .5f),
    BOX("0", -1f..10f, .5f),
    ALIGNMENT_X("0", choices = listOf("-1", "0", "1")),
    ALIGNMENT_Y("1", choices = listOf("-1", "0", "1")),
    MARGIN_X("10", 0f..200f),
    MARGIN_Y("10", 0f..200f),
    BLUR("0", 0f..10f, .5f);

    fun validated(value: String?): String {
        if (toggle) return value?.takeIf { it == "0" || it == "1" } ?: defaultValue
        if (choices.isNotEmpty()) return value?.takeIf { it in choices } ?: defaultValue
        val number = value?.toFloatOrNull()?.takeIf { it.isFinite() && it in requireNotNull(range) }
            ?: return defaultValue
        val snapped = kotlin.math.round(number / step) * step
        return java.math.BigDecimal(snapped.toString()).setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }
}
