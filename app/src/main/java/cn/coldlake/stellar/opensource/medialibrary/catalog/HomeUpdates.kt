package cn.coldlake.stellar.opensource.medialibrary.catalog

/** In-memory invalidation tokens, not another data cache. Keys are bounded by registered sources. */
internal data class HomeQuery(val section: HomeSection, val source: String?)
internal data class HomeUpdates(val sources: Long = 0, val sections: Map<HomeQuery, Long> = emptyMap()) {
    fun revision(section: HomeSection, source: String?): Long = sections[HomeQuery(section, source)] ?: -sources
}
