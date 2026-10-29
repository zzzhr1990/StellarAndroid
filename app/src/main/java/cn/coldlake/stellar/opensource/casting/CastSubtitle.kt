package cn.coldlake.stellar.opensource.casting

import com.stellar.player.contract.ExternalSubtitle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One bounded UTF-8 SRT resource. Video bytes and the original subtitle file are untouched. */
internal class CastSubtitle(val original: ExternalSubtitle, private val bytes: ByteArray) : CastByteSource {
    override fun open(head: Boolean, range: String?): CastResponse {
        val length = bytes.size.toLong()
        val requested = range?.let { ByteRange.parse(it, length) }
        if (range != null && requested == null)
            return CastResponse(416, mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
        val start = requested?.start?.toInt() ?: 0
        val count = requested?.length?.toInt() ?: bytes.size
        val headers = buildMap {
            put("Content-Type", "text/srt; charset=utf-8")
            put("Content-Length", count.toString())
            put("Accept-Ranges", "bytes")
            if (requested != null) put("Content-Range", "bytes ${requested.start}-${requested.end}/$length")
        }
        return CastResponse(if (requested == null) 200 else 206, headers,
            if (head) null else ByteArrayInputStream(bytes, start, count))
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val timing = Regex("[0-9]{2,}:[0-5][0-9]:[0-5][0-9],[0-9]{3}\\s+-->\\s+[0-9]{2,}:[0-5][0-9]:[0-5][0-9],[0-9]{3}(?:\\s+.*)?")

        fun read(subtitle: ExternalSubtitle): CastSubtitle {
            val file = File(subtitle.path)
            require(file.length() in 1..MAX_BYTES.toLong())
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val charset = when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
            require(text.isNotEmpty() && '\u0000' !in text)
            // Reject ASS/VTT/HTML/error responses instead of relabelling them as SRT.
            text.split(Regex("\n[ \t]*\n+")).forEach { cue ->
                val lines = cue.lines()
                require(lines.size >= 3 && lines[0].trim().toLongOrNull() != null && timing.matches(lines[1].trim()))
                require(lines.drop(2).any { it.isNotBlank() })
            }
            val utf8 = (text + "\n").toByteArray(Charsets.UTF_8)
            require(utf8.size <= MAX_BYTES)
            return CastSubtitle(subtitle, utf8)
        }
    }
}
