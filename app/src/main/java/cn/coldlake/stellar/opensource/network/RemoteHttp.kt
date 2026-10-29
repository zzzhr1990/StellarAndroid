package cn.coldlake.stellar.opensource.network

import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Process-owned transports. Authentication and request cancellation remain session-owned. */
internal object RemoteHttp {
    val cloud: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    // Ktor closes a managed engine's pool/executor. Pass these external engines to each
    // HttpClient so closing a DAV session cannot shut down another session's transport.
    private val davStrict by lazy { davEngine(cloud) }
    private val davInsecure by lazy {
        davEngine(cloud.newBuilder().apply { ignoreMediaCertificateErrors() }.build())
    }
    fun webDavEngine(verifyTls: Boolean) = if (verifyTls) davStrict else davInsecure
    private fun davEngine(base: OkHttpClient) = OkHttp.create {
        preconfigured = base.newBuilder().retryOnConnectionFailure(false).build()
    }
}

/** Tracks calls until their response body is closed, including calls racing with close(). */
internal class HttpCallScope(base: OkHttpClient) : Closeable {
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private val client = base.newBuilder().eventListener(object : EventListener() {
        override fun callStart(call: Call) {
            val cancel = synchronized(calls) { if (closed) true else { calls.add(call); false } }
            if (cancel) call.cancel()
        }
        override fun callEnd(call: Call) { synchronized(calls) { calls.remove(call) } }
        override fun callFailed(call: Call, ioe: IOException) { synchronized(calls) { calls.remove(call) } }
    }).build()

    fun newCall(request: Request): Call = client.newCall(request)
    override fun close() {
        val active = synchronized(calls) { closed = true; calls.toList().also { calls.clear() } }
        active.forEach { it.cancel() }
    }
}
