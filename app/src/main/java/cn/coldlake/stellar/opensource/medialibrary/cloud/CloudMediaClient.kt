package cn.coldlake.stellar.opensource.medialibrary.cloud

import okhttp3.Call
import okhttp3.Response
import java.io.Closeable

/** Provider owns credentials/expiring URLs; the reader can cancel an active response body. */
internal interface CloudMediaClient : Closeable {
    suspend fun media(id: String, range: String?, head: Boolean, onCall: (Call) -> Unit = {}): Response
}
