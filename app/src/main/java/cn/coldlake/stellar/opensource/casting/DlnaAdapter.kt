package cn.coldlake.stellar.opensource.casting

import android.content.Context
import com.stellar.player.contract.PlaybackStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Small SSDP/SOAP control point. No third-party UPnP SDK or incoming event server. */
internal class DlnaAdapter(private val context: Context, private val scope: CoroutineScope) : CastAdapter {
    private data class Service(val type: String, val control: HttpUrl, val actions: Set<String>) {
        fun getAction(name: String) = name.takeIf { it in actions }
    }
    private data class Renderer(val id: String, val name: String, val services: List<Service>) {
        fun findService(name: String) = services.firstOrNull { it.type.startsWith("urn:schemas-upnp-org:service:$name:") }
    }
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val mutableStatus = MutableStateFlow(RemoteStatus())
    override val status = mutableStatus.asStateFlow()
    private val devicesById = ConcurrentHashMap<String, Renderer>()
    private val lifecycle = AtomicLong()
    @Volatile private var selectedNetwork: CastNetwork? = null
    @Volatile private var socket: DatagramSocket? = null
    private var target: Renderer? = null
    private var poll: Job? = null
    private val commands = Mutex()
    private var http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private fun Element.elements(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
    private fun Element.direct(name: String): String? = (0 until childNodes.length).asSequence()
        .mapNotNull { childNodes.item(it) as? Element }.firstOrNull { (it.localName ?: it.tagName) == name }?.textContent?.trim()
    private fun fetch(request: Request): Element = http.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "DLNA request failed (${response.code})" }
        val body = response.body
        check(body.contentLength() <= 1024 * 1024)
        val source = body.source()
        check(!source.request(1024 * 1024 + 1L))
        DlnaXml.parse(source.readByteArray())
    }
    private fun sameOrigin(base: HttpUrl, relative: String): HttpUrl? = base.resolve(relative)?.takeIf {
        it.host == base.host && it.port == base.port && it.scheme == base.scheme && it.username.isEmpty() && it.password.isEmpty()
    }
    private fun describe(url: HttpUrl): List<Renderer> {
        val root = fetch(Request.Builder().url(url).build())
        val base = root.direct("URLBase")?.let { sameOrigin(url, it) } ?: url
        return root.elements("device").mapNotNull { device ->
            if (device.direct("deviceType")?.contains(":MediaRenderer:") != true) return@mapNotNull null
            val id = device.direct("UDN") ?: return@mapNotNull null
            val services = device.elements("service").take(12).mapNotNull { s ->
                val type = s.direct("serviceType") ?: return@mapNotNull null
                if (!type.matches(Regex("urn:schemas-upnp-org:service:(AVTransport|RenderingControl):[0-9]+"))) return@mapNotNull null
                val control = sameOrigin(base, s.direct("controlURL") ?: return@mapNotNull null) ?: return@mapNotNull null
                val scpd = sameOrigin(base, s.direct("SCPDURL") ?: return@mapNotNull null) ?: return@mapNotNull null
                val actions = fetch(Request.Builder().url(scpd).build()).elements("action").mapNotNull { it.direct("name") }.toSet()
                Service(type, control, actions)
            }
            Renderer("dlna:$id", device.direct("friendlyName")?.take(200) ?: "DLNA", services)
                .takeIf { r -> r.findService("AVTransport")?.actions?.containsAll(listOf("SetAVTransportURI", "Play", "Stop")) == true }
        }
    }
    suspend fun discover(lan: CastNetwork) = withContext(Dispatchers.IO) {
        if (selectedNetwork != null && selectedNetwork != lan) close()
        selectedNetwork = lan
        val version = lifecycle.get(); val bind = lan.allowsBinding(context)
        http = http.newBuilder().apply { if (bind) socketFactory(lan.network.socketFactory) }.build()
        val packet = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val seen = mutableSetOf<String>(); val slots = Semaphore(3)
        DatagramSocket(null).use { udp ->
            socket = udp
            if (bind) lan.network.bindSocket(udp)
            udp.reuseAddress = true; udp.bind(InetSocketAddress(lan.address, 0)); udp.soTimeout = 500
            udp.send(DatagramPacket(packet, packet.size, InetAddress.getByName("239.255.255.250"), 1900))
            val until = android.os.SystemClock.elapsedRealtime() + 5_000
            coroutineScope {
                while (android.os.SystemClock.elapsedRealtime() < until && lifecycle.get() == version && seen.size < 64) {
                    ensureActive()
                    val response = DatagramPacket(ByteArray(8192), 8192)
                    try { udp.receive(response) } catch (_: SocketTimeoutException) { continue }
                    val lines = String(response.data, 0, response.length, Charsets.US_ASCII).lineSequence().toList()
                    if (!lines.firstOrNull().orEmpty().startsWith("HTTP/1.1 200")) continue
                    val location = lines.firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: continue
                    val url = location.toHttpUrlOrNull()?.takeIf { it.host == response.address.hostAddress && it.username.isEmpty() && it.password.isEmpty() } ?: continue
                    if (!seen.add(url.toString())) continue
                    launch { slots.withPermit {
                        try {
                            val found = describe(url)
                            ensureActive()
                            if (lifecycle.get() == version) {
                                found.take(64 - devicesById.size).forEach { devicesById[it.id] = it }
                                mutableDevices.value = devicesById.values.map { CastDevice(it.id, it.name, CastProtocol.DLNA) }.sortedBy { it.name }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    } }
                }
            }
        }
        socket = null
    }
    suspend fun checkDiscoveryAccess() = withContext(Dispatchers.IO) { if (devices.value.isEmpty()) selectedNetwork?.requireAccess(context); Unit }
    suspend fun checkConnection(device: CastDevice) = withContext(Dispatchers.IO) {
        commands.withLock { action(checkNotNull(devicesById[device.id]?.findService("AVTransport")), "GetTransportInfo", "InstanceID" to 0); Unit }
    }
    private fun transport() = checkNotNull(target?.findService("AVTransport"))
    private fun rendering() = checkNotNull(target?.findService("RenderingControl"))
    fun canReloadSubtitles() = transport().actions.containsAll(listOf("Seek", "Pause", "GetPositionInfo", "GetTransportInfo"))
    private fun action(service: Service, name: String, vararg arguments: Pair<String, Any>): Map<String, String> {
        check(service.getAction(name) != null)
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$name xmlns:u=\"${service.type}\">" +
            arguments.joinToString("") { (key, value) -> "<$key>${xml(value.toString())}</$key>" } + "</u:$name></s:Body></s:Envelope>"
        val result = fetch(Request.Builder().url(service.control).header("SOAPAction", "\"${service.type}#$name\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build())
        check(result.elements("Fault").isEmpty())
        val response = result.elements("${name}Response").singleOrNull() ?: error("Invalid DLNA response")
        return (0 until response.childNodes.length).mapNotNull { response.childNodes.item(it) as? Element }
            .associate { (it.localName ?: it.tagName) to it.textContent.orEmpty() }
    }
    suspend fun currentPlayback(): RemoteStatus = withContext(Dispatchers.IO) {
        commands.withLock {
            val service = transport(); val info = action(service, "GetTransportInfo", "InstanceID" to 0)
            val playing = info["CurrentTransportState"]; check(playing == "PLAYING" || playing == "PAUSED_PLAYBACK")
            val position = action(service, "GetPositionInfo", "InstanceID" to 0)
            status.value.copy(status = if (playing == "PLAYING") PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                positionMs = checkNotNull(parseTime(position["RelTime"])))
        }
    }
    override suspend fun load(device: CastDevice, media: CastMedia, positionMs: Long, play: Boolean) = withContext(Dispatchers.IO) {
        poll?.cancelAndJoin()
        commands.withLock {
            target = checkNotNull(devicesById[device.id])
            val service = transport()
            action(service, "SetAVTransportURI", "InstanceID" to 0, "CurrentURI" to media.url,
                "CurrentURIMetaData" to didl(media))
            // Some renderers accept Seek only after Play. Keep the controller paused locally throughout.
            action(service, "Play", "InstanceID" to 0, "Speed" to "1")
            val seek = service.getAction("Seek") != null
            if (positionMs > 0 && seek) action(service, "Seek", "InstanceID" to 0, "Unit" to "REL_TIME", "Target" to time(positionMs))
            val pause = service.getAction("Pause") != null
            if (!play) { check(pause); action(service, "Pause", "InstanceID" to 0) }
            mutableStatus.value = RemoteStatus(if (play) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                if (seek) positionMs else 0, media.durationMs, seek, pause)
        }
        poll = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                delay(2000)
                try {
                    commands.withLock {
                        val service = transport()
                        val info = action(service, "GetTransportInfo", "InstanceID" to 0)
                        val position = if (service.getAction("GetPositionInfo") != null) action(service, "GetPositionInfo", "InstanceID" to 0) else emptyMap()
                        val playback = when (info["CurrentTransportState"]) {
                            "PLAYING" -> PlaybackStatus.PLAYING
                            "TRANSITIONING" -> PlaybackStatus.BUFFERING
                            else -> PlaybackStatus.PAUSED // STOPPED does not prove end-of-media on all renderers.
                        }
                        val volumeService = target?.findService("RenderingControl")
                        val volume = if (volumeService?.getAction("GetVolume") != null && volumeService.getAction("SetVolume") != null)
                            runCatching { action(volumeService, "GetVolume", "InstanceID" to 0, "Channel" to "Master")["CurrentVolume"]?.toFloatOrNull()?.div(100f) }.getOrNull() else null
                        mutableStatus.value = status.value.copy(status = playback,
                            positionMs = parseTime(position["RelTime"]) ?: status.value.positionMs,
                            durationMs = parseTime(position["TrackDuration"])?.takeIf { it > 0 } ?: status.value.durationMs,
                            volume = volume, failure = null)
                    }
                    failures = 0
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val failure = castNetworkFailure(e, CastFailure.DISCONNECTED)
                    if (failure == CastFailure.VPN_BLOCKED || ++failures >= 3) {
                        mutableStatus.value = status.value.copy(status = PlaybackStatus.PAUSED, failure = failure); break
                    }
                }
            }
        }
    }
    private suspend fun transportCommand(name: String, vararg args: Pair<String, Any>) = withContext(Dispatchers.IO) {
        commands.withLock { action(transport(), name, "InstanceID" to 0, *args); Unit }
    }
    override suspend fun play() = transportCommand("Play", "Speed" to "1")
    override suspend fun pause() = transportCommand("Pause")
    override suspend fun seek(positionMs: Long) {
        transportCommand("Seek", "Unit" to "REL_TIME", "Target" to time(positionMs))
        mutableStatus.value = currentPlayback()
    }
    override suspend fun volume(value: Float) = withContext(Dispatchers.IO) {
        commands.withLock { action(rendering(), "SetVolume", "InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to (value.coerceIn(0f, 1f) * 100).toInt()); Unit }
    }
    override suspend fun stop() {
        poll?.cancelAndJoin(); poll = null
        if (target != null) transportCommand("Stop")
        target = null
    }
    override fun close() {
        lifecycle.incrementAndGet()
        socket?.close(); socket = null
        poll?.cancel(); poll = null; target = null; selectedNetwork = null
        devicesById.clear(); mutableDevices.value = emptyList()
    }
    companion object {
        internal fun time(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
        internal fun parseTime(text: String?): Long? {
            val p = text?.split(':') ?: return null
            if (p.size != 3) return null
            val h = p[0].toLongOrNull() ?: return null; val m = p[1].toLongOrNull() ?: return null; val s = p[2].toDoubleOrNull() ?: return null
            return if (h in 0..100000 && m in 0..59 && s >= 0 && s < 60) h * 3600000 + m * 60000 + (s * 1000).toLong() else null
        }
        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        internal fun didl(media: CastMedia) = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
            if (media.subtitleUrl != null) append(" xmlns:sec=\"http://www.sec.co.kr/\" xmlns:pv=\"http://www.pv.com/pvns/\"")
            append("><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xml(media.title)}</dc:title><upnp:class>${if (media.mime.startsWith("audio/")) "object.item.audioItem.musicTrack" else "object.item.videoItem"}</upnp:class><res protocolInfo=\"http-get:*:${xml(media.mime)}:*\"")
            media.subtitleUrl?.let { append(" pv:subtitleFileUri=\"${xml(it)}\" pv:subtitleFileType=\"srt\"") }
            append(">${xml(media.url)}</res>")
            media.subtitleUrl?.let {
                val url = xml(it)
                append("<res protocolInfo=\"http-get:*:text/srt:*\">$url</res>")
                append("<sec:CaptionInfoEx sec:type=\"srt\" protocolInfo=\"http-get:*:text/srt:*\">$url</sec:CaptionInfoEx>")
                append("<sec:CaptionInfo sec:type=\"srt\">$url</sec:CaptionInfo>")
            }
            append("</item></DIDL-Lite>")
        }
    }
}
