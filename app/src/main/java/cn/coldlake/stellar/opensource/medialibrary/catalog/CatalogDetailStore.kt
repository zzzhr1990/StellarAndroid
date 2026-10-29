package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.sqlite.SQLiteConnection
import cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator
import org.json.JSONObject

internal const val CATALOG_DETAIL_IMAGES_QUERY = """SELECT o.name,o.doc,o.revision,p.name,o.parent,
                (SELECT r.uri FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1 ORDER BY r.id LIMIT 1)
                FROM objects o LEFT JOIN objects p ON p.scope=o.scope AND p.doc=o.parent
                WHERE o.id IN (
                    SELECT id FROM objects WHERE scope=? AND parent=? AND kind='IMAGE'
                    UNION SELECT id FROM objects WHERE scope=? AND kind='IMAGE' AND parent IN (
                        SELECT doc FROM objects WHERE scope=? AND parent=? AND name='.actors'))
                ORDER BY o.id LIMIT 1024"""

/** Only called for the open detail. Shelf/version payloads never contain credits or artwork galleries. */
internal class CatalogDetailStore(private val db: SQLiteConnection) {
    fun read(id: Long, series: Boolean): LibraryDetails {
        if (id < 0) {
            val row = db.rows("""SELECT r.payload,r.scope FROM imported_record r JOIN catalog_import c ON c.scope=r.scope
                WHERE r.id=? AND r.misses<2 AND c.state!='obsolete'""", -id) { MetadataRecord.parse(it.getText(0)) to it.getText(1) }.firstOrNull() ?: return LibraryDetails()
            if (!series) return row.first.details
            return row.first.seriesDetails ?: db.rows("SELECT payload FROM imported_record WHERE scope=? AND identity=? AND misses<2",
                row.second, row.first.seriesIdentity) { MetadataRecord.parse(it.getText(0)).details }.firstOrNull() ?: LibraryDetails()
        }
        if (db.scalar("SELECT COUNT(*) FROM wall WHERE object=? AND visible=1", id) == 0L) return LibraryDetails()
        val local = local(id, series)
        var result = local
        db.rows("SELECT origin,payload FROM metadata_binding WHERE object=? ORDER BY priority", id) {
            it.getText(0) to MetadataRecord.parse(it.getText(1))
        }.forEach { (origin, record) ->
            val details = if (series) publicParent(record)?.details ?: record.seriesDetails ?: importedParent(record) ?: LibraryDetails() else record.details
            result = if (origin == "provider") result.over(details) else details.over(result)
        }
        return result
    }

    private fun publicParent(record: MetadataRecord): MetadataRecord? = record.ids["catalog-series"]?.let { id ->
        db.rows("SELECT payload FROM metadata_entity WHERE identity=? AND lower(locale)=lower(?) LIMIT 1",
            "catalog:series:$id", record.locale) { MetadataRecord.parse(it.getText(0)) }.firstOrNull()
    }

    private fun importedParent(record: MetadataRecord): LibraryDetails? = record.seriesIdentity?.let { parent ->
        db.rows("""SELECT p.payload FROM imported_record c JOIN imported_record p ON p.scope=c.scope AND p.identity=?
            JOIN catalog_import s ON s.scope=c.scope WHERE c.identity=? AND c.misses<2 AND p.misses<2 AND s.state!='obsolete' LIMIT 1""",
            parent, record.identity) { MetadataRecord.parse(it.getText(0)).details }.firstOrNull()
    }

    fun local(id: Long, series: Boolean = false): LibraryDetails {
        val row = db.rows("""SELECT n.details,o.scope,o.parent FROM dependency d JOIN nfo n ON n.object=d.object
            JOIN objects o ON o.id=n.object JOIN wall w ON w.object=d.owner
            WHERE d.owner=? AND n.valid=1 AND n.kind=CASE WHEN ?=1 THEN 'series' ELSE w.kind END ORDER BY n.object LIMIT 1""",
            id, if (series) 1 else 0) { Triple(it.textOrNull(0), it.getText(1), it.getText(2)) }.firstOrNull()
        var details = row?.first?.let { LibraryDetails.parse(JSONObject(it)) } ?: LibraryDetails()
        if (row != null) {
            // One bounded image lookup for all sibling/.actors references, independent of cast size.
            val images = db.rows(CATALOG_DETAIL_IMAGES_QUERY,
                row.second, row.third, row.second, row.second, row.third) {
                val name = if (it.textOrNull(4) == row.third) it.getText(0) else ".actors/" + it.getText(0)
                name to locator(it.textOrNull(5), it.getText(1), it.getLong(2))
            }.groupBy({ it.first }, { it.second })
            fun resolve(art: LibraryArtwork?): LibraryArtwork? {
                val value = art?.uri ?: return null
                val remote = runCatching { java.net.URI(value) }.getOrNull()
                if (remote?.scheme in setOf("http", "https") && remote?.host != null && remote.userInfo == null) return art
                val path = value.replace('\\', '/').removePrefix("./")
                return images[path]?.singleOrNull()
            }
            details = details.copy(poster = resolve(details.poster), backdrop = resolve(details.backdrop), logo = resolve(details.logo),
                people = details.people.map { it.copy(image = resolve(it.image)) })
        }
        val prefix = if (series) "series_" else ""
        val images = db.rows("""SELECT a.role,o.doc,o.revision,
            (SELECT r.uri FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1 ORDER BY r.id LIMIT 1)
            FROM wall_artwork a JOIN objects o ON o.id=a.image WHERE a.owner=? AND o.kind='IMAGE'""", id) {
            it.getText(0) to locator(it.textOrNull(3), it.getText(1), it.getLong(2))
        }.toMap()
        return details.copy(backdrop = images[prefix + "backdrop"] ?: details.backdrop, logo = images[prefix + "logo"] ?: details.logo)
    }

    private fun locator(tree: String?, doc: String, revision: Long): LibraryArtwork? = tree?.let {
        LibraryArtwork(when {
            CloudLocator.isCloud(it) -> CloudLocator.file(it, doc)
            NasLocator.isNas(it) -> NasLocator.file(it, doc)
            else -> DocumentsContract.buildDocumentUriUsingTree(it.toUri(), doc).toString()
        }, revision.toString())
    }
}
