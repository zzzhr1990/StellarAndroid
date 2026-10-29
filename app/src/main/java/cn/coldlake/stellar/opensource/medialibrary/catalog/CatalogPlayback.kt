package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.Context
import android.net.Uri
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import kotlinx.coroutines.*

/** A durable reference, never a token, session URL or guessed server filesystem path. */
internal object CatalogServerLocator {
    fun create(p: ServerProfile, item: String): String = Uri.Builder().scheme("stellar-server").authority(p.id)
        .appendPath(item).appendQueryParameter("server", p.embyServerId).appendQueryParameter("user", p.embyUserId)
        .appendQueryParameter("revision", p.updatedAt.toString()).build().toString()
    fun isServer(uri: String) = uri.startsWith("stellar-server:")
    fun fromImport(scope:String, identity:String):String? {
        val p=scope.split('|')
        if(p.size!=5 || p[3].toLongOrNull()==null || p.take(3).any { it.isBlank() }) return null
        val prefix="server:${p[0]}:${p[1]}:${p[2]}:"
        if(!identity.startsWith(prefix) || identity.length==prefix.length) return null
        return Uri.Builder().scheme("stellar-server").authority(p[0]).appendPath(identity.removePrefix(prefix))
            .appendQueryParameter("server",p[1]).appendQueryParameter("user",p[2]).appendQueryParameter("revision",p[3]).build().toString()
    }
    fun resolved(uri: String) = !isServer(uri) || Uri.parse(uri).getQueryParameter("mediaSource") != null
    private fun profile(context:Context, uri:Uri):ServerProfile {
        val p=ServerDatabase(context).use { it.list().firstOrNull { p -> p.id==uri.authority } } ?: throw java.io.IOException("Source removed")
        require(p.embyServerId==uri.getQueryParameter("server") && p.embyUserId==uri.getQueryParameter("user"))
        return p
    }
    suspend fun versions(context:Context, locator:String):List<EmbyVersion> = withContext(Dispatchers.IO) {
        val uri=Uri.parse(locator); val p=profile(context,uri)
        EmbyClient(p,!MediaSecuritySettings(context).ignoreCertificateErrors).use { api -> api.playbackInfo(api.detail(requireNotNull(uri.lastPathSegment))).versions }
    }
    suspend fun prepare(context: Context, locator: String, position: Long): EmbyPlayback = withContext(Dispatchers.IO) {
        val uri = Uri.parse(locator)
        val profile = ServerDatabase(context).use { it.list().firstOrNull { p -> p.id == uri.authority } }
            ?: throw java.io.IOException("Source removed")
        require(profile.embyServerId == uri.getQueryParameter("server") && profile.embyUserId == uri.getQueryParameter("user"))
        val api = EmbyClient(profile, !MediaSecuritySettings(context).ignoreCertificateErrors)
        try {
            val item = api.detail(requireNotNull(uri.lastPathSegment))
            val chosen = requireNotNull(uri.getQueryParameter("mediaSource"))
            val info = api.playbackInfo(item, chosen)
            val version = info.versions.firstOrNull { it.id == chosen && it.streamable } ?: throw java.io.IOException("Version unavailable")
            val resume = if (position > 0) cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.get(context)
                .chooseResume(profile, item, chosen) else position
            EmbyPlayback(profile, item, version, info.sessionId, resume.coerceAtLeast(0).coerceAtMost((version.durationMs ?: Long.MAX_VALUE) - 1), api.verifyTlsCertificates)
        } finally { api.close() }
    }
}
