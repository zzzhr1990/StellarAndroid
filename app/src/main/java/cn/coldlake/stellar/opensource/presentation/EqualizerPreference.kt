package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.EqualizerSettings

/** Versioned, bounded preference payload; malformed or older data never reaches JNI. */
internal object EqualizerPreference {
    const val KEY = "equalizer.v1"
    fun encode(value: EqualizerSettings): String =
        (listOf(value.enabled.toString(), value.preampDb.toString()) + value.bandsDb.map(Float::toString)).joinToString(",")
    fun decode(text: String?): EqualizerSettings {
        val parts = text?.split(',') ?: return EqualizerSettings()
        if (parts.size != 12) return EqualizerSettings()
        val enabled = parts[0].toBooleanStrictOrNull() ?: return EqualizerSettings()
        val numbers = parts.drop(1).map { it.toFloatOrNull() ?: return EqualizerSettings() }
        return EqualizerSettings(enabled, numbers[0], numbers.drop(1)).takeIf { it.isValid() } ?: EqualizerSettings()
    }
}
