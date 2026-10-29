package cn.coldlake.stellar.opensource.medialibrary.catalog

import org.json.JSONObject

/** Locally cached TMDB summaries contain no availability or playback state. */
internal object MetadataPresentation {
    fun entity(raw: JSONObject): MetadataRecord = MetadataRecord.parse(raw.toString())
}
