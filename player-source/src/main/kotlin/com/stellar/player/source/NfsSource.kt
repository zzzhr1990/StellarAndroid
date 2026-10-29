package com.stellar.player.source

import java.net.URI
import java.util.UUID

enum class NfsVersion(val number: Int) { V3(3), V4(4) }

/** AUTH_SYS source, held only for the current session. Paths are literal UTF-8, not URLs. */
class NfsSource private constructor(
    val server: String,
    val export: String,
    val path: String,
    val version: NfsVersion,
    val uid: Int?,
    val gid: Int?,
    val port: Int,
) {
    val id = "nfs-session:${UUID.randomUUID()}"
    val title get() = path.substringAfterLast('/')
    override fun toString() = "NfsSource(session only)"

    companion object {
        /** Blank UID/GID uses the Android process identity; no implicit root or fallback. */
        fun parse(server: String, export: String, path: String, version: NfsVersion = NfsVersion.V3,
                  uid: String = "", gid: String = ""): NfsSource? {
            val host = server.trim()
            if (export.isBlank()) return null
            val root = export.trim().removeSuffix("/").ifEmpty { "/" }
            val file = path // Preserve significant whitespace, '+' and '%' in filenames.
            val uri = runCatching { URI("nfs://$host") }.getOrNull() ?: return null
            if (host.isBlank() || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65535 || uri.port == 0) return null
            fun valid(value: String, absolute: Boolean): Boolean {
                if (value.length !in 1..4096 || value.any(Char::isISOControl) || '\\' in value || '\uFFFD' in value) return false
                if (value.startsWith('/') != absolute) return false
                if (absolute && value == "/") return true
                return value.removePrefix("/").split('/').none { it.isEmpty() || it == "." || it == ".." }
            }
            if (!valid(root, true) || !valid(file, false)) return null
            fun identity(value: String): Int? = value.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            val u = identity(uid); val g = identity(gid)
            if ((uid.isNotBlank() && u == null) || (gid.isNotBlank() && g == null)) return null
            return NfsSource(uri.host.removePrefix("[").removeSuffix("]"), root, "/$file", version, u, g,
                if (uri.port == -1) 2049 else uri.port)
        }
    }
}
