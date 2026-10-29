package com.stellar.player.source

import java.net.URI

/** Session-only HTTP input. Never put this object in UI state, saved state or diagnostics. */
class HttpSource(
    val address: String,
    headers: Map<String, String>,
    val userAgent: String = "StellarPlayer/Android",
    val verifyTlsCertificates: Boolean = true,
) {
    val headers: Map<String, String> = headers.toMap()
    init {
        val uri = URI(address)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null)
        require(userAgent.isNotBlank() && userAgent.all { it.code in 32..126 })
        require(this.headers.all { (name, value) ->
            name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.all { it.code in 32..126 }
        })
    }
    fun headerBlock(): String = headers.entries.joinToString("") { (key, value) -> "$key: $value\r\n" }
    override fun toString(): String = "HttpSource(redacted)"
}
