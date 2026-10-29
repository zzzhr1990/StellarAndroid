@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package cn.coldlake.stellar.opensource.cache

import android.content.Context
import coil3.*
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.*
import coil3.network.okhttp.asNetworkClient
import coil3.request.*
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Scale
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import cn.coldlake.stellar.opensource.network.ignoreMediaCertificateErrors
import cn.coldlake.stellar.opensource.network.MAX_IMAGE_BYTES
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import cn.coldlake.stellar.opensource.medialibrary.cloud.smallFile
import cn.coldlake.stellar.opensource.medialibrary.catalog.NasArtwork
import cn.coldlake.stellar.opensource.medialibrary.catalog.NasLibrarySource
import cn.coldlake.stellar.opensource.medialibrary.catalog.NasLocator
import cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime
import cn.coldlake.stellar.opensource.medialibrary.catalog.PresentationArtwork

internal data class Artwork(val namespace: String, val key: String, val url: String,
    val headers: Map<String, String>, val tagged: Boolean, val verifyTls: Boolean, val software: Boolean,
    val publicRedirects: Boolean = false) {
    override fun toString() = "Artwork($key)"
}
internal val artworkExtra = Extras.Key<Artwork?>(null)
internal val libraryImageExtra = Extras.Key<String?>(null)

internal class AppImageLoader(private val context: Context, private val clock: CacheClock = CacheClock.SYSTEM,
    directory: File = File(context.cacheDir, "media-images-v1"),
    private val admit: suspend () -> Unit = {}, private val allowed: (String) -> Boolean = { true }) {
    val metrics = CacheMetrics()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domain = CacheDomain()
    private val keys = KeyLocks()
    private val index = CacheDatabase(context, directory, true)
    private val housekeeping=Mutex()
    private val mutableCleared=MutableStateFlow(CacheChange())
    val cleared=mutableCleared.asStateFlow()
    private val mutableRetries=MutableStateFlow(CacheChange())
    val retries=mutableRetries.asStateFlow()
    private val hot = object : LinkedHashMap<String, CacheTime>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheTime>?) = size > 20_000
    }
    private val missing = mutableMapOf<String, Long>()
    private val touches = mutableMapOf<String,Long>()
    private data class ArtworkColors(val edge: Int? = null, val seed: Int? = null)
    private val colors=object : LinkedHashMap<String,ArtworkColors>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,ArtworkColors>?)=size>256
    }
    private val downloads = Semaphore(4)
    private val active = AtomicInteger()
    private val dedupe = DeDupeConcurrentRequestStrategy()
    private val maxMemory = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
    private val disk by lazy { DiskCache.Builder().directory(File(directory, "coil").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
    private val initialized = CacheInitialization { withContext(Dispatchers.IO) {
        directory.mkdirs()
        // Android may evict only part of a cache directory. An absent ownership index
        // cannot support namespace deletion, so discard the orphaned image domain.
        if(!File(directory,"index.db").exists() && File(directory,"coil").exists()) disk.clear()
        try { index.writableDatabase }
        catch (_: android.database.sqlite.SQLiteException) {
            index.close(); SQLiteRecovery.rebuild(directory, index, disk)
        }
        for (ns in index.pending()) remove(if (ns == "*") null else ns)
    } }

    private fun client(verify: Boolean, publicRedirects: Boolean = false) = OkHttpClient.Builder().apply {
        followRedirects(publicRedirects); followSslRedirects(false); retryOnConnectionFailure(false)
        connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!verify) ignoreMediaCertificateErrors()
        addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val source = object : ForwardingSource(body.source()) {
                var bytes = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = super.read(sink, byteCount)
                    if (count > 0) { bytes += count; metrics.add("downloadBytes", count) }
                    if (bytes > MAX_IMAGE_BYTES) throw java.io.IOException("Image exceeds limit")
                    return count
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }
    }.build().asNetworkClient()
    private val strict by lazy { client(true) }
    private val relaxed by lazy { client(false) }
    // Anonymous presentation images use 307 to HTTPS OSS/CDN; authenticated server images keep their policy.
    private val publicImages by lazy { client(true, publicRedirects = true) }
    private val network = object : NetworkClient {
        override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T = downloads.withPermit {
            metrics.add("downloads"); metrics.peak("activeDownloads", active.incrementAndGet().toLong())
            try {
                val art = request.extras[artworkExtra]
                (if (art?.publicRedirects == true) publicImages else if (art?.verifyTls != false) strict else relaxed)
                    .executeRequest(request, block)
            }
            finally { active.decrementAndGet() }
        }
    }
    private val strategy = object : CacheStrategy {
        override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
            if (cacheResponse.code == 200) CacheStrategy.ReadResult(cacheResponse) else CacheStrategy.ReadResult(networkRequest)
        override suspend fun write(cacheResponse: NetworkResponse?, networkRequest: NetworkRequest,
            networkResponse: NetworkResponse, options: Options): CacheStrategy.WriteResult {
            if (networkResponse.code != 200) return CacheStrategy.WriteResult.DISABLED
            options.extras[artworkExtra]?.let { art ->
                indexed {
                    index.writableDatabase.execSQL("INSERT OR REPLACE INTO images VALUES(?,?,?,?,0)",
                        arrayOf<Any>(art.key, art.namespace, clock.wall(), clock.wall()))
                    metrics.add("indexWrites")
                }
            }
            return CacheStrategy.WriteResult(networkResponse)
        }
    }
    val loader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(maxMemory).weakReferencesEnabled(false).build() }
        .diskCache { disk }.bitmapFactoryMaxParallelism(2).memoryCacheMaxSizePercentWhileInBackground(.25)
        .interceptorCoroutineContext(Dispatchers.Default).fetcherCoroutineContext(Dispatchers.IO).decoderCoroutineContext(Dispatchers.Default)
        .components {
            add(Interceptor { chain -> intercept(chain) })
            add(object : coil3.fetch.Fetcher.Factory<NasArtwork> {
                override fun create(data: NasArtwork, options: Options, imageLoader: ImageLoader) = object : coil3.fetch.Fetcher {
                    override suspend fun fetch(): coil3.fetch.FetchResult {
                        val (bytes, cached) = nasImage(data)
                        return coil3.fetch.SourceFetchResult(coil3.decode.ImageSource(Buffer().write(bytes), options.fileSystem),
                            null, if (cached) coil3.decode.DataSource.DISK else coil3.decode.DataSource.NETWORK)
                    }
                }
            })
            add(NetworkFetcher.Factory(networkClient = { network }, cacheStrategy = { strategy }, concurrentRequestStrategy = { dedupe }))
        }
        .eventListener(object : EventListener() {
            override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
                metrics.add("decodes")
                result?.image?.let { metrics.add("decodedPixels", it.width.toLong() * it.height) }
            }
        }).build()

    init { scope.launch { while (isActive) {
        delay(5_000)
        try { flushTouches() } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { metrics.add("touchFailures") }
    } } }

    fun request(profile: ServerProfile, verifyTls: Boolean, item: EmbyItem, width: Int, height: Int,
        type: String = "Primary", index: Int = 0, explicitTag: String? = null, fit: Boolean = false,
        software: Boolean = false, quality: Int = 90): ImageRequest? {
        if (width <= 0 || height <= 0 || profile.embyUserId.isBlank()) return null
        val tag = explicitTag ?: when (type) { "Backdrop" -> item.backdropTags.getOrNull(index).orEmpty(); "Logo" -> item.logoTag; else -> item.imageTag }
        val owner = when (type) { "Backdrop" -> item.backdropId; "Logo" -> item.logoId; "Chapter" -> item.id; else -> item.imageId }
        val ratio = if (type == "Primary") item.imageAspectRatio else if (type in setOf("Backdrop", "Chapter")) 16.0 / 9 else null
        val sourceWidth = if (ratio == null) width.toDouble() else if (fit) minOf(width.toDouble(), height * ratio) else maxOf(width.toDouble(), height * ratio)
        val bucket = widthBucket(sourceWidth)
        val ns = profile.cacheNamespace()
        val key = cacheKey(ns, owner, type, index, tag, bucket, quality, 1)
        val protocol = MediaServerProtocol(profile)
        val url = URLBuilder(protocol.base).apply {
            appendPathSegments("Items", owner, "Images", type, index.toString(), encodeSlash = true)
            parameters.append("MaxWidth", bucket.toString()); parameters.append("Quality", quality.toString())
            if (tag.isNotBlank()) parameters.append("Tag", tag)
        }.buildString()
        val headers = buildMap { val auth = protocol.authorization(true); put(auth.first, auth.second)
            if (!protocol.jellyfin) put("X-Emby-Token", profile.embyToken) }
        val art = Artwork(ns, key, url, headers, tag.isNotEmpty(), verifyTls, software)
        return ImageRequest.Builder(context).data(url).diskCacheKey(key).memoryCacheKey("$key:${if (software) "sw" else "hw"}:1")
            .size(width, height).precision(Precision.INEXACT).scale(if (fit) Scale.FIT else Scale.FILL)
            .allowHardware(!software).httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k,v) -> set(k,v) } }.build())
            .apply { extras[artworkExtra] = art }.build()
    }
    /** Public metadata artwork participates in the same index, expiry, clearing generation and budgets. */
    fun libraryRequest(identity: String, version: String?, url: String, width: Int, height: Int, software: Boolean = false): ImageRequest {
        PresentationArtwork.parse(url)?.let { return requireNotNull(presentationRequest(it, width, height, software = software)) }
        val namespace = "library-metadata"
        val key = cacheKey(namespace, identity, version, widthBucket(width.toDouble()), 1)
        val art = Artwork(namespace, key, url, emptyMap(), true, true, software)
        return ImageRequest.Builder(context).data(url).diskCacheKey(key).memoryCacheKey(if (software) "$key:sw:1" else key)
            .size(width, height).precision(Precision.INEXACT).scale(Scale.FILL)
            .allowHardware(!software)
            .apply { extras[artworkExtra] = art }.build()
    }
    /** Encoded bytes are keyed by content + variant; Coil checks decoded dimensions on memory lookup. */
    fun presentationRequest(reference: PresentationArtwork, width: Int, height: Int,
        sourceAspectRatio: Double? = null, fit: Boolean = false, software: Boolean = false): ImageRequest? {
        val url = reference.url(width, height, sourceAspectRatio, fit) ?: return null
        val namespace = "library-metadata"
        val key = cacheKey(namespace, url, 1)
        val art = Artwork(namespace, key, url, emptyMap(), true, true, software, publicRedirects = true)
        return ImageRequest.Builder(context).data(url).diskCacheKey(key).memoryCacheKey("$key:${if (software) "sw" else "hw"}:1")
            .size(width, height).precision(Precision.INEXACT).scale(if (fit) Scale.FIT else Scale.FILL)
            .allowHardware(!software).apply { extras[artworkExtra] = art }.build()
    }
    private suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val art = chain.request.extras[artworkExtra] ?: run {
            val key = chain.request.extras[libraryImageExtra] ?: return chain.proceed()
            // Local/NAS references use the same loader and decode budget. Serialize identical
            // target-size requests before Coil's memory lookup, not just the underlying download.
            return keys.withKey(key) { chain.proceed() }
        }
        admit()
        initialized.await()
        return domain.request(art.namespace) {
            if(!allowed(art.namespace)) throw CancellationException("Server identity retired")
            // Also serializes expiry against reads of other sizes of the same compressed image.
            keys.withKey(art.key) {
                if (synchronized(missing) { (missing[art.key] ?: 0) > clock.monotonic() })
                    return@withKey ErrorResult(null, chain.request, java.io.IOException("Image unavailable"))
                var pending = false
                var stamp = synchronized(hot) { hot[art.key] }
                if (stamp == null) stamp = indexed {
                    metrics.add("indexReads")
                    index.readableDatabase.rawQuery("SELECT stamp,ready FROM images WHERE key=?", arrayOf(art.key)).use { c ->
                        if (!c.moveToFirst()) null else {
                            val time = CacheTime.restore(c.getLong(0), clock)
                            if (c.getInt(1) == 0) {
                                pending = true
                                metrics.add("pendingSnapshots")
                                val valid = disk.openSnapshot(art.key)?.use { snapshot -> disk.fileSystem.metadata(snapshot.data).size?.let { it > 0 } == true } == true
                                if (!valid) { disk.remove(art.key); index.writableDatabase.delete("images", "key=?", arrayOf(art.key)); return@use null }
                            }
                            time
                        }
                    }
                }
                if (stamp != null && stamp.age(clock) == Long.MAX_VALUE || !art.tagged && (stamp == null || stamp.age(clock) >= 86_400_000)) {
                    indexed { disk.remove(art.key); index.writableDatabase.delete("images", "key=?", arrayOf(art.key)) }
                    loader.memoryCache?.remove(MemoryCache.Key("${art.key}:sw:1")); loader.memoryCache?.remove(MemoryCache.Key("${art.key}:hw:1"))
                    stamp = null
                }
                val result = chain.proceed()
                if (result is SuccessResult) {
                    metrics.add(result.dataSource.name.lowercase())
                    val time = if (result.dataSource == coil3.decode.DataSource.NETWORK) CacheTime.now(clock) else stamp ?: CacheTime.now(clock)
                    if (pending || result.dataSource == coil3.decode.DataSource.NETWORK) indexed {
                        index.writableDatabase.execSQL("UPDATE images SET ready=1 WHERE key=?", arrayOf(art.key))
                    }
                    synchronized(hot) { hot[art.key] = time }
                    synchronized(touches) { if (touches.size < 512) touches[art.key] = clock.wall() }
                } else if (result is ErrorResult) {
                    metrics.add("errors")
                    val status = (result.throwable as? HttpException)?.response?.code
                    if (status == 404) synchronized(missing) {
                        if (missing.size >= 512) missing.clear()
                        missing[art.key] = clock.monotonic() + 30_000
                    }
                    indexed { disk.remove(art.key); index.writableDatabase.delete("images", "key=?", arrayOf(art.key)) }
                    synchronized(hot) { hot.remove(art.key) }
                }
                result
            }
        }
    }
    private suspend fun <T> indexed(block: () -> T): T = withContext(Dispatchers.IO) { housekeeping.withLock { block() } }
    /** NAS artwork shares the same disk budget, clear/retention journal and four download permits. */
    private suspend fun nasImage(art: NasArtwork): Pair<ByteArray, Boolean> {
        admit(); initialized.await()
        val runtime = LocalLibraryRuntime.get(context)
        runtime.awaitReady()
        val profiles = runtime.serverProfiles.value.values.toList()
        val cloud = cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(art.uri)
        val profile = if (cloud) cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.profile(art.uri, profiles)
            else NasLocator.profile(art.uri, profiles)
        val namespace = profile.cacheNamespace()
        val key = cacheKey(namespace, "nas", art.uri, art.version)
        return domain.request(namespace) {
            if (!allowed(namespace)) throw CancellationException("NAS identity retired")
            keys.withKey(key) {
                val cached = disk.openSnapshot(key)?.use { snapshot -> disk.fileSystem.read(snapshot.data) { readByteArray() } }
                if (cached != null) {
                    synchronized(touches) { if (touches.size < 512) touches[key] = clock.wall() }
                    metrics.add("disk"); return@withKey cached to true
                }
                val bytes = downloads.withPermit {
                    metrics.add("downloads"); metrics.peak("activeDownloads", active.incrementAndGet().toLong())
                    try {
                        if (cloud) cn.coldlake.stellar.opensource.medialibrary.cloud.cloudProvider(context, profile,
                            cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.ref(art.uri)).use {
                            it.smallFile(cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.ref(art.uri).id, MAX_IMAGE_BYTES)
                        } else NasLibrarySource(context, profiles).read(art.uri, MAX_IMAGE_BYTES)
                    }
                    finally { active.decrementAndGet() }
                }
                currentCoroutineContext().ensureActive()
                indexed {
                    disk.openEditor(key)?.let { editor ->
                        try {
                            index.writableDatabase.execSQL("INSERT OR REPLACE INTO images VALUES(?,?,?,?,0)",
                                arrayOf<Any>(key, namespace, clock.wall(), clock.wall()))
                            disk.fileSystem.write(editor.data) { write(bytes) }; editor.commit()
                            index.writableDatabase.execSQL("UPDATE images SET ready=1 WHERE key=?", arrayOf(key))
                        } catch (error: Throwable) { editor.abort(); throw error }
                    }
                }
                metrics.add("downloadBytes", bytes.size.toLong()); bytes to false
            }
        }
    }
    suspend fun flushTouches() = withContext(Dispatchers.IO) {
        initialized.await()
        housekeeping.withLock {
        val batch = synchronized(touches) { touches.toMap().also { touches.clear() } }
        if (batch.isNotEmpty()) runCatching {
            val db = index.writableDatabase; db.beginTransaction()
            try { db.compileStatement("UPDATE images SET touched=? WHERE key=?").use { s ->
                for ((key,time) in batch) { s.bindLong(1, time); s.bindString(2,key); s.executeUpdateDelete() }
            }; db.setTransactionSuccessful(); metrics.add("touchTransactions") }
            finally { db.endTransaction() }
        }
        }
    }
    fun retry(namespace: String) {
        synchronized(missing) { missing.clear() }
        // A cleared cooldown alone cannot restart a Coil painter already in Error.
        mutableRetries.update { CacheChange(it.sequence + 1, namespace) }
    }
    fun trimMemory() { loader.memoryCache?.clear(); synchronized(hot) { hot.clear() }; synchronized(colors) { colors.clear() } }
    suspend fun color(request: ImageRequest,bitmap: android.graphics.Bitmap, wholeImage: Boolean = false): Int = domain.request(request.extras[artworkExtra]?.namespace.orEmpty()) {
        withContext(Dispatchers.Default) {
        val key=checkNotNull(request.diskCacheKey)
        synchronized(colors) { colors[key]?.let { if (wholeImage) it.seed else it.edge } } ?: run {
            val result = if (wholeImage) artworkSeed(bitmap) else {
            var r=0L; var g=0L; var b=0L; var count=0
            for(y in bitmap.height*4/5 until bitmap.height step maxOf(1,bitmap.height/40)) for(x in 0 until bitmap.width step maxOf(1,bitmap.width/40)) {
                val pixel=bitmap.getPixel(x,y); r+=android.graphics.Color.red(pixel); g+=android.graphics.Color.green(pixel); b+=android.graphics.Color.blue(pixel); count++
            }
            android.graphics.Color.rgb((r/count).toInt(),(g/count).toInt(),(b/count).toInt())
            }
            synchronized(colors) {
                val previous = colors[key] ?: ArtworkColors()
                colors[key] = if (wholeImage) previous.copy(seed = result) else previous.copy(edge = result)
            }
            result
        }
        }
    }
    suspend fun awaitReady() { initialized.await() }
    suspend fun clear(namespace: String? = null): Long {
        initialized.await()
        var bytes=0L
        domain.clear(namespace, { withContext(Dispatchers.IO) { housekeeping.withLock { index.mark(namespace) } } }, {
            withContext(Dispatchers.IO) { housekeeping.withLock { remove(namespace); bytes=index.actualBytes() } }
        })
        mutableCleared.value=CacheChange(mutableCleared.value.sequence+1,namespace)
        return bytes
    }
    private fun remove(namespace: String?) {
        val removed = index.imageKeys(namespace)
        if (namespace == null) { disk.clear(); check(disk.size==0L) { "Image cache still in use" }; loader.memoryCache?.clear() }
        else for (key in removed) {
            removeFile(key)
            loader.memoryCache?.remove(MemoryCache.Key("$key:sw:1")); loader.memoryCache?.remove(MemoryCache.Key("$key:hw:1"))
        }
        synchronized(hot) { if (namespace == null) hot.clear() else removed.forEach { hot.remove(it) } }
        synchronized(colors) { if(namespace==null) colors.clear() else removed.forEach { colors.remove(it) } }
        synchronized(missing) { missing.clear() }; synchronized(touches) { touches.keys.removeAll(removed.toSet()) }
        index.delete(namespace); index.compact(4L*1024*1024,true); index.complete(namespace)
    }
    suspend fun maintain(retentionMillis: Long, maximumEntries: Int = 20_000): Boolean = withContext(Dispatchers.IO) {
        initialized.await(); flushTouches()
        var before: Pair<Long,String>?=null
        var kept=0
        var more=true
        val now=clock.wall()
        while(more) {
            currentCoroutineContext().ensureActive()
            if(!domain.idle { housekeeping.withLock {
                val db=index.writableDatabase
                val cursor=before
                val rows=db.rawQuery("SELECT key,touched,ready FROM images"+
                    (if(cursor==null) "" else " WHERE (touched,key)<(?,?)")+" ORDER BY touched DESC,key DESC LIMIT 128",
                    cursor?.let { arrayOf(it.first.toString(),it.second) }).use { c ->
                    buildList { while(c.moveToNext()) add(Triple(c.getString(0),c.getLong(1),c.getInt(2))) }
                }
                more=rows.size==128
                before=rows.lastOrNull()?.let { it.second to it.first }
                metrics.peak("maintenanceBatch",rows.size.toLong())
                db.beginTransaction()
                try {
                    for((key,touched,ready) in rows) {
                        currentCoroutineContext().ensureActive()
                        val expired=touched<now-retentionMillis || touched>now || kept>=maximumEntries
                        val exists=if(expired) true else disk.openSnapshot(key)?.use { snapshot ->
                            disk.fileSystem.metadata(snapshot.data).size?.let { it>0 } == true
                        } == true
                        if(expired || !exists) {
                            removeFile(key); db.delete("images","key=?",arrayOf(key)); evictMemory(key)
                            metrics.add(if(expired) "retentionRemoved" else "orphanIndexRemoved")
                        } else {
                            kept++
                            if(ready==0) db.execSQL("UPDATE images SET ready=1 WHERE key=?",arrayOf(key))
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } }) return@withContext false
            // Admit foreground image work between bounded transactions.
            yield()
        }
        var compacted=false
        while(!compacted) {
            currentCoroutineContext().ensureActive()
            if(!domain.idle { housekeeping.withLock {
                val db=index.writableDatabase
                index.checkpoint()
                val oldest=if(index.liveBytes()>3_500_000L) db.rawQuery("SELECT key FROM images ORDER BY touched LIMIT 128",null)
                    .use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } } else emptyList()
                if(oldest.isEmpty()) {
                    index.compact(4L*1024*1024); compacted=true; metrics.add("maintenanceRuns")
                } else {
                    db.beginTransaction()
                    try {
                        for(key in oldest) {
                            currentCoroutineContext().ensureActive()
                            removeFile(key); db.delete("images","key=?",arrayOf(key)); evictMemory(key)
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            } }) return@withContext false
            yield()
        }
        true
    }
    private fun evictMemory(key: String) {
        loader.memoryCache?.remove(MemoryCache.Key("$key:sw:1")); loader.memoryCache?.remove(MemoryCache.Key("$key:hw:1"))
        synchronized(hot) { hot.remove(key) }; synchronized(colors) { colors.remove(key) }
    }
    private fun removeFile(key: String) {
        // Coil.remove may return true while a held snapshot leaves zombie files behind.
        // Inspect only paths returned by the public snapshot API, never guessed filenames.
        val files=disk.openSnapshot(key)?.use { listOf(it.metadata,it.data) }
        val removed=disk.remove(key)
        check(if(files!=null) files.none { disk.fileSystem.exists(it) } else !removed) { "Image cache still in use" }
    }
    suspend fun diskBytes() = withContext(Dispatchers.IO) { initialized.await(); housekeeping.withLock { index.actualBytes() } }
    suspend fun shutdown() {
        try { flushTouches() }
        finally { scope.cancel(); loader.shutdown(); withContext(NonCancellable + Dispatchers.IO) { index.close() } }
    }
    companion object { fun widthBucket(width: Double) = listOf(160,320,480,640,960,1280,1920).firstOrNull { it >= width } ?: 1920 }
}

/** Exact owned filenames only; a corrupt disposable index never deletes the app cache root. */
internal object SQLiteRecovery {
    fun rebuild(directory: File, index: CacheDatabase, disk: DiskCache? = null) {
        disk?.clear()
        for (name in listOf("index.db", "index.db-wal", "index.db-shm", "index.db-journal")) File(directory, name).delete()
        index.writableDatabase
    }
}
