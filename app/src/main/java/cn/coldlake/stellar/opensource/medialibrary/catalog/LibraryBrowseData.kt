package cn.coldlake.stellar.opensource.medialibrary.catalog

import org.json.JSONObject

internal data class LibraryPersonData(val id: String, val name: String, val biography: String?,
    val birthDate: String?, val deathDate: String?, val birthplace: String?) {
    companion object {
        fun parse(raw: JSONObject, expected: String): LibraryPersonData {
            if (raw.getString("object_kind") != "person" || MetadataProvider.owned(raw.getString("object_id")) != expected)
                throw MetadataFailure("identity")
            return LibraryPersonData(expected, raw.getString("name"), raw.clean("biography", 32768),
                raw.clean("birth_date"), raw.clean("death_date"), raw.clean("place_of_birth"))
        }
    }
}

internal data class LibraryFilmography(val items: List<MetadataRecord>, val truncated: Boolean) {
    companion object {
        fun parse(raw: JSONObject, expected: String): LibraryFilmography {
            if (MetadataProvider.owned(raw.getString("person_id")) != expected || raw.getJSONArray("items").length() > 40)
                throw MetadataFailure("identity")
            return LibraryFilmography(raw.getJSONArray("items").objects(40).map {
                val id = MetadataProvider.owned(it.getString("media_id"))
                val kind = it.getString("media_kind")
                if (kind !in setOf("movie", "series")) throw MetadataFailure("kind")
                MetadataRecord("catalog:$kind:$id", kind, it.getString("title"),
                    it.clean("date")?.take(4)?.toIntOrNull(), ids = mapOf("catalog" to id))
            }.distinctBy { it.identity }, raw.getBoolean("truncated"))
        }
    }
}

internal data class LibraryChildren(val items: List<MetadataRecord>, val next: String?) {
    companion object {
        fun parse(raw: JSONObject, owner: String, kind: String): LibraryChildren {
            if (raw.getString("owner_id") != owner || raw.getString("state") != "ready" || raw.getJSONArray("items").length() > 50)
                throw MetadataFailure("identity")
            val items = raw.getJSONArray("items").objects(50).map {
                val record = MetadataPresentation.entity(it)
                if (record.kind != (if (kind == "series") "season" else "episode") ||
                    record.ids["catalog-$kind"] != owner) throw MetadataFailure("identity")
                // A card's excerpt is labelled/used as a preview, never saved as a full entity.
                record
            }
            if (items.map { it.identity }.distinct().size != items.size) throw MetadataFailure("identity")
            return LibraryChildren(items, raw.clean("next_cursor", 4096).takeIf { raw.optBoolean("has_more") })
        }
    }
}

/** Name-only credits remain scoped to their originating work. */
internal fun LibraryCredit.navigationKey(): String = listOf(id, name, type, role.orEmpty()).joinToString { "${it.length}:$it" }
