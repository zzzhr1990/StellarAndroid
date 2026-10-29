package cn.coldlake.stellar.opensource.cache

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal class MediaDataCache(context: Context, private val clock: CacheClock = CacheClock.SYSTEM,
    directory: File = File(context.cacheDir,"media-data-v1"),
    private val admit: suspend () -> Unit = {}, private val allowed: (String) -> Boolean = { true },
    private val enabled: () -> Boolean = { true }) {
    // Tickets exist only while a request or its queued write exists. No permanent epoch maps.
    private class Ticket(val query: DataQuery) { var valid = true; var queued = false }
    private data class Write(val ticket: Ticket, val entry: DataEntry, val payload: String) {
        // Retained DTO estimate plus a conservative UTF-16 budget for the encoded response.
        val bytes: Long get() = entry.bytes.toLong() + payload.length * 2L
    }
    val metrics = CacheMetrics()
    private val memory = DataMemory(metrics)
    val directories = DirectoryCache(memory,clock,admit,enabled)
    private val index = CacheDatabase(context,directory,false)
    private val store = MediaResponseStore(index,clock,metrics)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val domain = CacheDomain()
    private val locks = KeyLocks()
    private val sql = Mutex()
    private val network = Semaphore(3)
    private val sequence = AtomicLong()
    private val state = Any()
    private val tickets = mutableSetOf<Ticket>()
    private val writes = LinkedHashMap<String,Write>()
    private var writerActive = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val changes = LinkedHashMap<String,UserStamp>()
    private val touches = mutableMapOf<String,Long>()
    private val mutableChanges = MutableStateFlow(CacheChange())
    val cleared = mutableChanges.asStateFlow()
    @Volatile private var discardDisk = false // A failed invalidation must never expose the old index again.
    private val ready = CacheInitialization {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            try { index.writableDatabase } catch (_: android.database.sqlite.SQLiteDatabaseCorruptException) {
                index.close(); SQLiteRecovery.rebuild(directory,index)
            }
            if (!enabled()) index.mark(null)
            for (ns in index.pending()) {
                val namespace = ns.takeUnless { it=="*" }
                index.delete(namespace); index.compact(32L*1024*1024,true); index.complete(namespace)
            }
        }
    }
    init {
        scope.launch {
            for (signal in wake) while (true) {
                val write = synchronized(state) {
                    writes.entries.firstOrNull()?.let { writes.remove(it.key); writerActive=true; it.value }
                } ?: break
                try { disk {
                    if (synchronized(state) { write.ticket.valid } && enabled() && allowed(write.ticket.query.namespace)
                        && !domain.blocked(write.ticket.query.namespace))
                        store.persist(write.entry, write.payload) { id -> synchronized(changes) { changes["${write.ticket.query.namespace}:$id"] } }
                } } finally { synchronized(state) { tickets.remove(write.ticket); writerActive=false } }
            }
        }
        scope.launch { while (isActive) { delay(5_000); flushTouches() } }
    }
    // All initialization and disk access are serialized; failed initialization is retried next time.
    private suspend fun prepareStorage() {
        ready.await()
        if (discardDisk) {
            index.mark(null); index.delete(null); index.compact(32L*1024*1024,true); index.complete(null)
            discardDisk=false
        }
    }
    private suspend fun <T> storage(action: () -> T): T = withContext(Dispatchers.IO) { sql.withLock { prepareStorage(); action() } }
    private suspend fun <T> disk(invalidation: Boolean = false, action: () -> T): T? = withContext(Dispatchers.IO) { sql.withLock {
        try { prepareStorage(); action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (invalidation) discardDisk=true
            metrics.add("storageFailures"); null
        }
    } }
    suspend fun awaitReady() { storage {} }
    private fun touch(key: String) = synchronized(touches) { if(touches.size<512) touches[key]=clock.wall() }
    suspend fun flushTouches() {
        val batch=synchronized(touches) { touches.toMap().also { touches.clear() } }
        if(batch.isNotEmpty()) disk { store.touch(batch) }
    }
    private fun delivery(entry: DataEntry, policy: DataPolicy, cached: Boolean): CacheDelivery {
        val items=entry.value.items.map { item ->
            val local=synchronized(changes) { changes["${entry.query.namespace}:${item.id}"] }
            val stored=entry.states[item.id]
            val current=local?.takeIf { it.revision>(stored?.revision ?: 0) } ?: stored
            current?.apply(item,clock) ?: item.copy(userStateKnown=false,played=false,favorite=false,resumeMs=0,unplayed=0)
        }
        val fresh=if(entry.value.empty()) minOf(policy.fresh,15_000) else policy.fresh
        return CacheDelivery(entry.value.copy(items=items),cached,entry.time.age(clock)>=fresh,entry.time.wall)
    }
    private fun invalidate(predicate: (DataQuery) -> Boolean) = synchronized(state) {
        tickets.filter { predicate(it.query) }.forEach { it.valid=false }
        memory.removeWhere { _, value -> value is DataEntry && predicate(value.query) }
    }
    suspend fun load(namespace: String, key: String, group: String, kind: String, policy: DataPolicy,
        force: Boolean = false, onValue: suspend (CacheDelivery)->Unit,
        cacheChecked: suspend ()->Unit = {}, fetch: suspend ()->MediaPayload): MediaPayload {
        admit()
        return domain.request(namespace,allowBlocked=true) {
            if(!allowed(namespace)) throw CancellationException("Server identity retired")
            val ticket=Ticket(DataQuery(namespace,key,group,kind))
            synchronized(state) { tickets+=ticket }
            suspend fun valid() {
                currentCoroutineContext().ensureActive()
                if(!allowed(namespace) || !synchronized(state) { ticket.valid }) throw CancellationException("Cache invalidated")
            }
            try {
                val cacheable=enabled() && !domain.blocked(namespace) && (ready.initialized && !discardDisk || disk { true }==true)
                if(!cacheable) {
                    cacheChecked()
                    val fresh=withContext(Dispatchers.Default) { network.withPermit { metrics.add("requests"); fetch() } }
                    valid(); onValue(CacheDelivery(fresh,false,false,clock.wall())); return@request fresh
                }
                val initial=(memory.get("media:$key") as? DataEntry ?: if(policy.disk) disk { store.read(ticket.query) } else null)
                    ?.takeIf { it.query.namespace==namespace && it.query.kind==kind }
                val maximum=if(initial?.value?.empty()==true) minOf(policy.maximum,60_000) else policy.maximum
                var cached: CacheDelivery?=null
                if(initial!=null && initial.time.age(clock)<maximum) {
                    valid(); synchronized(state) { if(ticket.valid) memory.put("media:$key",initial) }
                    touch(key); cached=delivery(initial,policy,true); onValue(cached); metrics.add("hits")
                }
                cacheChecked()
                if(cached!=null && !force && !cached.stale && !(kind=="detail" &&
                    (initial!!.states.size<initial.value.items.size || initial.states.values.any { it.time.age(clock)>=30_000 }))) return@request cached.value
                locks.withKey(key) {
                    valid()
                    val validated=memory.get("media:$key") as? DataEntry
                    if(validated!=null && validated.query==ticket.query && validated.revision>(initial?.revision ?: -1) && !delivery(validated,policy,true).stale) {
                        touch(key); val value=delivery(validated,policy,true); onValue(value); return@withKey value.value
                    }
                    val revision=sequence.incrementAndGet(); val start=CacheTime.now(clock)
                    val fresh=try { withContext(Dispatchers.Default) { network.withPermit { metrics.add("requests"); fetch() } } }
                    catch(e: EmbyFailure) {
                        if(e.reason in setOf(ServerError.AUTHENTICATION,ServerError.PERMISSION)) {
                            invalidate { it.namespace==namespace }
                            disk(invalidation=true) { index.delete(namespace) }
                        } else if(e.reason==ServerError.PATH) invalidateQuery(namespace,key,if(kind=="detail") group else null)
                        throw e
                    }
                    valid()
                    val (entry,payload)=withContext(Dispatchers.Default) {
                        val value=fresh.staticOnly()
                        val states=fresh.items.associate { it.id to UserStamp(it.favorite,it.played,it.resumeMs,it.unplayed,start,revision) }
                        val encoded=MediaCacheCodec.response(value)
                        val bytes=encoded.toByteArray().size+states.values.sumOf { it.encode().toByteArray().size }
                        DataEntry(ticket.query,value,states,CacheTime.now(clock),bytes,revision) to encoded
                    }
                    valid()
                    synchronized(state) { if(ticket.valid && enabled()) memory.put("media:$key",entry) }
                    val output=delivery(entry,policy,false); onValue(output)
                    if(policy.disk) synchronized(state) {
                        val write=Write(ticket,entry,payload)
                        val queued=writes.values.filter { it.ticket.query.key!=key }.sumOf { it.bytes }
                        if(ticket.valid && enabled() && (writes.size<8 || key in writes) && queued+write.bytes<=8*1024*1024) {
                            writes.remove(key)?.let { tickets.remove(it.ticket) }
                            ticket.queued=true; writes[key]=write
                            metrics.peak("writeQueue",writes.size.toLong()); wake.trySend(Unit)
                        } else metrics.add("skippedWrites")
                    }
                    output.value
                }
            } finally { synchronized(state) { if(!ticket.queued) tickets.remove(ticket) } }
        }
    }
    suspend fun invalidateQuery(namespace: String,key: String,itemId: String?=null) {
        val affected=mutableSetOf(key)
        synchronized(state) {
            memory.removeWhere { _, value ->
                (value is DataEntry && value.query.namespace==namespace && itemId!=null && value.value.items.any { it.id==itemId })
                    .also { if(it) affected+=(value as DataEntry).query.key }
            }
            writes.values.filter { it.entry.query.namespace==namespace && itemId!=null && it.entry.value.items.any { row -> row.id==itemId } }
                .forEach { affected+=it.entry.query.key }
        }
        disk(invalidation=true) {
            if(itemId!=null) affected+=store.keys("SELECT key FROM members WHERE namespace=? AND id=?",arrayOf(namespace,itemId))
            invalidate { it.namespace==namespace && it.key in affected }
            store.transaction { store.remove(affected.toList()) }
        }
        invalidate { it.namespace==namespace && it.key in affected }
    }
    suspend fun refreshGroup(group: String, preserve: String? = null) {
        // Preserve first-page display, but reject every old request/write in the group.
        synchronized(state) { tickets.filter { it.query.group==group }.forEach { it.valid=false } }
        memory.removeWhere { _, value -> value is DataEntry && value.query.group==group && value.query.key!=preserve }
        disk(invalidation=true) { store.invalidate("group_key=? AND key!=?",arrayOf(group,preserve ?: "")) }
    }
    private fun change(namespace: String,id: String,state: UserStamp) = synchronized(changes) {
        changes["$namespace:$id"]=state
        while(changes.size>256) changes.remove(changes.keys.first())
    }
    fun beginMutation(namespace: String,item: EmbyItem,favorite: Boolean): Long {
        val revision=sequence.incrementAndGet()
        change(namespace,item.id,UserStamp(if(favorite) !item.favorite else item.favorite,
            if(favorite) item.played else !item.played,item.resumeMs,item.unplayed,CacheTime.now(clock),revision))
        return revision
    }
    fun rollback(namespace: String,id: String,revision: Long) { synchronized(changes) {
        if(changes["$namespace:$id"]?.revision==revision) changes.remove("$namespace:$id")
    } }
    suspend fun invalidateProgressQueries(namespace: String) {
        invalidate { it.namespace == namespace && it.kind == "user" }
        disk(invalidation = true) { store.invalidate("namespace=? AND kind='user'", arrayOf(namespace)) }
    }
    suspend fun progressReported(namespace: String,item: EmbyItem,position: Long) {
        val previous=synchronized(changes) { changes["$namespace:${item.id}"] }
        change(namespace,item.id,UserStamp(previous?.favorite ?: item.favorite,previous?.played ?: item.played,
            position,item.unplayed,CacheTime.now(clock),sequence.incrementAndGet()))
        invalidate { it.namespace==namespace && it.kind=="user" }
        disk(invalidation=true) { store.invalidate("namespace=? AND kind='user'",arrayOf(namespace)) }
    }
    suspend fun userChanged(namespace: String, clearStates: Boolean = false) {
        invalidate { it.namespace==namespace }
        if(clearStates) synchronized(changes) { changes.keys.removeAll { it.startsWith("$namespace:") } }
        disk(invalidation=true) {
            store.invalidate(if(clearStates) "namespace=? AND kind='user'" else "namespace=? AND kind!='detail'",arrayOf(namespace))
            if(clearStates) store.clearStates(namespace)
        }
    }
    suspend fun awaitWrites() { while(synchronized(state) { writes.isNotEmpty() || writerActive }) delay(5) }
    fun trimMemory() { memory.clear() }
    suspend fun clear(namespace: String?=null, disabled: Boolean=false): Long {
        var bytes=0L
        try { domain.clear(namespace,{
            invalidate { namespace==null || it.namespace==namespace }
            synchronized(changes) { changes.keys.removeAll { namespace==null || it.startsWith("$namespace:") } }
            if(namespace==null) directories.clear()
            storage { index.mark(namespace) }
        },{
            storage {
                synchronized(touches) { touches.clear() }
                index.delete(namespace); index.compact(32L*1024*1024,true); index.complete(namespace); bytes=index.actualBytes()
            }
        }) } finally {
            // The UI must also stop presenting cache provenance when physical cleanup fails.
            mutableChanges.value=CacheChange(mutableChanges.value.sequence+1,namespace,disabled)
        }
        return bytes
    }
    suspend fun maintain(retentionMillis: Long): Boolean {
        flushTouches(); awaitWrites()
        return domain.idle { storage {
            val expired=store.keys("SELECT key FROM responses WHERE touched<? OR touched>?",arrayOf((clock.wall()-retentionMillis).toString(),clock.wall().toString()))
            store.transaction { store.remove(expired); store.pruneStates() }
            memory.removeWhere { _, value -> value is DataEntry && value.query.key in expired }
            index.checkpoint()
            while(index.liveBytes()>28L*1024*1024) {
                val keys=store.keys("SELECT key FROM responses ORDER BY touched LIMIT 16")
                if(keys.isEmpty()) break
                store.transaction { store.remove(keys) }
                memory.removeWhere { _, value -> value is DataEntry && value.query.key in keys }
            }
            index.compact(32L*1024*1024)
            metrics.add("maintenanceRuns"); metrics.add("retentionRemoved",expired.size.toLong())
        } }
    }
    suspend fun diskBytes() = storage { index.actualBytes() }
    suspend fun shutdown() {
        try { awaitWrites(); flushTouches() }
        finally { scope.cancel(); withContext(NonCancellable + Dispatchers.IO) { sql.withLock { index.close() } } }
    }
}
