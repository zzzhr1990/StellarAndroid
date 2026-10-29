package cn.coldlake.stellar.opensource.cache

import cn.coldlake.stellar.opensource.medialibrary.emby.*
import kotlinx.coroutines.*

internal data class DataPolicy(val fresh: Long, val maximum: Long, val disk: Boolean = true) {
    companion object {
        val LIST = DataPolicy(300_000,86_400_000)
        val DETAIL = DataPolicy(21_600_000,604_800_000)
        val FILTER = DataPolicy(3_600_000,86_400_000)
        val USER = DataPolicy(30_000,120_000)
        val SEARCH = DataPolicy(60_000,300_000,false)
    }
}
internal data class CacheDelivery(val value: MediaPayload, val cached: Boolean, val stale: Boolean, val updatedAt: Long)
internal class CachedMedia(private val api: EmbyClient, private val cache: MediaDataCache?, private val language: ()->String) {
    val namespace=api.profile.cacheNamespace()
    fun group(location: EmbyLocation) = cacheKey(namespace,"page",language(),EmbyClient.BROWSE_FIELDS,
        location.section,location.parent?.id,location.parent?.type,location.parent?.collectionType,location.query,
        location.sort,location.descending,location.genre,location.tag,location.studio,location.person,location.year,
        location.played,location.favoriteOnly,location.itemTypes,location.recursive,60)
    private suspend fun overlay(value: MediaPayload, fresh: Boolean = false): MediaPayload {
        val runtime = cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.current() ?: return value
        return try { value.copy(items = runtime.overlay(api.profile, value.items, freshServer = fresh)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { value }
    }
    private suspend fun load(key: String,group: String,kind: String,policy: DataPolicy,force: Boolean,
        publish: suspend (CacheDelivery)->Unit,cacheChecked: suspend ()->Unit = {},fetch: suspend ()->MediaPayload): MediaPayload {
        var delivered: MediaPayload? = null
        val delivery: suspend (CacheDelivery) -> Unit = {
            val merged = overlay(it.value, !it.cached); delivered = merged
            publish(it.copy(value = merged))
        }
        val value = if (cache == null) {
            cacheChecked()
            withContext(Dispatchers.Default) { fetch() }.also { delivery(CacheDelivery(it,false,false,System.currentTimeMillis())) }
        } else cache.load(namespace,key,group,kind,policy,force,delivery,cacheChecked,fetch)
        return delivered ?: overlay(value)
    }
    suspend fun page(location: EmbyLocation,start: Int=0,force: Boolean=false,cacheChecked: suspend ()->Unit = {},publish: suspend (CacheDelivery)->Unit = {}): EmbyPage {
        val group=group(location)
        val userDependent=location.section in setOf(EmbySection.RESUME,EmbySection.FAVORITES) ||
            location.favoriteOnly || location.played!=null || location.sort=="DatePlayed"
        val policy=when {
            location.section==EmbySection.SEARCH -> DataPolicy.SEARCH
            userDependent -> DataPolicy.USER
            location.section in setOf(EmbySection.GENRES,EmbySection.TAGS) -> DataPolicy.FILTER
            else -> DataPolicy.LIST
        }
        return load(cacheKey(group,start),group,if(userDependent) "user" else "page",policy,force,publish,cacheChecked) {
            if(location.section==EmbySection.TAGS) {
                val names=filters(location.parent).tags
                MediaPayload(names.map { EmbyItem("tag:$it",it,"Tag",true) },names.size,names.size)
            } else MediaPayload.from(api.page(location,start))
        }.page()
    }
    suspend fun detail(id: String,force: Boolean=false,cacheChecked: suspend ()->Unit = {},publish: suspend (CacheDelivery)->Unit = {}): EmbyItem =
        load(cacheKey(namespace,"detail",id,language()),id,"detail",DataPolicy.DETAIL,force,publish,cacheChecked) { MediaPayload(listOf(api.detail(id))) }.items.first()
    suspend fun seasons(item: EmbyItem,force: Boolean=false,cacheChecked: suspend ()->Unit = {},publish: suspend (CacheDelivery)->Unit = {}) =
        load(cacheKey(namespace,"seasons",item.id,language()),cacheKey(namespace,"seasons",item.id),"page",DataPolicy.LIST,force,publish,cacheChecked) { MediaPayload.from(api.seasons(item)) }.page()
    suspend fun episodes(seriesId: String,seasonId: String="",force: Boolean=false,cacheChecked: suspend ()->Unit = {},publish: suspend (CacheDelivery)->Unit = {},start: Int=0) =
        load(if(start==0) cacheKey(namespace,"episodes",seriesId,seasonId,language(),60) else cacheKey(namespace,"episodes",seriesId,seasonId,language(),60,start),
            cacheKey(namespace,"episodes",seriesId,seasonId),"page",DataPolicy.LIST,force,publish,cacheChecked) { MediaPayload.from(api.episodes(seriesId,seasonId,start=start)) }.page()
    suspend fun similar(item: EmbyItem,force: Boolean=false,cacheChecked: suspend ()->Unit = {},publish: suspend (CacheDelivery)->Unit = {}) =
        load(cacheKey(namespace,"similar",item.id,language(),20),cacheKey(namespace,"similar",item.id),"page",DataPolicy.LIST,force,publish,cacheChecked) { MediaPayload.from(api.similar(item)) }.page()
    suspend fun filters(parent: EmbyItem?,publish: suspend (CacheDelivery)->Unit = {}): EmbyFilters = coroutineScope {
        suspend fun names(endpoint: String): List<String> {
            val key=cacheKey(namespace,"names",endpoint,parent?.id,language())
            return load(key,key,"filters",DataPolicy.FILTER,false,{}) {
                MediaPayload(filters=EmbyFilters(tags=api.names(endpoint,parent)))
            }.filters!!.tags
        }
        val studios=async { names("Studios") }
        val result=if(api.profile.kind==cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind.JELLYFIN) {
            val key=cacheKey(namespace,"filter-core",parent?.id,language())
            load(key,key,"filters",DataPolicy.FILTER,false,{}) { MediaPayload(filters=api.filterBase(parent)) }.filters!!.copy(studios=studios.await())
        } else {
            val genres=async { names("Genres") }; val years=async { names("Years") }; val tags=async { names("Tags") }
            EmbyFilters(genres.await(),years.await(),studios.await(),tags.await())
        }
        publish(CacheDelivery(MediaPayload(filters=result),false,false,System.currentTimeMillis()))
        result
    }
    suspend fun refresh(location: EmbyLocation) { val group=group(location); cache?.refreshGroup(group,cacheKey(group,0)) }
}
