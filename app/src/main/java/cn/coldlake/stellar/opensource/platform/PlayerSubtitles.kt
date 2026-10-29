package cn.coldlake.stellar.opensource.platform

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.stellar.player.contract.ExternalSubtitle
import cn.coldlake.stellar.opensource.MainActivity
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import cn.coldlake.stellar.opensource.network.ignoreMediaCertificateErrors
import cn.coldlake.stellar.opensource.presentation.AppRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Activity-result ownership and bounded IO stay in the host; the backend receives a private local path. */
internal class PlayerSubtitles(private val activity: MainActivity, saved: Bundle?) {
    private val vm get() = activity.playerViewModel
    private val folder = File(activity.filesDir, "external-subtitles")
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private var httpCall: okhttp3.Call? = null
    private var loadingSession: Long? = null
    private var pickerSession: Long? = saved?.getLong("subtitlePickerSession", 0)?.takeIf { it != 0L }
    private var pickerCastToken: Long? = saved?.getLong("subtitlePickerCastToken", 0)?.takeIf { it != 0L }
    val pickerActive get() = pickerSession != null
    var busy by mutableStateOf(false); private set
    private var urlSession by mutableStateOf<Long?>(null)
    private var urlCastToken: Long? = null
    private var url by mutableStateOf("")
    private var invalidUrl by mutableStateOf(false)
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val original = pickerSession ?: return@registerForActivityResult
        val castToken = pickerCastToken
        job = activity.lifecycleScope.launch {
            try {
                activity.lifecycle.withResumed { }
                // Match the existing picker recovery: the system document activity can replace the Surface.
                if (castToken == null) withTimeoutOrNull(3000) {
                    while (sameSession(original) && (activity.screenshots.surface?.holder?.surface?.isValid != true || !activity.hasWindowFocus())) delay(16)
                }
                val session = if (!sameTarget(original, castToken)) null
                    else if (castToken != null) original else vm.reopenAfterScreenshotFolder(original)
                if (uri != null && session != null) {
                    withTimeout(15_000) { vm.snapshot.firstReady(session) }
                    load(session, castToken) { copyLocal(uri) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure(vm.snapshot.value.sessionId) }
            finally { pickerSession = null; pickerCastToken = null; activity.pictureInPicture.suspendAutoEnter(false) }
        }
    }
    init {
        if (pickerActive) activity.pictureInPicture.suspendAutoEnter(true)
        activity.lifecycleScope.launch {
            vm.snapshot.map { it.sessionId }.distinctUntilChanged().collect { current ->
                if (loadingSession?.let { it != current } == true) { httpCall?.cancel(); job?.cancel() }
            }
        }
    }
    fun saveState(out: Bundle) {
        pickerSession?.let { out.putLong("subtitlePickerSession", it) }
        pickerCastToken?.let { out.putLong("subtitlePickerCastToken", it) }
    }
    private fun sameSession(session: Long) = vm.snapshot.value.sessionId == session && vm.navigation.current == AppRoute.PLAYER
    private fun sameTarget(session: Long, castToken: Long?) = sameSession(session) && vm.casting?.activeToken == castToken
    fun local() {
        if (busy || pickerActive || !vm.snapshot.value.capabilities.canImportSubtitle || vm.snapshot.value.simulated) return
        vm.dismissPanel()
        pickerSession = vm.snapshot.value.sessionId
        pickerCastToken = vm.casting?.activeToken
        activity.pictureInPicture.suspendAutoEnter(true)
        try { launcher.launch(arrayOf("*/*")) }
        catch (_: Exception) { pickerSession = null; pickerCastToken = null; activity.pictureInPicture.suspendAutoEnter(false); failure(vm.snapshot.value.sessionId) }
    }
    fun remote() {
        if (busy || pickerActive || !vm.snapshot.value.capabilities.canImportSubtitle || vm.snapshot.value.simulated) return
        vm.dismissPanel(); url = ""; invalidUrl = false; urlSession = vm.snapshot.value.sessionId
        urlCastToken = vm.casting?.activeToken
        vm.interactions.panel(true)
    }
    private fun dismissUrl() { urlSession = null; urlCastToken = null; url = ""; vm.interactions.panel(false) }
    internal fun loadUrl(value: String) {
        if (busy || pickerActive) return
        val address = value.trim().toHttpUrlOrNull()
        if (address == null || address.username.isNotEmpty() || address.password.isNotEmpty()) { invalidUrl = true; return }
        val session = urlSession ?: vm.snapshot.value.sessionId
        val castToken = if (urlSession != null) urlCastToken else vm.casting?.activeToken
        dismissUrl()
        val ignoreTls = MediaSecuritySettings(activity).ignoreCertificateErrors
        job = activity.lifecycleScope.launch {
            load(session, castToken) {
                val selectedClient = if (ignoreTls) client.newBuilder().apply { ignoreMediaCertificateErrors() }.build() else client
                val call = selectedClient.newCall(Request.Builder().url(address).build())
                httpCall = call
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful)
                        check(response.body.contentLength() <= MAX_BYTES)
                        copy(response.body.byteStream(), address.pathSegments.lastOrNull().orEmpty())
                    }
                } finally { httpCall = null }
            }
        }
    }
    private suspend fun load(session: Long, castToken: Long?, source: suspend () -> ExternalSubtitle) {
        if (!sameTarget(session, castToken)) return
        loadingSession = session
        busy = true
        try {
            val result = withContext(Dispatchers.IO) { source() }
            if (sameTarget(session, castToken)) {
                if (!vm.loadExternalSubtitle(session, result) && sameTarget(session, castToken)) failure(session)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (sameTarget(session, castToken)) failure(session) }
        finally { busy = false; loadingSession = null }
    }
    private suspend fun copyLocal(uri: Uri): ExternalSubtitle {
        val label = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        return checkNotNull(activity.contentResolver.openInputStream(uri)).use { copy(it, label) }
    }
    private suspend fun copy(input: InputStream, name: String): ExternalSubtitle {
        folder.mkdirs()
        val temporary = File.createTempFile("import-", ".tmp", folder)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024); var size = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    size += count; check(size <= MAX_BYTES)
                    output.write(buffer, 0, count); hash.update(buffer, 0, count)
                }
                check(size > 0)
            }
            val id = hash.digest().joinToString("") { "%02x".format(it) }
            val extension = name.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "ass", "ssa", "vtt", "sub", "txt") } ?: "srt"
            val destination = File(folder, "$id.$extension")
            val existing = destination.isFile
            if (!existing) check(temporary.renameTo(destination))
            currentCoroutineContext().ensureActive()
            val protected = vm.snapshot.value.externalSubtitle?.path
            val localProtected = vm.casting?.localSubtitlePath
            folder.listFiles().orEmpty().filter { it != destination && it.absolutePath != protected && it.absolutePath != localProtected }
                .sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
            return ExternalSubtitle("external:$id", name.take(200).ifBlank { activity.getString(R.string.subtitle_file) }, destination.absolutePath)
        } finally { temporary.delete() }
    }
    private fun failure(session: Long) { activity.screenshots.exportMessage(R.string.subtitle_load_failed, session) }
    fun close() {
        if (urlSession != null) vm.interactions.panel(false)
        httpCall?.cancel(); job?.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
    }
    @Composable fun Dialogs() {
        val session = urlSession
        val sessionFlow = remember(vm) { vm.snapshot.map { it.sessionId }.distinctUntilChanged() }
        val initialSession = remember(vm) { vm.snapshot.value.sessionId }
        val currentSession by sessionFlow.collectAsState(initialSession)
        LaunchedEffect(currentSession) {
            if (session != null && !sameSession(session)) dismissUrl()
        }
        if (session != null) AlertDialog(onDismissRequest = ::dismissUrl,
            title = { Text(stringResource(R.string.subtitle_url_title)) },
            text = { OutlinedTextField(value = url, onValueChange = { url = it; invalidUrl = false },
                label = { Text(stringResource(R.string.subtitle_url_label)) }, singleLine = true,
                isError = invalidUrl, supportingText = if (invalidUrl) ({ Text(stringResource(R.string.subtitle_url_invalid)) }) else null,
                modifier = Modifier.fillMaxWidth().testTag("subtitle-url-input")) },
            confirmButton = { TextButton(onClick = { loadUrl(url) }, modifier = Modifier.testTag("subtitle-url-load")) { Text(stringResource(R.string.subtitle_load)) } },
            dismissButton = { TextButton(onClick = ::dismissUrl) { Text(stringResource(R.string.subtitle_cancel)) } })
        if (busy) AlertDialog(onDismissRequest = {}, text = { Column { Text(stringResource(R.string.subtitle_loading)); LinearProgressIndicator() } },
            confirmButton = { TextButton(onClick = { httpCall?.cancel(); job?.cancel() }) { Text(stringResource(R.string.subtitle_cancel)) } })
    }
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<com.stellar.player.contract.PlaybackSnapshot>.firstReady(session: Long) {
    first { it.sessionId != session || it.status != com.stellar.player.contract.PlaybackStatus.BUFFERING }
}
