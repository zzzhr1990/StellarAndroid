package cn.coldlake.stellar.opensource.medialibrary.emby

import android.graphics.Bitmap
import cn.coldlake.stellar.opensource.medialibrary.catalog.*
import cn.coldlake.stellar.opensource.navigation.MediaDestination
import cn.coldlake.stellar.opensource.navigation.MediaNavigation
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.StateFlow
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import cn.coldlake.stellar.opensource.cache.*
import cn.coldlake.stellar.opensource.medialibrary.ScrollPosition
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlaybackAvailability { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE }
internal data class EmbyBrowserState(
    val profile: ServerProfile? = null, val location: EmbyLocation = EmbyLocation(),
    val items: List<EmbyItem> = emptyList(), val total: Int = 0, val nextIndex: Int = 0,
    val loading: Boolean = false, val error: ServerError? = null,
    val contentReady: Boolean = false, val refreshError: ServerError? = null,
    val detail: EmbyItem? = null, val versions: List<EmbyVersion> = emptyList(),
    val selectedVersion: String? = null, val preparing: Boolean = false,
    /** A watched/favorite toggle is in flight; it only disables that pair of actions. */
    val flagPending: Boolean = false, val flagError: ServerError? = null,
    val availability: PlaybackAvailability = PlaybackAvailability.UNKNOWN, val reportError: ServerError? = null,
    val scroll: ScrollPosition = ScrollPosition(),
    val pageKey: Any = Any(), val restoring: Boolean = false,
    val detailColor: Pair<String, Int>? = null,
    val rows: MutableMap<String, ScrollPosition> = mutableMapOf(),
    val heroScroll: ScrollPosition = ScrollPosition(),
    val shelves: List<EmbyShelf> = emptyList(), val hero: List<EmbyItem> = emptyList(),
    val children: List<EmbyItem> = emptyList(), val childTotal: Int = 0,
    val ratings: List<LibraryRating> = emptyList(), val ratingsForSeries: Boolean = false, val ratingsIdentity: String? = null,
    val nextEpisode: EmbyItem? = null, val related: List<EmbyItem> = emptyList(),
    val filters: EmbyFilters = EmbyFilters(), val listMode: Boolean = false,
    val filtersLoaded: Boolean = false,
    val cachedAt: Long? = null, val stale: Boolean = false, val filtersLoading: Boolean = false,
    val childrenLoading: Boolean = false, val relatedLoading: Boolean = false, val versionsLoading: Boolean = false,
    val childrenLoaded: Boolean = false, val relatedLoaded: Boolean = false,
    val childrenError: ServerError? = null, val relatedError: ServerError? = null,
    val pageStart: Int = 0, val pagingUnstable: Boolean = false,
    val anchor: String? = null,
    val auxiliaryError: ServerError? = null, val selectedAudio: Int? = null, val selectedSubtitle: Int? = null,
) {
    val unsupported get() = availability==PlaybackAvailability.UNAVAILABLE
    val scrollIndex get() = scroll.index
    val scrollOffset get() = scroll.offset
}

internal class EmbyBrowserController(private val scope: CoroutineScope,
    private val caches: AppCaches? = AppCaches.current,
    private val savedState: androidx.lifecycle.SavedStateHandle? = null,
    val navigation: MediaNavigation = MediaNavigation(savedState),
    private val ratingsProvider: () -> MetadataProvider? = { caches?.context?.let { LocalLibraryRuntime.get(it).metadata.provider } },
    private val verifyTlsCertificates: () -> Boolean = { true }) {
    private val mutable = MutableStateFlow(EmbyBrowserState())
    // Compatibility read model for playback/session callers. UI subscribes to page(key).
    val state = mutable.asStateFlow()
    private var client: EmbyClient? = null
    private var repository: CachedMedia? = null
    private val pages = linkedMapOf<MediaDestination.Emby, MutableStateFlow<EmbyBrowserState>>()
    private val roots = mutableStateMapOf<EmbySection, MediaDestination.Emby>()
    val retainedRoots get() = roots.values.toList()
    fun isDirectoryDestination(key: MediaDestination.Emby): Boolean =
        key !in roots.values && pages[key]?.value?.let { it.detail == null } == true
    private val current get() = navigation.current as? MediaDestination.Emby
    private var currentState: EmbyBrowserState
        get() = mutable.value
        set(value) {
            mutable.value = value
            current?.let { pages[it]?.value = value }
        }
    fun page(key: MediaDestination.Emby): StateFlow<EmbyBrowserState> =
        pages.getOrPut(key) { MutableStateFlow(EmbyBrowserState(pageKey = key.entry)) }.asStateFlow()
    private var job: Job? = null
    private var filterJob: Job? = null
    private var versionJob: Job? = null
    private var flagJob: Job? = null
    private val shelfJobs = mutableMapOf<Int,Job>()
    private var wantedShelves=emptySet<Int>()
    private var generation = 0L
    private var returningFromPlayback = false
    private var instability = 0
    val imageChanges get() = caches?.images?.cleared
    val imageRetries get() = caches?.images?.retries
    fun detailColor(pageKey: Any, artwork: String, color: Int) {
        val current = state.value
        if (current.pageKey == pageKey && current.detailColor != (artwork to color))
            currentState = current.copy(detailColor = artwork to color)
    }
    init {
        caches?.let { app -> scope.launch {
            app.images.cleared.collect { event ->
                if (event.sequence > 0 && (event.namespace == null || event.namespace == state.value.profile?.cacheNamespace())) {
                    pages.values.forEach { it.value = it.value.copy(detailColor = null) }
                    currentState = state.value.copy(detailColor = null)
                }
            }
        } }
        caches?.let { app -> scope.launch {
            app.data.cleared.collect { event ->
                val profile=state.value.profile
                if(event.sequence>0 && profile!=null && (event.namespace==null || event.namespace==profile.cacheNamespace())) {
                    pages.filterKeys { it != current }.values.forEach { it.value = lightweight(it.value) }
                    if(event.disabled) {
                        cancel()
                        currentState=state.value.copy(cachedAt=null,stale=false,loading=false,childrenLoading=false,relatedLoading=false,filtersLoading=false)
                    } else refresh()
                }
            }
        } }
    }
    private fun light(item: EmbyItem?) = item?.let { EmbyItem(it.id,it.name,it.type,it.folder,collectionType=it.collectionType) }
    private fun snapshot(s: EmbyBrowserState) = s.copy(scroll=s.scroll.snapshot(),heroScroll=s.heroScroll.snapshot(),
        rows=s.rows.mapValues { it.value.snapshot() }.toMutableMap(),
        anchor=if(s.location.section==EmbySection.LIBRARIES) null else s.items.getOrNull(s.scroll.index)?.id ?: s.anchor,
        loading=false,preparing=false,flagPending=false,childrenLoading=false,relatedLoading=false,filtersLoading=false,versionsLoading=false)
    private fun lightweight(s: EmbyBrowserState): EmbyBrowserState {
        val frozen=snapshot(s)
        return EmbyBrowserState(profile=s.profile,location=s.location.copy(parent=light(s.location.parent)),detail=light(s.detail),
            pageKey=s.pageKey,scroll=frozen.scroll,heroScroll=frozen.heroScroll,rows=frozen.rows,anchor=frozen.anchor,
            listMode=s.listMode,pageStart=s.pageStart,restoring=true,selectedVersion=s.selectedVersion,
            selectedAudio=s.selectedAudio,selectedSubtitle=s.selectedSubtitle,detailColor=s.detailColor)
    }
    private fun freeze() {
        // Snapshot synchronously before a fling's outgoing composition can write its tail frames.
        current?.let { key -> pages[key]?.let { it.value=snapshot(it.value) } }
        cancel()
    }
    private fun trim() {
        val keep=pages.keys.toList().takeLast(3).toSet()
        pages.filterKeys { it !in keep }.values.forEach { if(it.value.contentReady) it.value=lightweight(it.value) }
    }
    private fun cancel() { ++generation; job?.cancel(); job=null; filterJob?.cancel(); filterJob=null; versionJob?.cancel(); versionJob=null; flagJob?.cancel(); flagJob=null; shelfJobs.values.forEach { it.cancel() }; shelfJobs.clear(); wantedShelves=emptySet() }
    private suspend fun valid(ticket: Long) {
        currentCoroutineContext().ensureActive()
        if(ticket!=generation) throw CancellationException("Page changed")
    }
    fun open(profile: ServerProfile) {
        if(!profile.kind.mediaServer) return
        val saved=savedState?.get<android.os.Bundle>("media_navigation")?.takeIf { it.getString("namespace")==profile.cacheNamespace() }
        val stack=navigation.backStack.filterIsInstance<MediaDestination.Emby>().takeIf { keys ->
            state.value.profile==null && keys.isNotEmpty() && keys.all { it.profileId==profile.id } && saved!=null
        }.orEmpty()
        // During asynchronous process restoration the entry may already be collecting its
        // placeholder flow. Keep that flow and key alive; do not close/recreate them.
        if(stack.isEmpty()) close() else cancel()
        client=EmbyClient(profile,verifyTlsCertificates())
        repository=CachedMedia(client!!,caches?.data) { caches?.context?.resources?.configuration?.locales?.get(0)?.toLanguageTag() ?: java.util.Locale.getDefault().toLanguageTag() }
        mutable.value=EmbyBrowserState(profile=profile)
        if(stack.isNotEmpty()) {
            val decoded=mutableSetOf<MediaDestination.Emby>()
            saved?.getParcelableArrayList<android.os.Bundle>("pages")?.forEach { bundle ->
                val entry=bundle.getString("entry") ?: return@forEach
                decodeCheckpoint(bundle,profile,entry)?.let { value ->
                    val key=MediaDestination.Emby(profile.id,entry)
                    pages.getOrPut(key) { MutableStateFlow(value) }.value=value
                    decoded+=key
                }
            }
            if(stack.all { it in decoded }) {
                EmbySection.entries.forEach { section -> saved?.getBundle("roots")?.getString(section.name)?.let { id ->
                    MediaDestination.Emby(profile.id,id).takeIf { it in pages }?.let { roots[section]=it }
                } }
                activate(stack.last()); return
            }
            pages.clear(); roots.clear(); navigation.removeFrom { it is MediaDestination.Emby }
        }
        val key=newKey(profile)
        navigation.push(key); roots[EmbySection.LIBRARIES]=key
        pages[key]=MutableStateFlow(EmbyBrowserState(profile=profile,pageKey=key.entry))
        currentState=pages.getValue(key).value
        saveNavigation(); load(verify=true)
    }
    private fun newKey(profile: ServerProfile) = MediaDestination.Emby(profile.id,java.util.UUID.randomUUID().toString())
    fun close() {
        cancel(); client?.close(); client=null; repository=null
        navigation.removeFrom { it is MediaDestination.Emby }
        roots.clear(); pages.clear(); mutable.value=EmbyBrowserState(); returningFromPlayback=false
        savedState?.remove<android.os.Bundle>("media_navigation")
    }
    fun saveNavigation() {
        val profile=state.value.profile ?: return
        // This map is presentation checkpoints only. The sole ordered history is MediaNavigation.
        savedState?.set("media_navigation",android.os.Bundle().apply {
            putString("profile",profile.id); putString("namespace",profile.cacheNamespace())
            putParcelableArrayList("pages",ArrayList(pages.map { (key,flow) -> encodeCheckpoint(flow.value).apply { putString("entry",key.entry) } }))
            putBundle("roots",android.os.Bundle().apply { roots.forEach { (section,key) -> putString(section.name,key.entry) } })
        })
    }
    private fun encodeCheckpoint(s: EmbyBrowserState)=android.os.Bundle().apply {
        val c=snapshot(s); val l=c.location
        putStringArray("query",arrayOf(l.section.name,l.parent?.id.orEmpty(),l.parent?.name.orEmpty(),l.parent?.type.orEmpty(),l.parent?.collectionType.orEmpty(),
            l.query,l.title,l.sort,l.genre,l.tag,l.studio,l.person,l.year,l.played?.toString().orEmpty(),l.itemTypes,c.detail?.id.orEmpty(),c.detail?.name.orEmpty(),c.detail?.type.orEmpty(),c.anchor.orEmpty()))
        putBoolean("folder",l.parent?.folder ?: false); putBoolean("detailFolder",c.detail?.folder ?: false)
        putBoolean("descending",l.descending); putBoolean("favorite",l.favoriteOnly); putBoolean("recursive",l.recursive); putBoolean("list",c.listMode)
        putInt("start",c.pageStart); putInt("index",c.scroll.index); putInt("offset",c.scroll.offset); putInt("heroIndex",c.heroScroll.index); putInt("heroOffset",c.heroScroll.offset)
        putBundle("rows",android.os.Bundle().apply { c.rows.forEach { (key,p) -> putIntArray(key,intArrayOf(p.index,p.offset)) } })
        putString("version",c.selectedVersion); c.selectedAudio?.let { putInt("audio",it) }; c.selectedSubtitle?.let { putInt("subtitle",it) }
    }
    private fun decodeCheckpoint(b: android.os.Bundle,profile: ServerProfile,entry: String): EmbyBrowserState? = runCatching {
        val q=b.getStringArray("query") ?: return null
        if(q.size!=19) return null
        val location=EmbyLocation(EmbySection.valueOf(q[0]),q[1].takeIf { it.isNotEmpty() }?.let { EmbyItem(it,q[2],q[3],b.getBoolean("folder"),collectionType=q[4]) },
            q[5],q[6],q[7],b.getBoolean("descending"),q[8],q[9],q[10],q[11],q[12],q[13].toBooleanStrictOrNull(),b.getBoolean("favorite"),q[14],b.getBoolean("recursive"))
        EmbyBrowserState(profile=profile,location=location,detail=q[15].takeIf { it.isNotEmpty() }?.let { EmbyItem(it,q[16],q[17],b.getBoolean("detailFolder")) },
            scroll=ScrollPosition().also { it.save(b.getInt("index"),b.getInt("offset")) },heroScroll=ScrollPosition().also { it.save(b.getInt("heroIndex"),b.getInt("heroOffset")) },
            listMode=b.getBoolean("list"),pageStart=b.getInt("start"),anchor=q[18].takeIf { it.isNotEmpty() },pageKey=entry,restoring=true,
            rows=b.getBundle("rows")?.let { rows -> rows.keySet().associateWith { key ->
                ScrollPosition().also { p -> rows.getIntArray(key)?.takeIf { it.size==2 }?.let { p.save(it[0],it[1]) } }
            } }.orEmpty().toMutableMap(),selectedVersion=b.getString("version"),selectedAudio=if(b.containsKey("audio")) b.getInt("audio") else null,
            selectedSubtitle=if(b.containsKey("subtitle")) b.getInt("subtitle") else null)
    }.getOrNull()
    private fun activate(key: MediaDestination.Emby) {
        cancel()
        val flow=pages.remove(key) ?: return
        pages[key]=flow
        currentState=flow.value
        trim()
        if(state.value.detail!=null) loadDetail() else {
            val s=state.value
            val offset=if(s.location.section==EmbySection.LIBRARIES) 0 else s.pageStart+s.scroll.index/60*60
            if(!s.contentReady && s.location.section!=EmbySection.LIBRARIES) s.scroll.save(s.scroll.index%60,s.scroll.offset)
            load(start=offset,anchor=s.anchor)
        }
        saveNavigation()
    }
    fun back() {
        val key=current ?: return
        freeze()
        navigation.pop(); pages.remove(key)
        val parent=current
        if(parent==null) close() else activate(parent)
    }
    fun switchRoot(section: EmbySection) {
        val before=state.value
        if(current !in roots.values || section !in setOf(EmbySection.LIBRARIES,EmbySection.FAVORITES,EmbySection.SEARCH) || before.profile==null) return
        if(roots[section]==current) return
        freeze()
        val key=roots.getOrPut(section) { newKey(before.profile).also {
            pages[it]=MutableStateFlow(EmbyBrowserState(profile=before.profile,location=EmbyLocation(section),pageKey=it.entry))
        } }
        navigation.replace(key)
        activate(key)
    }
    private fun push(location: EmbyLocation,detail: EmbyItem?=null) {
        val before=state.value; val profile=before.profile ?: return
        if(navigation.backStack.count { it is MediaDestination.Emby }>=32) {
            currentState=before.copy(auxiliaryError=ServerError.LIMIT); return
        }
        freeze()
        val key=newKey(profile)
        navigation.push(key)
        pages[key]=MutableStateFlow(EmbyBrowserState(profile=profile,location=location,detail=detail,pageKey=key.entry))
        currentState=pages.getValue(key).value
        trim(); saveNavigation()
        if(detail!=null) loadDetail() else load()
    }
    fun navigate(location: EmbyLocation) = push(location)
    fun replace(location: EmbyLocation) {
        val key=current ?: return
        cancel(); instability=0
        currentState=EmbyBrowserState(profile=state.value.profile,location=location,reportError=state.value.reportError,pageKey=key.entry)
        saveNavigation(); load()
    }
    fun listMode() { currentState=state.value.copy(listMode=!state.value.listMode,scroll=state.value.scroll.snapshot()) }
    fun select(item: EmbyItem) {
        if(item.type in setOf("Genre","Tag")) {
            navigate(EmbyLocation(EmbySection.RELATED,parent=state.value.location.parent,title=item.name,
                genre=if(item.type=="Genre") item.name else "",tag=if(item.type=="Tag") item.name else "")); return
        }
        if(!item.details) navigate(EmbyLocation(EmbySection.FOLDER,item)) else push(state.value.location,item)
    }
    fun refresh() {
        val s=state.value
        s.profile?.let { caches?.images?.retry(it.cacheNamespace()) }; instability=0
        if(s.detail!=null) loadDetail(true)
        else load(force=true,start=if(s.location.section==EmbySection.LIBRARIES) 0 else s.pageStart+s.scroll.index/60*60)
    }
    fun screenVisible() {
        if(returningFromPlayback) {
            returningFromPlayback=false
            scope.launch {
                val profile = state.value.profile ?: return@launch
                val runtime = cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.current()
                if (runtime != null) {
                    runtime.awaitIdle()
                    val before = state.value
                    val detail = before.detail?.let { runtime.overlay(profile, listOf(it)).first() }
                    currentState = before.copy(detail = detail, items = runtime.overlay(profile, before.items),
                        hero = runtime.overlay(profile, before.hero), shelves = before.shelves.map { it.copy(items = runtime.overlay(profile, it.items)) },
                        children = runtime.overlay(profile, before.children), related = runtime.overlay(profile, before.related))
                }
            }
        }
    }
    fun nextPage() { if(!state.value.loading && !state.value.pagingUnstable && state.value.nextIndex<state.value.total) load(append=true) }
    fun previousPage() { if(!state.value.loading && state.value.pageStart>0) load(start=(state.value.pageStart-60).coerceAtLeast(0)) }
    fun selectVersion(id: String) { if(!state.value.preparing) currentState=state.value.copy(selectedVersion=id,selectedAudio=null,selectedSubtitle=null) }
    fun selectTrack(audio: Boolean,index: Int) { currentState=if(audio) state.value.copy(selectedAudio=index) else state.value.copy(selectedSubtitle=index) }
    private fun request(action: suspend (EmbyClient,Long)->Unit) {
        val api=client ?: return
        cancel(); val ticket=generation
        job=scope.launch {
            try { action(api,ticket) }
            catch(e: CancellationException) {
                if (ticket == generation) currentState = state.value.copy(preparing = false)
                throw e
            }
            catch(e: Exception) { if(ticket==generation) failure(e) }
        }
    }
    private enum class Part { MAIN, CHILDREN, RELATED, FILTERS, VERSIONS }
    private fun failure(e: Exception, part: Part=Part.MAIN) {
        val error=(e as? EmbyFailure)?.reason ?: ServerError.NETWORK
        if(error in setOf(ServerError.AUTHENTICATION,ServerError.PERMISSION)) {
            cancel()
            pages.values.forEach { it.value=lightweight(it.value) }
            currentState=lightweight(state.value).copy(error=ServerError.AUTHENTICATION,restoring=false)
        } else currentState=when(part) {
            Part.CHILDREN -> state.value.copy(auxiliaryError=error,childrenError=error,childrenLoading=false)
            Part.RELATED -> state.value.copy(auxiliaryError=error,relatedError=error,relatedLoading=false)
            Part.FILTERS -> state.value.copy(auxiliaryError=error,filtersLoading=false)
            Part.VERSIONS -> state.value.copy(auxiliaryError=error,versionsLoading=false,availability=PlaybackAvailability.UNKNOWN)
            Part.MAIN -> state.value.copy(loading=false,preparing=false,
                error=error.takeUnless { state.value.contentReady && error!=ServerError.PATH },
                refreshError=error.takeIf { state.value.contentReady && error!=ServerError.PATH },
                restoring=state.value.restoring && state.value.contentReady)
        }
    }
    private fun marker(value: CacheDelivery) { currentState=state.value.copy(cachedAt=if(value.cached) value.updatedAt else null,stale=value.stale) }
    private fun load(append: Boolean=false,verify: Boolean=false,force: Boolean=false,start: Int=0,anchor: String?=null) {
        val before=state.value
        val repo=repository ?: return
        val visibleHomeShelves = wantedShelves
        currentState=before.copy(loading=true,error=null,refreshError=null,auxiliaryError=null,pagingUnstable=false)
        request { api,ticket ->
            // request() cancels old work and clears its demand. Retain the current home
            // viewport: an unchanged visible-items snapshot will not emit again on refresh.
            if (before.location.section == EmbySection.LIBRARIES) wantedShelves = visibleHomeShelves
            // Verification belongs to this page generation, but must never gate a cache read.
            if(verify) scope.launch(currentCoroutineContext()) {
                try { api.verifyServer(); valid(ticket) }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { if(ticket==generation) {
                    if((e as? EmbyFailure)?.reason in setOf(ServerError.AUTHENTICATION,ServerError.PERMISSION)) failure(e)
                    else currentState=state.value.copy(refreshError=(e as? EmbyFailure)?.reason ?: ServerError.NETWORK)
                } }
            }
            valid(ticket)
            if(before.location.section==EmbySection.SEARCH && before.location.query.isBlank()) {
                currentState=state.value.copy(loading=false,restoring=false,items=emptyList(),total=0,nextIndex=0); return@request
            }
            if(force) repo.refresh(before.location)
            val offset=if(append) before.nextIndex else start
            var unstable=false
            var delivered=false
            var homeInitialized=false
            try { repo.page(before.location,offset,force) { delivery ->
                valid(ticket)
                delivered=true
                val page=delivery.value.page()
                if(append && (page.total!=before.total || page.items.any { item -> before.items.any { it.id==item.id } } || page.nextIndex<=offset)) {
                    unstable=true; return@page
                }
                val relative=offset-before.pageStart
                // Revalidating a page inside a retained multi-page window must not shorten
                // the grid to 60 items or move its anchor. Actual membership changes still
                // replace the window using the live item anchor below.
                val preserveWindow=!append && before.contentReady && before.location.section!=EmbySection.LIBRARIES &&
                    page.total==before.total && relative>=0 && relative+page.items.size<=before.items.size &&
                    before.items.subList(relative,relative+page.items.size).map { it.id }==page.items.map { it.id }
                val combined=if(preserveWindow) before.items.toMutableList().apply {
                    page.items.forEachIndexed { index,item -> this[relative+index]=item }
                } else ((if(append) before.items else emptyList())+page.items).distinctBy { it.id }
                val dropped=(combined.size-300).coerceAtLeast(0)
                val items=combined.drop(dropped).let { if(it==before.items) before.items else it }
                // Live Lazy states anchor by stable item keys. Never replace them with
                // the position captured before a cache/network delivery or clamp home
                // section indices to the number of libraries.
                if(state.value.restoring && before.location.section!=EmbySection.LIBRARIES) {
                    val target=anchor?.let { id -> items.indexOfFirst { it.id==id }.takeIf { it>=0 } }
                    state.value.scroll.save(target ?: before.scroll.index.coerceIn(0,(items.size-1).coerceAtLeast(0)),before.scroll.offset)
                }
                val scroll=if(!append && !preserveWindow && before.contentReady && before.location.section!=EmbySection.LIBRARIES) {
                    val live=state.value.scroll.current
                    val id=state.value.items.getOrNull(live.first)?.id
                    ScrollPosition().also { it.save(items.indexOfFirst { item -> item.id==id }.takeIf { it>=0 }
                        ?: live.first.coerceAtMost((items.size-1).coerceAtLeast(0)),live.second) }
                } else state.value.scroll
                currentState=state.value.copy(loading=false,restoring=false,contentReady=true,items=items,total=page.total,
                    nextIndex=if(preserveWindow) before.nextIndex else page.nextIndex,scroll=scroll,
                    pageStart=(if(append || preserveWindow) before.pageStart else offset)+dropped)
                marker(delivery)
                if(!append && before.location.section==EmbySection.LIBRARIES) {
                    updateHomeShelves(force && !homeInitialized)
                    homeInitialized=true
                }
            } } catch(e: Exception) {
                if(!delivered && !append && ticket==generation && before.items.isEmpty()) currentState=state.value.copy(items=emptyList(),total=0,nextIndex=0,cachedAt=null,stale=false)
                throw e
            }
            valid(ticket)
            if(unstable) {
                instability++
                if(instability>=2) currentState=state.value.copy(loading=false,pagingUnstable=true)
                else load(force=true)
                return@request
            }
        }
    }
    private fun updateHomeShelves(force: Boolean) {
        val old=state.value.shelves.associateBy { it.key }
        val slots=(listOf(EmbyShelf(label="continue",items=emptyList(),location=EmbyLocation(EmbySection.RESUME),wide=true,loaded=false))+
            state.value.items.map { EmbyShelf(title=it.name,items=emptyList(),location=EmbyLocation(EmbySection.LATEST,it),loaded=false) })
            .map { slot -> old[slot.key]?.let { previous -> previous.copy(title=slot.title,location=slot.location,
                loaded=if(force) false else previous.loaded,error=if(force) false else previous.error) } ?: slot }
        if(slots.map { it.key }!=state.value.shelves.map { it.key }) {
            shelfJobs.values.forEach { it.cancel() }; shelfJobs.clear()
        }
        currentState=state.value.copy(shelves=slots)
        val top=state.value.scroll.index
        wantedShelves=(wantedShelves+0+embyHeroShelves(state.value.items)+listOfNotNull(embyHomeShelfAt(top),embyHomeShelfAt(top+1)))
            .filter { it in slots.indices }.toSet()
        wantedShelves.forEach { shelfVisible(it,force) }
    }
    fun visibleShelves(visible: Set<Int>) {
        if(state.value.location.section!=EmbySection.LIBRARIES) return
        val wanted=(visible.take(4)+listOfNotNull(visible.maxOrNull()?.plus(1))).filter { it in state.value.shelves.indices }.toSet()+
            if(state.value.hero.isEmpty()) embyHeroShelves(state.value.items).toSet() else emptySet()
        if(wanted==wantedShelves) return
        wantedShelves=wanted
        shelfJobs.filterKeys { it !in wanted }.values.forEach { it.cancel() }
        shelfJobs.keys.removeAll { it !in wanted }
        // Keep up to 12 visited shelves (20 summaries each). Evicted shelves keep
        // their card-sized placeholder and row checkpoint, rather than collapsing.
        currentState=state.value.copy(shelves=trimShelves(state.value.shelves))
        wanted.forEach { shelfVisible(it) }
    }
    private fun trimShelves(slots: List<EmbyShelf>): List<EmbyShelf> {
        val loaded=slots.indices.filter { slots[it].items.isNotEmpty() }
        if(loaded.size<=12) return slots
        val retained=loaded.sortedBy { index -> wantedShelves.minOfOrNull { kotlin.math.abs(it-index) } ?: index }.take(12).toSet()
        return slots.mapIndexed { i,shelf -> if(i !in retained && shelf.items.isNotEmpty()) shelf.copy(items=emptyList(),loaded=false) else shelf }
    }
    fun shelfVisible(index: Int,force: Boolean=false) {
        val repo=repository ?: return
        val shelf=state.value.shelves.getOrNull(index) ?: return
        if((shelf.loaded && !force) || shelfJobs[index]?.isActive==true) return
        val ticket=generation
        shelfJobs[index]=scope.launch {
            try {
                repo.page(shelf.location ?: return@launch,force=force) { delivery ->
                    valid(ticket)
                    val slots=state.value.shelves.mapIndexed { i,old -> if(i==index) {
                        val fresh = delivery.value.items.take(20)
                        val items = if (fresh == old.items) old.items else fresh
                        old.copy(items=items,loaded=true,error=false).let { if (it == old) old else it }
                    } else old }
                    // Only the hero's source shelves feed it. Preserve equal list instances
                    // across cache/network validation so strong skipping can reuse its subtree.
                    val sources=embyHeroShelves(state.value.items).mapNotNull { slots.getOrNull(it) }
                    val hero=if(index in embyHeroShelves(state.value.items) && sources.all { it.loaded }) embyHeroItems(sources)
                        .let { if (it == state.value.hero) state.value.hero else it } else state.value.hero
                    currentState=state.value.copy(shelves=trimShelves(slots),hero=hero)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket==generation) {
                if((e as? EmbyFailure)?.reason in setOf(ServerError.AUTHENTICATION,ServerError.PERMISSION)) failure(e)
                else currentState=state.value.copy(auxiliaryError=(e as? EmbyFailure)?.reason ?: ServerError.NETWORK,
                    shelves=state.value.shelves.mapIndexed { i,old -> if(i==index) old.copy(loaded=true,error=true) else old })
            } }
        }
    }
    fun loadFilters() {
        val repo=repository ?: return
        if(filterJob?.isActive==true) return
        val ticket=generation; val parent=state.value.location.parent
        currentState=state.value.copy(filtersLoading=true)
        filterJob=scope.launch {
            try { repo.filters(parent) { value -> valid(ticket); currentState=state.value.copy(filters=value.value.filters ?: EmbyFilters(),filtersLoading=false,filtersLoaded=true) } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket==generation) failure(e,Part.FILTERS) }
        }
    }
    private fun next(items: List<EmbyItem>)=items.firstOrNull { it.userStateKnown && it.resumeMs>0 } ?: items.firstOrNull { it.userStateKnown && !it.played } ?: items.firstOrNull()
    private fun loadDetail(force: Boolean=false) {
        val selected=state.value.detail ?: return
        val repo=repository ?: return
        currentState=state.value.copy(loading=!state.value.contentReady,error=null,refreshError=null,auxiliaryError=null,
            childrenError=null,relatedError=null,
            childrenLoading=!state.value.childrenLoaded && selected.type in setOf("Series","Season","Person","BoxSet","MusicAlbum"),
            relatedLoading=!state.value.relatedLoaded && selected.type in setOf("Series","Episode","Movie"))
        request { _,ticket ->
            coroutineScope {
                var sections: Job?=null
                var ratings: Job?=null
                var ratingsIdentity: String?=null
                var primaryChecked=false
                var sectionsChecked=false
                suspend fun attach() {
                    valid(ticket)
                    if(primaryChecked && sectionsChecked) currentState=state.value.copy(restoring=false)
                }
                try { repo.detail(selected.id,force,cacheChecked={ primaryChecked=true; attach() }) { delivery ->
                    valid(ticket)
                    val item=delivery.value.items.first()
                    val keepVersions=state.value.contentReady && state.value.detail?.sources==item.sources
                    currentState=state.value.copy(detail=item,loading=false,contentReady=true,
                        availability=if(keepVersions) state.value.availability else PlaybackAvailability.UNKNOWN,
                        versions=if(keepVersions) state.value.versions else item.sources.map { EmbyVersion(it.id,it.name,item.durationMs,true) },
                        selectedVersion=state.value.selectedVersion ?: item.sources.firstOrNull()?.id)
                    marker(delivery)
                    val identity = "${item.type}:${item.id}:${item.imdbId}:${item.seriesId}"
                    if (ratingsIdentity != identity) {
                        ratings?.cancel(); ratingsIdentity = identity
                        if (state.value.ratingsIdentity != identity) currentState = state.value.copy(
                            ratings = emptyList(), ratingsForSeries = false, ratingsIdentity = identity)
                        ratings = launch { loadDetailRatings(item, repo, ticket) }
                    }

                    // Auxiliary reads can start with cached primary data, without waiting
                    // for its network validation. Start once per page request.
                    if(sections==null) sections=launch { loadDetailSections(item,repo,ticket,force) { sectionsChecked=true; attach() } }
                } } catch(e: CancellationException) { throw e }
                catch(e: Exception) { if(ticket==generation) {
                    failure(e)
                    if(sections==null) currentState=state.value.copy(childrenLoading=false,relatedLoading=false,restoring=false)
                } }
                sections?.join()
                valid(ticket)
                currentState=state.value.copy(restoring=false)
            }
        }
    }
    private suspend fun loadDetailRatings(item: EmbyItem, repo: CachedMedia, ticket: Long) {
        try {
            val series = item.type in setOf("Season", "Episode")
            var owner = item
            if (series) {
                if (item.seriesId.isBlank()) return
                repo.detail(item.seriesId) { delivery ->
                    valid(ticket)
                    owner = delivery.value.items.firstOrNull() ?: owner
                }
                if (owner.type != "Series" || owner.id != item.seriesId) return
            }
            val target = if (item.type == "Episode") RatingsTarget.episode(owner.imdbId, item.seasonIndex, item.index)
                else RatingsTarget.imdb(owner.type.lowercase(), owner.imdbId)
            target ?: return
            val provider = ratingsProvider() ?: return
            provider.loadRatings(target) { ratings ->
                valid(ticket)
                currentState = state.value.copy(ratings = ratings, ratingsForSeries = item.type == "Season" && ratings.isNotEmpty())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional ratings never fail the detail or its other sections. */ }
    }

    private suspend fun loadDetailSections(detail: EmbyItem,repo: CachedMedia,ticket: Long,force: Boolean,
        cacheChecked: suspend ()->Unit) = supervisorScope {
        var remaining=if(detail.type=="Series") 2 else 1
        suspend fun checked() { if(--remaining==0) cacheChecked() }
        suspend fun child(related: Boolean,action: suspend (suspend (CacheDelivery)->Unit)->Unit) {
            try { action { value ->
                valid(ticket)
                currentState=if(related) state.value.copy(related=value.value.items,relatedLoading=false,relatedLoaded=true,relatedError=null,
                    nextEpisode=if(detail.type=="Series") next(value.value.items) else state.value.nextEpisode)
                else state.value.copy(children=value.value.items,childTotal=value.value.total,childrenLoading=false,childrenLoaded=true,childrenError=null,
                    nextEpisode=if(detail.type=="Season") next(value.value.items) else state.value.nextEpisode)
            } } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket==generation) failure(e,if(related) Part.RELATED else Part.CHILDREN) }
        }
        when(detail.type) {
            "Series" -> { launch { child(false) { repo.seasons(detail,force,::checked,it) } }; launch { child(true) { repo.episodes(detail.id,force=force,cacheChecked=::checked,publish=it) } } }
            "Season" -> child(false) { repo.episodes(detail.seriesId,detail.id,force,::checked,it) }
            "Episode" -> child(true) { repo.episodes(detail.seriesId,detail.seasonId,force,::checked,it) }
            "Person" -> child(false) { repo.page(EmbyLocation(EmbySection.RELATED,person=detail.id,itemTypes="Movie,Series,Episode"),force=force,cacheChecked=::checked,publish=it) }
            "BoxSet","MusicAlbum" -> child(false) { repo.page(EmbyLocation(EmbySection.FOLDER,detail),force=force,cacheChecked=::checked,publish=it) }
            "Movie" -> child(true) { repo.similar(detail,force,::checked,it) }
            else -> cacheChecked()
        }
    }
    fun loadVersions() {
        val selected=state.value.detail ?: return
        if(!selected.playable || state.value.versionsLoading) return
        currentState=state.value.copy(versionsLoading=true,availability=PlaybackAvailability.CHECKING)
        val api=client ?: return; val ticket=generation
        versionJob=scope.launch {
            try {
                val info=withContext(Dispatchers.Default) { api.playbackInfo(selected) }; valid(ticket)
                currentState=state.value.copy(versions=info.versions,versionsLoading=false,availability=if(info.versions.any { it.streamable }) PlaybackAvailability.AVAILABLE else PlaybackAvailability.UNAVAILABLE,
                    selectedVersion=state.value.selectedVersion?.takeIf { id -> info.versions.any { it.id==id && it.streamable } } ?: info.versions.firstOrNull { it.streamable }?.id)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket==generation) failure(e,Part.VERSIONS) }
        }
    }
    fun flag(favorite: Boolean, played: Boolean? = null) {
        val selected=state.value.detail ?: return
        val repo=repository ?: return
        val api=client ?: return
        if(state.value.preparing || state.value.loading || state.value.flagPending) return
        // Its own job: a toggle must not cancel the page's detail/auxiliary requests, block
        // playback controls or show the page progress bar. Leaving the page still cancels it.
        val ticket=generation
        currentState=state.value.copy(flagPending=true,flagError=null)
        flagJob=scope.launch {
            var revision=0L; var original: EmbyItem?=null
            var committed: EmbyItem?=null
            try {
                val item=if(selected.userStateKnown) selected else repo.detail(selected.id,true)
                valid(ticket); original=item
                revision=caches?.data?.beginMutation(repo.namespace,item,favorite) ?: 0
                val intended=if(favorite) item.copy(favorite=!item.favorite) else {
                    val watched = played ?: !item.played
                    item.copy(played = watched, resumeMs = if (watched) item.resumeMs else 0)
                }
                currentState=state.value.copy(detail=intended)
                val reporting = cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.current()
                val value = if (favorite) intended.favorite else intended.played
                if (reporting == null) api.setUserFlag(item, favorite, value)
                else reporting.userCommand(api.profile, item, favorite, value,
                    onLocalCommit = { committed = intended.copy(progressPending = true) }) { api.setUserFlag(item, favorite, value) }
                committed=intended
                caches?.data?.userChanged(repo.namespace)
                valid(ticket)
                // A new request can confirm the mutation. Earlier requests carry lower revisions.
                // Republish only real differences; an identical confirmation would recompose
                // the whole detail body for nothing.
                repo.detail(item.id,true) { result -> valid(ticket)
                    val confirmed=result.value.items.first()
                    if(!result.cached && confirmed!=state.value.detail) currentState=state.value.copy(detail=confirmed) }
                currentState=state.value.copy(flagPending=false)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(ticket!=generation) return@launch
                if(committed != null) {
                    // The write is committed. A failed confirmation must not undo it.
                    currentState=state.value.copy(detail=committed,flagPending=false)
                    failure(e) // Preserve the existing authentication revocation path.
                    return@launch
                }
                original?.let { caches?.data?.rollback(repo.namespace,it.id,revision); currentState=state.value.copy(detail=it) }
                val error=(e as? EmbyFailure)?.reason ?: ServerError.NETWORK
                if(error in setOf(ServerError.AUTHENTICATION,ServerError.PERMISSION)) { currentState=state.value.copy(flagPending=false); failure(e) }
                else currentState=state.value.copy(flagPending=false,flagError=error)
            }
        }
    }
    fun play(resume: Boolean, onPlay: (EmbyPlayback) -> Unit, positionMs: Long? = null) {
        if (state.value.loading || state.value.preparing || state.value.flagPending) return
        val selected = state.value.detail?.takeIf { it.playable } ?: state.value.nextEpisode ?: return
        val versionId = state.value.selectedVersion
        val profile = state.value.profile ?: return
        currentState = currentState.copy(preparing = true, error = null)
        request { api, ticket ->
            // A fresh session is negotiated at the explicit play action, never reuse a detail request.
            val detail = api.detail(selected.id)
            currentState = currentState.copy(detail = if (state.value.detail?.id == detail.id) detail else state.value.detail)
            val initial = if (versionId == null) api.playbackInfo(detail) else null
            val chosen = versionId ?: initial?.versions?.firstOrNull { it.streamable }?.id ?: throw EmbyFailure(ServerError.PROTOCOL)
            val info = api.playbackInfo(detail, chosen)
            valid(ticket)
            val version = info.versions.firstOrNull { it.id == chosen && it.streamable }
                ?: throw EmbyFailure(ServerError.PROTOCOL)
            val start = (positionMs ?: if (resume) cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.current()
                ?.chooseResume(profile, detail, chosen) ?: detail.resumeMs else 0).coerceAtMost((version.durationMs ?: Long.MAX_VALUE) - 1).coerceAtLeast(0)
            val playback = EmbyPlayback(profile, detail, version, info.sessionId, start,
                verifyTlsCertificates = api.verifyTlsCertificates) { error ->
                scope.launch { if (state.value.profile?.id == profile.id) currentState = currentState.copy(reportError = error) }
            }
            val tracks = detail.sources.firstOrNull { it.id == chosen }?.tracks.orEmpty()
            fun trackId(type: String, selectedIndex: Int?): String? {
                val filtered = tracks.filter { it.type == type && !it.external }
                val ordinal = filtered.indexOfFirst { it.index == selectedIndex }
                return if (ordinal < 0) null else "${type.uppercase()}:$ordinal:${filtered[ordinal].index}"
            }
            playback.initialAudioId = trackId("Audio", state.value.selectedAudio)
            playback.initialSubtitleId = trackId("Subtitle", state.value.selectedSubtitle)
            playback.episodeSiblings = state.value.related + state.value.children
            try { onPlay(playback); returningFromPlayback = true } catch (e: Exception) { playback.close(); throw e }
            currentState = currentState.copy(preparing = false)
        }
    }
    val imageLoader get() = caches?.images?.loader
    suspend fun imageColor(request: ImageRequest,bitmap: Bitmap): Int = checkNotNull(caches).images.color(request,bitmap,wholeImage = true)
    fun imageRequest(item: EmbyItem,width: Int,height: Int,type: String="Primary",index: Int=0,tag: String?=null,
        fit: Boolean=false,software: Boolean=false,namespace: String?=null): ImageRequest? {
        val api=client ?: return null
        // An outgoing entry must never resolve old artwork through a newly opened account.
        if(namespace!=null && namespace!=repository?.namespace) return null
        return caches?.images?.request(api.profile,api.verifyTlsCertificates,item,width,height,type,index,tag,fit,software)
    }
    suspend fun image(item: EmbyItem,type: String="Primary",index: Int=0,explicitTag: String?=null): Bitmap? {
        val request=imageRequest(item,480,720,type,index,explicitTag,software=true) ?: return null
        return (imageLoader?.execute(request) as? SuccessResult)?.image?.toBitmap()
    }
}

/** Home list items are the hero, Continue Watching (shelf 0), the library row, then the
 * remaining shelves. Returns the shelf shown by list item [index], if any. */
internal fun embyHomeShelfAt(index: Int): Int? = when { index == 1 -> 0; index >= 3 -> index - 2; else -> null }
internal fun embyHomeItemOf(shelf: Int): Int = if (shelf == 0) 1 else shelf + 2

/** Shelves feeding the hero: the latest of the first movie and the first TV library, so
 * both kinds appear. Other video libraries fill in when one kind is missing. */
internal fun embyHeroShelves(libraries: List<EmbyItem>): List<Int> {
    val video = libraries.indices.filter { libraries[it].collectionType !in setOf("music", "books", "photos", "playlists", "livetv") }
    val preferred = listOfNotNull(video.firstOrNull { libraries[it].collectionType == "movies" },
        video.firstOrNull { libraries[it].collectionType == "tvshows" })
    return (preferred + video).distinct().take(2).map { it + 1 }
}

/** Alternates the source shelves' newest movies and series, newest first within each. */
internal fun embyHeroItems(sources: List<EmbyShelf>, limit: Int = 8): List<EmbyItem> {
    val lists = sources.map { shelf -> shelf.items.filter { it.type in setOf("Movie", "Series") && (it.imageTag.isNotEmpty() || it.backdropTags.isNotEmpty()) } }
    return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        .distinctBy { it.id }.take(limit)
}
