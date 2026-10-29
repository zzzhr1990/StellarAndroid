package cn.coldlake.stellar.opensource.appearance

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.network.MAX_IMAGE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest

internal enum class ThemeMode { SYSTEM, LIGHT, DARK }
internal enum class BackgroundStyle { DEFAULT, TONAL, IMAGE }
// Existing persisted indices keep their identity; new themes are appended.
internal const val STELLAR_PALETTE_INDEX = 4
internal const val CUSTOM_PALETTE_INDEX = 5
internal const val GOLDEN_AGE_PALETTE_INDEX = 6
internal const val RED_IMPRESSIONS_PALETTE_INDEX = 7
internal data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val palette: Int = STELLAR_PALETTE_INDEX,
    val customPalette: CustomPalette = CustomPalette(),
    val dynamic: Boolean = false,
    val floatingNavigation: Boolean = true,
    val hideNavigationLabels: Boolean = false,
    val background: BackgroundStyle = BackgroundStyle.TONAL,
    val customFont: Boolean = false,
    val fontFile: String = "",
    val fontName: String = "",
    val imageFile: String = "",
    val imageName: String = "",
)

/** Real application appearance; intentionally separate from historical preview preferences. */
internal class AppearanceStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val directory = File(app.filesDir, "appearance").apply { mkdirs() }
    var state by mutableStateOf(read())
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state = read() }
    fun start() { state = read(); preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun stop() = preferences.unregisterOnSharedPreferenceChangeListener(listener)
    private fun read() = Appearance(
        mode = ThemeMode.entries.getOrElse(preferences.getInt("mode", 2)) { ThemeMode.DARK },
        palette = preferences.getInt("palette", STELLAR_PALETTE_INDEX)
            .takeIf { it in 0..RED_IMPRESSIONS_PALETTE_INDEX } ?: STELLAR_PALETTE_INDEX,
        customPalette = CustomPalette.decode(preferences.getString("custom_palette", null)),
        dynamic = preferences.getBoolean("dynamic", false),
        floatingNavigation = preferences.getBoolean("floating", true),
        hideNavigationLabels = preferences.getBoolean("hide_navigation_labels", false),
        background = BackgroundStyle.entries.getOrElse(preferences.getInt("background", 1)) { BackgroundStyle.TONAL },
        customFont = preferences.getBoolean("custom_font", false),
        fontFile = preferences.getString("font_file", "").orEmpty(),
        fontName = preferences.getString("font_name", "").orEmpty(),
        imageFile = preferences.getString("image_file", "").orEmpty(),
        imageName = preferences.getString("image_name", "").orEmpty(),
    )
    fun mode(value: ThemeMode) = preferences.edit { putInt("mode", value.ordinal) }
    fun palette(value: Int) = preferences.edit {
        putInt("palette", value.coerceIn(0, RED_IMPRESSIONS_PALETTE_INDEX)); putBoolean("dynamic", false)
    }
    fun customPalette(value: CustomPalette) = preferences.edit {
        putString("custom_palette", value.encode())
        putInt("palette", CUSTOM_PALETTE_INDEX)
        putBoolean("dynamic", false)
    }
    fun dynamic(value: Boolean) = preferences.edit { putBoolean("dynamic", value) }
    fun floating(value: Boolean) = preferences.edit { putBoolean("floating", value) }
    fun hideNavigationLabels(value: Boolean) = preferences.edit { putBoolean("hide_navigation_labels", value) }
    fun background(value: BackgroundStyle) = preferences.edit { putInt("background", value.ordinal) }
    fun fontEnabled(value: Boolean) = preferences.edit { putBoolean("custom_font", value) }
    fun file(name: String): File? = name.takeIf { it.isNotBlank() && File(it).name == it }
        ?.let { File(directory, it).takeIf(File::isFile) }

    suspend fun importFont(uri: Uri) {
        val imported = withContext(Dispatchers.IO) {
            val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: app.getString(R.string.hs_app_font)
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                input.readLimited(MAX_FONT_BYTES)
            } ?: error("Font cannot be read")
            require(bytes.size in 12..MAX_FONT_BYTES)
            val signature = bytes.take(4)
            require(signature == listOf<Byte>(0, 1, 0, 0) || signature == "OTTO".toByteArray().toList())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val target = File(directory, "font-$hash.font")
            if (!target.exists()) {
                val temporary = File.createTempFile("font-import-", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    Typeface.createFromFile(temporary) // Validate before committing preferences.
                    check(temporary.renameTo(target))
                } finally { temporary.delete() }
            } else Typeface.createFromFile(target)
            target.name to name
        }
        preferences.edit {
            putString("font_file", imported.first); putString("font_name", imported.second)
            putBoolean("custom_font", true)
        }
    }

    suspend fun importImage(uri: Uri) {
        val imported = withContext(Dispatchers.IO) {
            val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: app.getString(R.string.ap_background_image)
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_IMAGE_BYTES) }
                ?: error("Image cannot be read")
            require(bytes.size <= MAX_IMAGE_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            var bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }))
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val transform = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (!transform.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val target = File(directory, "background-$hash.jpg")
            try {
                if (!target.exists()) {
                    val temporary = File.createTempFile("image-import-", ".tmp", directory)
                    try {
                        temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                        check(temporary.renameTo(target))
                    } finally { temporary.delete() }
                }
            } finally { bitmap.recycle() }
            target.name to name
        }
        preferences.edit {
            putString("image_file", imported.first); putString("image_name", imported.second)
            putInt("background", BackgroundStyle.IMAGE.ordinal)
        }
    }

    companion object {
        const val PREFERENCES = "appearance_v1"
        private const val MAX_FONT_BYTES = 20 * 1024 * 1024
    }
}

private fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable
internal fun rememberAppearanceStore(context: Context): AppearanceStore {
    val store = remember(context.applicationContext) { AppearanceStore(context.applicationContext) }
    DisposableEffect(store) { store.start(); onDispose { store.stop() } }
    return store
}
