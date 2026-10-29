package com.stellar.player.source

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/** Session-only credentials. Never parcel, persist, log or put them in MediaDescriptor. */
class SmbSource private constructor(
    val server: String,
    val share: String,
    val path: String,
    val user: String,
    val password: String,
    val domain: String,
    val requireEncryption: Boolean,
) {
    val id: String = "smb-session:${UUID.randomUUID()}"
    val title: String get() = path.substringAfterLast('/')
    override fun toString() = "SmbSource(session only)"

    companion object {
        /** Accept literal Unicode/spaces or percent-encoded UTF-8, decode once per segment.
         * Userinfo and URL options are intentionally rejected; credentials use separate fields. */
        fun parse(address: String, user: String = "", password: String = "", domain: String = "",
                  requireEncryption: Boolean = false): SmbSource? {
            val raw = address.trim()
            if (raw.length !in 1..8192 || raw.any { it.isISOControl() } || '\\' in raw) return null
            val uri = runCatching { URI(raw.replace(" ", "%20")) }.getOrNull() ?: return null
            if (!uri.scheme.equals("smb", ignoreCase = true) || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65535 || uri.port == 0) return null
            val parts = uri.rawPath.orEmpty().removePrefix("/").split('/').map { part ->
                runCatching { URLDecoder.decode(part.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            }
            if (parts.size < 2 || parts.any { it.isBlank() || it == "." || it == ".." ||
                    '/' in it || '\\' in it || '\uFFFD' in it || it.any(Char::isISOControl) }) return null
            if (listOf(user, password, domain).any { it.length > 1024 || it.any(Char::isISOControl) } ||
                (user.isBlank() && (password.isNotEmpty() || domain.isNotEmpty() || requireEncryption))) return null
            val server = uri.host + if (uri.port != -1) ":${uri.port}" else ""
            return SmbSource(server, parts.first(), parts.drop(1).joinToString("/"), user, password, domain, requireEncryption)
        }
    }
}
