package cn.coldlake.stellar.opensource.network

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Applies only to this session's client, after an explicit opt-in. System TLS defaults stay intact. */
@SuppressLint("BadHostnameVerifier")
internal fun OkHttpClient.Builder.ignoreMediaCertificateErrors() {
    val trustManager = OptInMediaTrustManager()
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    sslSocketFactory(context.socketFactory, trustManager)
    hostnameVerifier { _, _ -> true }
}

@SuppressLint("TrustAllX509TrustManager")
internal class OptInMediaTrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    // Android's X509TrustManagerExtensions invokes this overload when cleaning the peer chain.
    @Suppress("unused", "UNUSED_PARAMETER")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> = chain.toList()
}
