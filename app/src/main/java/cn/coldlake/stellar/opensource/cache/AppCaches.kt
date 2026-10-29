package cn.coldlake.stellar.opensource.cache

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile

internal class AppCaches internal constructor(val context: Context, private val storageId: String = "") : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    val settings = CacheSettings(context,"media_cache_settings_v1$storageId")
    private val mutable = MutableStateFlow(CacheMaintenanceState())
    val maintenance = mutable.asStateFlow()
    private val retired = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val journal by lazy { context.getSharedPreferences("media_cache_transitions_v1$storageId", Context.MODE_PRIVATE) }
    val images: AppImageLoader by lazy { AppImageLoader(context, directory=java.io.File(context.cacheDir,"media-images-v1$storageId"), admit = { ready.await() }, allowed = { it !in retired }) }
    val data: MediaDataCache by lazy { MediaDataCache(context, directory=java.io.File(context.cacheDir,"media-data-v1$storageId"), admit = { ready.await() }, allowed = { it !in retired },
        enabled = { settings.initialized && settings.state.value.dataEnabled }) }
    private val ready = CacheInitialization {
        // Admission knows retired identities before either optional cache opens its database.
        retired += journal.getStringSet("pending", emptySet()).orEmpty()
        settings.initialize()
    }
    private val recovered = CacheInitialization {
        ready.await()
        data.awaitReady(); images.awaitReady()
        // A transition is journaled before either cache or the server repository changes.
        val pending = journal.getStringSet("pending", emptySet()).orEmpty().toSet()
        for (namespace in pending) { data.clear(namespace); images.clear(namespace) }
        check(journal.edit().remove("pending").commit())
        retired.removeAll(pending)
    }
    private var background: Job? = null
    init { scope.launch { runCatching { recovered.await() } } }
    suspend fun awaitReady() = recovered.await()
    suspend fun shutdown() {
        background?.cancelAndJoin()
        try { data.shutdown() } finally { try { images.shutdown() } finally { scope.cancel() } }
    }
    fun refreshUsage() = scope.launch { operations.withLock {
        mutable.value = mutable.value.copy(data = CacheUsage(work=CacheWork.CALCULATING), images=CacheUsage(work=CacheWork.CALCULATING), busy=true)
        measure(false); mutable.value=mutable.value.copy(busy=false)
    } }
    private suspend fun measure(done: Boolean, imageOnly: Boolean? = null) {
        suspend fun usage(read: suspend () -> Long) = try { ready.await(); CacheUsage(read(), if(done) CacheWork.DONE else CacheWork.IDLE) }
            catch (_: Exception) { CacheUsage(work=CacheWork.FAILED) }
        if (imageOnly != true) mutable.value=mutable.value.copy(data=usage { data.diskBytes() })
        if (imageOnly != false) mutable.value=mutable.value.copy(images=usage { images.diskBytes() })
    }
    fun clear(image: Boolean) = clearCaches(image)
    fun clearAll() = clearCaches(false, true)
    private fun clearCaches(vararg imageCaches: Boolean) = scope.launch { operations.withLock {
        val busy = CacheUsage(work=CacheWork.CLEARING)
        mutable.value=mutable.value.copy(
            data=if(false in imageCaches) busy else mutable.value.data,
            images=if(true in imageCaches) busy else mutable.value.images, busy=true)
        try {
            for(image in imageCaches) {
                val usage=try {
                    ready.await()
                    CacheUsage(if(image) images.clear() else data.clear(),CacheWork.DONE)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CacheUsage(work=CacheWork.FAILED) }
                mutable.value=if(image) mutable.value.copy(images=usage) else mutable.value.copy(data=usage)
            }
        } finally { mutable.value=mutable.value.copy(busy=false) }
    } }
    fun setDataEnabled(enabled: Boolean) = scope.launch { operations.withLock {
        mutable.value=mutable.value.copy(busy=true,data=CacheUsage(work=CacheWork.CLEARING))
        try {
            ready.await()
            // Persist disabled before clearing; restart must finish cleanup before serving cache hits.
            if (!enabled) {
                settings.save(settings.state.value.copy(dataEnabled=false))
                data.clear(disabled=true)
            } else {
                // Also repairs a failed earlier cleanup before admitting writes again.
                data.clear(disabled=true)
                settings.save(settings.state.value.copy(dataEnabled=true))
            }
            measure(true,false)
        } catch (_: Exception) { mutable.value=mutable.value.copy(data=CacheUsage(work=CacheWork.FAILED)) }
        finally { mutable.value=mutable.value.copy(busy=false) }
    } }
    fun setRetention(image: Boolean, retention: CacheRetention) = scope.launch { operations.withLock {
        mutable.value=mutable.value.copy(busy=true)
        try {
            ready.await()
            val old=settings.state.value
            settings.save(if(image) old.copy(imageRetention=retention) else old.copy(dataRetention=retention))
            // Idle-only; an active browser is never cancelled for retention work.
            if(image) images.maintain(retention.millis) else data.maintain(retention.millis)
            measure(false,image)
        } catch (_: Exception) {
            val failed=CacheUsage(work=CacheWork.FAILED)
            mutable.value=if(image) mutable.value.copy(images=failed) else mutable.value.copy(data=failed)
        } finally { mutable.value=mutable.value.copy(busy=false) }
    } }
    suspend fun profileChange(old: ServerProfile?, new: ServerProfile?, persist: () -> Unit) = operations.withLock {
        ready.await()
        val ns=old?.cacheNamespace()
        if(ns==null || ns==new?.cacheNamespace() && ns !in retired) {
            withContext(Dispatchers.IO) { persist() }
            // A renewed media login keeps artwork/static payloads but must revalidate user state.
            if(old?.kind?.mediaServer==true && new!=null && (old.embyToken!=new.embyToken || old.username!=new.username || old.password!=new.password))
                data.userChanged(ns!!, clearStates=true)
            return@withLock
        }
        withContext(NonCancellable + Dispatchers.IO) {
            val pending=journal.getStringSet("pending",emptySet()).orEmpty().toSet()+ns
            check(journal.edit().putStringSet("pending",pending).commit())
            retired += ns
            data.clear(ns); images.clear(ns)
            persist()
            check(journal.edit().putStringSet("pending",pending-ns).commit())
            new?.let { retired.remove(it.cacheNamespace()) }
        }
    }
    private fun scheduleMaintenance() {
        background?.cancel()
        background=scope.launch {
            delay(2_000)
            operations.withLock { runCatching {
                ready.await()
                data.maintain(settings.state.value.dataRetention.millis)
                images.maintain(settings.state.value.imageRetention.millis)
            } }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() { images.trimMemory(); data.trimMemory() }
    override fun onTrimMemory(level: Int) {
        if(level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) scheduleMaintenance()
        else if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory()
    }
    companion object {
        @Volatile var current: AppCaches? = null; private set
        fun install(context: Context) { if(current==null) synchronized(this) {
            if(current==null) current=AppCaches(context.applicationContext).also { caches ->
                context.registerComponentCallbacks(caches)
                (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                    private var started=0
                    override fun onActivityStarted(activity: android.app.Activity) { started++; caches.background?.cancel() }
                    override fun onActivityStopped(activity: android.app.Activity) { if(--started==0) caches.scheduleMaintenance() }
                    override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
                    override fun onActivityResumed(activity: android.app.Activity) = Unit
                    override fun onActivityPaused(activity: android.app.Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
                    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
                })
            }
        } }
    }
}
