package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel

/** One recent window per visible shelf/grid, owned by its navigation entry. No Bitmap or Context. */
internal class LibraryPresentation : ViewModel() {
    var sources by mutableStateOf<List<HomeSource>>(emptyList())
    var sourcesLoaded by mutableStateOf(false)
    private val shelves = mutableMapOf<HomeSection, LibraryWindow>()
    private var details: LibraryDetailWindow? = null
    private var hero: LibraryHeroWindow? = null
    fun hero(source: String?): LibraryHeroWindow = hero?.takeIf { it.source == source }
        ?: LibraryHeroWindow(source).also { hero = it }
    fun window(section: HomeSection, source: String?): LibraryWindow {
        val existing = shelves[section]
        return if (existing != null && existing.source == source) existing
        else LibraryWindow(source).also { shelves[section] = it }
    }
    fun detail(entity: Long, source: String?): LibraryDetailWindow = details
        ?.takeIf { it.entity == entity && it.source == source }
        ?: LibraryDetailWindow(entity, source).also { details = it }
}

internal data class LibraryHero(val card: HomeCard, val details: LibraryDetails, val contentRevision: Long)
internal class LibraryHeroWindow(val source: String?) {
    var rows by mutableStateOf<List<LibraryHero>>(emptyList())
    var loaded by mutableStateOf(false)
    var revision = -1L
}

internal class LibraryDetailWindow(val entity: Long, val source: String?) {
    var card by mutableStateOf<HomeCard?>(null)
    var rich by mutableStateOf(LibraryDetails())
    var richRevision: Long? = null
    var versions by mutableStateOf<List<HomeVersion>>(emptyList())
    var seasons by mutableStateOf<List<Int>>(emptyList())
    var versionCount by mutableIntStateOf(0)
    var selectedVersion by mutableStateOf<HomeVersion?>(null)
    var loaded by mutableStateOf(false)
}

internal class LibraryWindow(val source: String?) {
    var rows by mutableStateOf<List<HomeCard>>(emptyList())
    var loaded by mutableStateOf(false)
    var revision = -1L
}
