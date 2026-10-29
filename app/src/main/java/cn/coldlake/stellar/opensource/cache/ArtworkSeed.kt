package cn.coldlake.stellar.opensource.cache

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlin.math.roundToInt

/** Called on Dispatchers.Default using the already decoded image, with bounded work. */
internal fun artworkSeed(bitmap: Bitmap): Int {
    val scale = minOf(1f, 128f / maxOf(bitmap.width, bitmap.height))
    val small = bitmap.scale((bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    val pixels = IntArray(small.width * small.height)
    try { small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height) }
    finally { if (small !== bitmap) small.recycle() }
    // Transparent artwork must not introduce a black seed. Preserve neutral art instead
    // of substituting the library's default Google Blue when no colourful swatch wins.
    val opaque = pixels.filter { it ushr 24 >= 128 }.map { it or 0xff000000.toInt() }.toIntArray()
    if (opaque.isEmpty()) return 0xff777777.toInt()
    val population = QuantizerCelebi.quantize(opaque, 128)
    val fallback = population.maxByOrNull { it.value }?.key ?: opaque[0]
    return Score.score(population, desired = 1, fallbackColorArgb = fallback).first()
}
