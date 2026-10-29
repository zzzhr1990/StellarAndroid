package cn.coldlake.stellar.opensource.presentation

import android.os.Process
import android.os.SystemClock
import com.stellar.player.contract.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Lifetime is the visible overlay's RESUMED lifecycle. No timer exists when hidden. */
class PlayerDiagnostics(private val port: PlaybackRouter) {
    private val mutable = MutableStateFlow(PlaybackDiagnostics(0))
    val values = mutable.asStateFlow()
    @Volatile var sampling = false; private set
    @Volatile var samples = 0L; private set

    // Sampling uses a real-time background dispatcher, independent of Compose's frame clock.
    suspend fun collect(session: Long) = withContext(Dispatchers.Default) {
        mutable.value = PlaybackDiagnostics(session)
        var lastBytes: Long? = null
        var lastAt = SystemClock.elapsedRealtime()
        var lastCpu = Process.getElapsedCpuTime()
        // Only stat an explicitly local filesystem path; never issue an extra remote request.
        val localSize = withContext(Dispatchers.IO) {
            port.snapshots.value.media?.id?.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.let { path ->
                val local = when { path.startsWith("/") -> path; path.startsWith("file://") -> android.net.Uri.parse(path).path; else -> null }
                local?.let(::File)?.takeIf { it.isFile }?.length()
            }
        }
        sampling = true
        try {
            while (currentCoroutineContext().isActive && port.snapshots.value.sessionId == session) {
                val start = SystemClock.elapsedRealtime()
                val value = port.sampleDiagnostics(session) ?: PlaybackDiagnostics(session)
                currentCoroutineContext().ensureActive()
                if (port.snapshots.value.sessionId != session) break
                val now = SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                val elapsed = now - lastAt
                val networkBytes = value.networkBytes
                val network = if (elapsed > 0 && lastBytes != null && networkBytes != null && networkBytes >= lastBytes)
                    (networkBytes - lastBytes) * 1000.0 / elapsed else value.networkBytesPerSecond
                mutable.value = value.copy(fileBytes = value.fileBytes ?: localSize,
                    networkBytesPerSecond = network,
                    cpuPercent = if (elapsed >= 500) (cpu - lastCpu).coerceAtLeast(0) * 100.0 / elapsed else null)
                samples++
                lastBytes = value.networkBytes; lastAt = now; lastCpu = cpu
                // No catch-up burst after a slow/blocked native query.
                val spent = SystemClock.elapsedRealtime() - start
                delay(if (spent < 1000) 1000 - spent else 1000)
            }
        } finally { sampling = false }
    }
}
