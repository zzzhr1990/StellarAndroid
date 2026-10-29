package cn.coldlake.stellar.opensource.medialibrary.servers

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavError
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavFailure
import com.stellar.player.nativeio.NativeDirectoryProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal enum class ServerError { INPUT, AUTHENTICATION, PATH, PERMISSION, TIMEOUT, NETWORK, TLS, PROTOCOL, STORAGE, LIMIT, DRIVE_AUTH, DRIVE_CONFIG, ONEDRIVE_AUTH, ONEDRIVE_CONFIG, ONEDRIVE_ACCOUNT, ONEDRIVE_SERVICE, ONEDRIVE_SIGNIN }
internal fun interface ServerProbe { suspend fun check(profile: ServerProfile): ServerError? }

internal class DirectoryServerProbe(private val context: Context) : ServerProbe {
    override suspend fun check(profile: ServerProfile): ServerError? = try {
        withTimeout(20_000) {
            when (profile.kind) {
                ServerKind.ONEDRIVE -> {
                    cn.coldlake.stellar.opensource.medialibrary.onedrive.OneDriveClient(context, profile.cloudAccountId).use { it.list("root") }
                    null
                }
                ServerKind.GOOGLE_DRIVE -> {
                    cn.coldlake.stellar.opensource.medialibrary.drive.DriveClient(context, profile.username).use { it.list("root") }
                    null
                }
                ServerKind.WEBDAV -> {
                    WebDavClient.fromSettings(context, profile.address, profile.username.ifEmpty { null },
                        if (profile.username.isEmpty()) null else profile.password).use { it.listDirectory() }
                    null
                }
                ServerKind.SMB -> {
                    val host = if (':' in profile.address) "[${profile.address}]" else profile.address
                    nativeError(NativeDirectoryProbe.checkSmb("$host:${profile.port}", profile.share, profile.path,
                        profile.username, profile.password, profile.domain))
                }
                ServerKind.NFS -> nativeError(NativeDirectoryProbe.checkNfs(profile.address, profile.path,
                    profile.nfsVersion, profile.port.toInt(), profile.uid.toIntOrNull(), profile.gid.toIntOrNull()))
                else -> ServerError.PROTOCOL
            }
        }
    } catch (_: TimeoutCancellationException) { ServerError.TIMEOUT
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: WebDavFailure) {
        when (failure.reason) {
            WebDavError.AUTHENTICATION -> ServerError.AUTHENTICATION
            WebDavError.FORBIDDEN -> ServerError.PERMISSION
            WebDavError.NOT_FOUND, WebDavError.NOT_COLLECTION -> ServerError.PATH
            WebDavError.TLS -> ServerError.TLS
            WebDavError.TIMEOUT -> ServerError.TIMEOUT
            WebDavError.NETWORK -> ServerError.NETWORK
            else -> ServerError.PROTOCOL
        }
    } catch (failure: DirectoryFailure) { failure.reason
    } catch (_: Exception) { ServerError.NETWORK }

    private fun nativeError(result: NativeDirectoryProbe.Result): ServerError? = when (result) {
        NativeDirectoryProbe.Result.OK -> null
        NativeDirectoryProbe.Result.AUTHENTICATION -> ServerError.AUTHENTICATION
        NativeDirectoryProbe.Result.PATH -> ServerError.PATH
        NativeDirectoryProbe.Result.PERMISSION -> ServerError.PERMISSION
        NativeDirectoryProbe.Result.TIMEOUT -> ServerError.TIMEOUT
        NativeDirectoryProbe.Result.CANCELLED -> throw CancellationException("Directory probe cancelled")
        NativeDirectoryProbe.Result.NETWORK -> ServerError.NETWORK
        NativeDirectoryProbe.Result.LIMIT -> ServerError.LIMIT
    }
}
