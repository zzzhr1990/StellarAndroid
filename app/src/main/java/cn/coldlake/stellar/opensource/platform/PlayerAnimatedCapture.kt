package cn.coldlake.stellar.opensource.platform

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.createBitmap
import com.stellar.player.contract.MediaKind
import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus
import com.stellar.player.contract.VideoDynamicRange
import cn.coldlake.stellar.opensource.MainActivity
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.presentation.AppRoute
import com.stellar.player.nativeio.GifCaptureEncoder
import android.view.SurfaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

internal enum class CaptureFormat { GIF, MP4 }
internal enum class CapturePhase { IDLE, PREPARING, RECORDING, SAVING }

/** Activity-owned, one reusable bitmap and no queued frames. Playback owns its own Surface. */
internal class PlayerAnimatedCapture(private val activity: MainActivity) {
    private val vm get() = activity.playerViewModel
    private val screenshots get() = activity.screenshots
    private val storage = ScreenshotStorage(activity.applicationContext)
    var phase by mutableStateOf(CapturePhase.IDLE)
        private set
    var elapsedSeconds by mutableIntStateOf(0)
        private set
    var durationSeconds by mutableIntStateOf(5)
        private set
    var dialogSession by mutableStateOf<Long?>(null)
        private set
    val busy get() = phase != CapturePhase.IDLE
    private var job: Job? = null
    private var interrupted = false
    internal var lastFrames = 0
        private set
    internal var lastBitmapBytes = 0
        private set

    fun unavailable(snapshot: PlaybackSnapshot): Int? = when {
        snapshot.remotePlayback -> R.string.casting_local_only
        snapshot.simulated || snapshot.media?.kind != MediaKind.VIDEO -> R.string.capture_real_video
        snapshot.details?.videoDynamicRange in setOf(VideoDynamicRange.HDR, VideoDynamicRange.DOLBY_VISION) -> R.string.capture_sdr_only
        snapshot.status != PlaybackStatus.PLAYING -> R.string.capture_play_first
        else -> null
    }
    fun open() {
        if (busy || screenshots.busy) return
        dialogSession = vm.snapshot.value.sessionId
    }
    fun dismiss() { dialogSession = null }
    fun cancel(automatic: Boolean = false) {
        if (automatic) dialogSession = null
        if (!busy) return
        interrupted = automatic
        job?.cancel()
    }
    fun background() {
        // Permission UI runs before any frame is acquired and uses the screenshot recovery path.
        if (!screenshots.awaitingCapturePermission) cancel(automatic = true)
    }
    fun start(format: CaptureFormat, seconds: Int) {
        if (busy || screenshots.busy || seconds !in listOf(3,5,10)) return
        val requested = vm.snapshot.value
        if (dialogSession != requested.sessionId) { dismiss(); return }
        unavailable(requested)?.let { screenshots.exportMessage(it, requested.sessionId); return }
        dismiss()
        phase = CapturePhase.PREPARING; elapsedSeconds = 0; durationSeconds = seconds
        interrupted = false; lastFrames = 0; lastBitmapBytes = 0
        job = activity.lifecycleScope.launch {
            var session = requested.sessionId
            var saved: Uri? = null
            var committed = false
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "player-capture") }.asCoroutineDispatcher()
            var temporary: File? = null
            try {
                screenshots.export(session, { session = it }) { readySession, folder ->
                    session = readySession
                    // The folder picker can reopen playback. Wait for that asynchronous prepare,
                    // rather than capturing an old/black buffer or treating BUFFERING as a failure.
                    checkNotNull(withTimeoutOrNull(10_000) {
                        vm.snapshot.first { it.sessionId != session || it.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.ERROR, PlaybackStatus.ENDED) }
                    }) { "Playback did not resume after capture permission" }
                    if (vm.snapshot.value.sessionId != session || vm.navigation.current != AppRoute.PLAYER) throw CancellationException()
                    check(unavailable(vm.snapshot.value) == null)
                    val surface = checkNotNull(screenshots.surface)
                    val sourceWidth = surface.width; val sourceHeight = surface.height
                    check(surface.holder.surface.isValid && sourceWidth > 0 && sourceHeight > 0)
                    val scale = minOf(1.0, 640.0 / maxOf(sourceWidth, sourceHeight))
                    val width = ((sourceWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                    val height = ((sourceHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                    val videoId = vm.snapshot.value.selectedVideoId
                    val owner = currentCoroutineContext().job
                    val guard = launch {
                        combine(vm.snapshot, vm.navigation.routeChanges) { snapshot, route ->
                            snapshot.sessionId == session && snapshot.selectedVideoId == videoId &&
                                route == AppRoute.PLAYER && unavailable(snapshot) == null
                        }.collect { valid -> if (!valid) { interrupted = true; owner.cancel() } }
                    }
                    try {
                        phase = CapturePhase.RECORDING
                        withContext(worker) {
                            // Private bounded temporary file; only a complete export is published.
                            val file = File.createTempFile("capture-", if (format == CaptureFormat.GIF) ".gif" else ".mp4", activity.cacheDir)
                            temporary = file
                            val bitmap = createBitmap(width,height,Bitmap.Config.ARGB_8888)
                            lastBitmapBytes = bitmap.allocationByteCount
                            var gif: GifCaptureEncoder? = null
                            var avc: AvcCaptureEncoder? = null
                            try {
                                if (format == CaptureFormat.GIF) gif = GifCaptureEncoder(file.path,width,height)
                                else avc = AvcCaptureEncoder(file.path,width,height)
                                val interval = if (format == CaptureFormat.GIF) 100L else 67L
                                var start = 0L
                                var next = 0L
                                val duration = seconds * 1000L
                                do {
                                    currentCoroutineContext().ensureActive()
                                    val now = SystemClock.elapsedRealtime()
                                    if (start != 0L && now >= start + duration) break
                                    if (next > now) delay(next - now)
                                    withContext(Dispatchers.Main.immediate) {
                                        if (screenshots.surface !== surface || surface.width != sourceWidth || surface.height != sourceHeight ||
                                            activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
                                            interrupted = true; throw CancellationException("Capture surface changed")
                                        }
                                        copyInto(surface,bitmap)
                                    }
                                    currentCoroutineContext().ensureActive()
                                    val sampled = SystemClock.elapsedRealtime()
                                    if (start == 0L) start = sampled
                                    val time = sampled - start
                                    if (time >= duration) break
                                    gif?.add(bitmap,time); avc?.add(bitmap,time)
                                    lastFrames++
                                    withContext(Dispatchers.Main.immediate) { elapsedSeconds = (time / 1000).toInt() }
                                    // Skip missed sample slots; never accumulate a frame backlog.
                                    next = start + ((SystemClock.elapsedRealtime() - start) / interval + 1) * interval
                                } while (true)
                                check(lastFrames >= 2)
                                currentCoroutineContext().ensureActive()
                                gif?.finish(duration); avc?.finish(duration)
                            } finally { gif?.close(); avc?.close(); bitmap.recycle() }
                            check(file.length() in 1..64L * 1024 * 1024)
                        }
                        phase = CapturePhase.SAVING
                        withContext(Dispatchers.IO) {
                            saved = storage.saveExport(checkNotNull(temporary), format == CaptureFormat.MP4, folder) { owner.ensureActive() }
                        }
                        currentCoroutineContext().ensureActive()
                        if (vm.snapshot.value.sessionId != session || vm.navigation.current != AppRoute.PLAYER) throw CancellationException()
                        committed = true
                        screenshots.exportMessage(if (folder == null) R.string.capture_saved_album else R.string.capture_saved_folder, session)
                    } finally { guard.cancel() }
                }
            } catch (e: CancellationException) {
                if (vm.snapshot.value.sessionId == session && vm.navigation.current == AppRoute.PLAYER)
                    screenshots.exportMessage(if (interrupted) R.string.capture_interrupted else R.string.capture_cancelled, session)
                throw e
            } catch (_: SecurityException) { screenshots.exportMessage(R.string.screenshot_permission_failed,session) }
            catch (e: Exception) {
                android.util.Log.w("PlayerCapture", "Capture failed during $phase", e)
                screenshots.exportMessage(R.string.capture_failed,session)
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        if (!committed) saved?.let { runCatching { storage.deleteExport(it) } }
                        temporary?.delete()
                    }
                    worker.close()
                    phase = CapturePhase.IDLE
                }
            }
        }
    }
    private suspend fun copyInto(view: SurfaceView, bitmap: Bitmap) {
        // Wait for the actual callback even when cancelled: PixelCopy still owns this bitmap.
        withContext(NonCancellable) {
            suspendCoroutine<Unit> { continuation ->
                try {
                    PixelCopy.request(view,bitmap,{ result ->
                        if (result == PixelCopy.SUCCESS) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("PixelCopy $result"))
                    },Handler(Looper.getMainLooper()))
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        }
    }
    fun close() { cancel(automatic = true) }
}
