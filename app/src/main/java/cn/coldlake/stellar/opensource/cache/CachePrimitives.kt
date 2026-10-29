package cn.coldlake.stellar.opensource.cache

import android.os.SystemClock
import cn.coldlake.stellar.opensource.medialibrary.emby.mediaServerBase
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun cacheKey(vararg values: Any?): String {
    val canonical = values.joinToString("") { value -> value.toString().let { "${it.length}:$it" } }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
}
internal fun ServerProfile.cacheNamespace() = if (kind.mediaServer)
    cacheKey(1, kind.name, id, mediaServerBase(address, kind), embyServerId, embyUserId)
else if (kind == cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind.ONEDRIVE)
    cacheKey(1, kind.name, id, address, cloudAccountId)
else cacheKey(1, kind.name, id, address, port, share, path, username, domain, nfsVersion, uid, gid)

internal interface CacheClock {
    fun wall(): Long
    fun monotonic(): Long
    companion object { val SYSTEM = object : CacheClock {
        override fun wall() = System.currentTimeMillis()
        override fun monotonic() = SystemClock.elapsedRealtime()
    } }
}
internal data class CacheTime(val wall: Long, val mono: Long) {
    fun age(clock: CacheClock): Long {
        val elapsed = clock.monotonic() - mono
        val wallElapsed = clock.wall() - wall
        return if (elapsed < 0 || wallElapsed < 0 || kotlin.math.abs(wallElapsed - elapsed) > 300_000) Long.MAX_VALUE else elapsed
    }
    companion object {
        fun now(clock: CacheClock) = CacheTime(clock.wall(), clock.monotonic())
        fun restore(wall: Long, clock: CacheClock): CacheTime? {
            val age = clock.wall() - wall
            return if (age < 0) null else CacheTime(wall, clock.monotonic() - age)
        }
    }
}
internal class CacheMetrics {
    private val values = ConcurrentHashMap<String, AtomicLong>()
    fun add(name: String, amount: Long = 1) { values.getOrPut(name) { AtomicLong() }.addAndGet(amount) }
    fun peak(name: String, value: Long) { values.getOrPut(name) { AtomicLong() }.updateAndGet { maxOf(it, value) } }
    fun snapshot() = values.mapValues { it.value.get() }
}

/** Short registration locks only; network/IO work is never run under the registry lock. */
internal class KeyLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(entries) { if (--entry.users == 0) entries.remove(key) } }
    }
}

/** A failed attempt is retryable; only successful initialization is memoized. */
internal class CacheInitialization(private val initialize: suspend () -> Unit) {
    private val mutex = Mutex()
    @Volatile private var ready = false
    val initialized get() = ready
    suspend fun await() {
        if (!ready) mutex.withLock { if (!ready) { initialize(); ready = true } }
    }
}

/** The gate only closes for maintenance. Failed cleanup blocks its cache scope, not the gate. */
internal class CacheDomain {
    private val lock = Any()
    private val maintenance = Mutex()
    private var gate = CompletableDeferred(Unit)
    private val jobs = mutableMapOf<Job, String>()
    private val blocked = mutableSetOf<String?>()
    fun blocked(namespace: String): Boolean = synchronized(lock) { null in blocked || namespace in blocked }
    suspend fun <T> request(namespace: String, allowBlocked: Boolean = false, action: suspend () -> T): T = coroutineScope {
        val self = currentCoroutineContext().job
        var admitted = false
        try {
            while (!admitted) {
                val wait = synchronized(lock) {
                    if (gate.isCompleted) { jobs[self] = namespace; admitted = true }
                    gate
                }
                wait.await()
            }
            if (!allowBlocked && blocked(namespace)) throw java.io.IOException("Cache cleanup pending")
            action().also { ensureActive() }
        }
        finally { synchronized(lock) { jobs.remove(self) } }
    }
    suspend fun clear(namespace: String?, mark: suspend () -> Unit, delete: suspend () -> Unit) = maintenance.withLock {
        val wait = CompletableDeferred<Unit>()
        val old = synchronized(lock) {
            gate = wait
            blocked += namespace
            jobs.filterValues { namespace == null || it == namespace }.keys.toList()
        }
        // NonCancellable closes the crash-recovery sequence even if a settings screen leaves.
        withContext(NonCancellable) {
            try {
                old.forEach { it.cancel() }; old.joinAll()
                mark()
                delete()
                synchronized(lock) { if (namespace == null) blocked.clear() else blocked.remove(namespace) }
            } finally { wait.complete(Unit) }
        }
    }
    /** Skip routine maintenance while consumers are active; never cancel browsing for retention. */
    suspend fun idle(action: suspend () -> Unit): Boolean = maintenance.withLock {
        val wait = CompletableDeferred<Unit>()
        val admitted = synchronized(lock) {
            if (jobs.isNotEmpty() || !gate.isCompleted || blocked.isNotEmpty()) false
            else { gate = wait; true }
        }
        if (!admitted) return@withLock false
        try { action(); true }
        finally { wait.complete(Unit) }
    }
}
