package com.stellar.player.contract

/** App-owned settings. No filter syntax or native types cross the playback boundary. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandsDb: List<Float> = List(10) { 0f },
) {
    fun isValid() = preampDb.isFinite() && preampDb in -24f..0f && bandsDb.size == 10 &&
        bandsDb.all { it.isFinite() && it in -12f..12f }

    companion object {
        val frequenciesHz = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }
}

enum class EqualizerPreset(val bandsDb: List<Float>) {
    FLAT(List(10) { 0f }),
    BASS(listOf(4f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
    VOICE(listOf(-3f, -3f, -2f, -1f, 1f, 3f, 3f, 1f, 0f, -1f)),
    BRIGHT(listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 3f)),
}
