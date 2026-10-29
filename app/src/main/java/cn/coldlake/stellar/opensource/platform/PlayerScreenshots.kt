package cn.coldlake.stellar.opensource.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.PixelCopy
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.stellar.player.contract.MediaKind
import com.stellar.player.contract.PlaybackStatus
import cn.coldlake.stellar.opensource.MainActivity
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.presentation.AppRoute
import android.view.SurfaceView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** One bounded capture at a time. Bitmap export never participates in live video rendering. */
internal class PlayerScreenshots(private val activity: MainActivity) {
    private val vm get() = activity.playerViewModel
    val preferences = ScreenshotPreferences(activity)
    private val storage = ScreenshotStorage(activity.applicationContext)
    var surface: SurfaceView? = null
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set
    private var messageSession: Long? = null
    private var hideMessage: Job? = null
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var folderResult: CompletableDeferred<Boolean>? = null
    private var captureSession: Long? = null
    val awaitingCapturePermission get() = captureSession != null && captureSession == vm.snapshot.value.sessionId &&
        (permissionResult != null || folderResult != null)
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionResult?.complete(it)
    }
    private val folderLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = folderResult ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            val uri = result.data?.data
            val accepted = result.resultCode == Activity.RESULT_OK && uri != null && runCatching {
                withContext(Dispatchers.IO) { storage.rememberFolder(uri, result.data!!.flags, preferences) }
            }.isSuccess
            pending.complete(accepted)
        }
    }
    private fun notify(message: Int, session: Long? = null) {
        hideMessage?.cancel()
        this.message = message
        messageSession = session
        hideMessage = activity.lifecycleScope.launch { delay(3500); this@PlayerScreenshots.message = null }
    }
    fun playerMessage(session: Long): Int? = message.takeIf { messageSession == session }
    fun settingsMessage(): Int? = message.takeIf { messageSession == null }
    fun exportMessage(message: Int, session: Long) { notify(message, session) }

    /** Reserve the existing screenshot gate and finish permission UI before live sampling. */
    suspend fun export(session: Long, sessionUpdated: (Long) -> Unit, block: suspend (Long, android.net.Uri?) -> Unit) {
        check(!busy && folderResult == null)
        busy = true
        captureSession = session
        val destination = preferences.destination
        try {
            if (destination == ScreenshotDestination.FOLDER &&
                (preferences.folder == null || !withContext(Dispatchers.IO) { storage.hasFolderPermission(preferences.folder!!) })) {
                val granted = requestFolder()
                captureSession?.let(sessionUpdated)
                if (!granted) throw SecurityException()
            } else if (destination == ScreenshotDestination.ALBUM && Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                if (!requestLegacyPermission()) throw SecurityException()
            }
            if (!sameSession()) throw CancellationException("Capture session changed")
            block(checkNotNull(captureSession), if (destination == ScreenshotDestination.FOLDER) preferences.folder ?: throw ScreenshotFolderUnavailable() else null)
        } finally { busy = false; captureSession = null }
    }

    fun chooseFolder() {
        if (busy || folderResult != null) return
        activity.lifecycleScope.launch {
            if (!requestFolder()) notify(R.string.screenshot_permission_failed)
        }
    }
    private suspend fun requestFolder(): Boolean {
        val result = CompletableDeferred<Boolean>()
        // A Surface/VO replacement can change the native paused clock. Keep the frame
        // the user was viewing before leaving for permission, rather than that new clock.
        val pausedPosition = vm.snapshot.value.takeIf {
            it.sessionId == captureSession && it.status == PlaybackStatus.PAUSED
        }?.let { it.framePositionMs ?: it.positionMs }
        folderResult = result
        activity.pictureInPicture.suspendAutoEnter(true)
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            preferences.folder?.let { tree -> intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))) }
            folderLauncher.launch(intent)
            val granted = result.await()
            val session = captureSession
            if (session != null) {
                activity.lifecycle.withResumed { }
                // Reopen against the replacement Surface, after the return transition binds it.
                withTimeoutOrNull(3000) {
                    while (sameSession() && (surface?.holder?.surface?.isValid != true || !activity.hasWindowFocus())) delay(16)
                }
                if (sameSession()) captureSession = vm.reopenAfterScreenshotFolder(session, pausedPosition)
            }
            granted
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { folderResult = null; activity.pictureInPicture.suspendAutoEnter(false) }
    }
    private suspend fun requestLegacyPermission(): Boolean {
        val result = CompletableDeferred<Boolean>()
        permissionResult = result
        activity.pictureInPicture.suspendAutoEnter(true)
        return try {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            result.await()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { permissionResult = null; activity.pictureInPicture.suspendAutoEnter(false) }
    }

    fun capture() {
        if (busy || folderResult != null) return
        val snapshot = vm.snapshot.value
        if (snapshot.simulated) { vm.feature(com.stellar.player.ui.PlayerFeature.SNAPSHOT); return }
        if (snapshot.remotePlayback) { notify(R.string.casting_local_only, snapshot.sessionId); return }
        val view = surface
        if (snapshot.media?.kind != MediaKind.VIDEO || snapshot.status !in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.ENDED) ||
            view == null || !view.holder.surface.isValid || view.width <= 0 || view.height <= 0) {
            notify(R.string.screenshot_capture_failed, snapshot.sessionId); return
        }
        val destination = preferences.destination
        busy = true
        captureSession = snapshot.sessionId
        vm.dismissPanel()
        activity.lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = copySurface(view)
                if (!sameSession()) return@launch
                if (destination == ScreenshotDestination.FOLDER &&
                    (preferences.folder == null || !withContext(Dispatchers.IO) { storage.hasFolderPermission(preferences.folder!!) })) {
                    if (!requestFolder()) { notify(R.string.screenshot_permission_failed, captureSession); return@launch }
                } else if (destination == ScreenshotDestination.ALBUM && Build.VERSION.SDK_INT <= 28 &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    if (!requestLegacyPermission()) { notify(R.string.screenshot_permission_failed, captureSession); return@launch }
                }
                if (!sameSession()) return@launch
                val frame = bitmap
                suspend fun save() = withContext(Dispatchers.IO) {
                    storage.save(frame, if (destination == ScreenshotDestination.FOLDER) preferences.folder ?: throw ScreenshotFolderUnavailable() else null)
                }
                try { save() } catch (e: Exception) {
                    if (destination != ScreenshotDestination.FOLDER || (e !is SecurityException && e !is ScreenshotFolderUnavailable)) throw e
                    if (!requestFolder()) { notify(R.string.screenshot_permission_failed, captureSession); return@launch }
                    if (!sameSession()) return@launch
                    save()
                }
                if (sameSession()) notify(if (destination == ScreenshotDestination.ALBUM) R.string.screenshot_saved_album else R.string.screenshot_saved_folder, captureSession)
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { notify(R.string.screenshot_permission_failed, captureSession) }
            catch (_: Exception) { notify(if (bitmap == null) R.string.screenshot_capture_failed else R.string.screenshot_save_failed, captureSession) }
            finally { bitmap?.recycle(); busy = false; captureSession = null }
        }
    }
    private fun sameSession() = vm.snapshot.value.sessionId == captureSession && vm.navigation.current == AppRoute.PLAYER

    private suspend fun copySurface(view: SurfaceView): Bitmap = suspendCancellableCoroutine { continuation ->
        // Display-sized SDR PNG export, including the current fit/flip and native subtitles.
        val bitmap = try { createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }
            catch (e: OutOfMemoryError) { continuation.resumeWithException(IllegalStateException("Capture allocation failed", e)); return@suspendCancellableCoroutine }
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive) {
                    continuation.resume(bitmap) { _, value, _ -> value.recycle() }
                } else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PixelCopy $result"))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { bitmap.recycle(); continuation.resumeWithException(e) }
    }
    fun close() { surface = null }
}
