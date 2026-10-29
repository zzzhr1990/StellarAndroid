package cn.coldlake.stellar.opensource.medialibrary.webdav

import io.ktor.client.plugins.auth.AuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicReference

/** Reuses only a successfully authenticated challenge; Ktor still generates every proof. */
internal class RememberedDavAuth(private val delegate: AuthProvider, private val sameOrigin: (Url) -> Boolean) : AuthProvider by delegate {
    private val accepted = AtomicReference<HttpAuthHeader?>()
    private val used = AttributeKey<HttpAuthHeader>("DavAuthChallenge.${delegate.javaClass.name}")

    override fun sendWithoutRequest(request: HttpRequestBuilder) = sameOrigin(request.url.build()) && accepted.get() != null

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        val challenge = authHeader ?: accepted.get() ?: return
        if (!sameOrigin(request.url.build())) throw WebDavFailure(WebDavError.CROSS_ORIGIN)
        delegate.addRequestHeaders(request, challenge)
        request.attributes.put(used, challenge)
        // A preemptive Digest proof may need one new nonce. A challenged retry is final.
        request.attributes.put(PREEMPTIVE_DIGEST, authHeader == null && challenge.authScheme.equals("Digest", true))
    }

    fun observe(response: HttpResponse) {
        val challenge = response.call.request.attributes.getOrNull(used) ?: return
        when (response.status.value) {
            in 200..299 -> accepted.set(challenge)
            401 -> accepted.compareAndSet(challenge, null)
        }
    }

    override fun clearToken() { accepted.set(null); delegate.clearToken() }

    companion object { val PREEMPTIVE_DIGEST = AttributeKey<Boolean>("DavPreemptiveDigest") }
}
