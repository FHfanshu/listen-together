package app.listentogether.output

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

data class DlnaService(val type: String, val controlUrl: String, val descriptionUrl: String)

data class DlnaDevice(
    val id: String,
    val name: String,
    val location: String,
    val transport: DlnaService,
    val rendering: DlnaService? = null,
)

data class DlnaTrack(val title: String = "一起听", val artist: String = "", val durationMs: Long = 0, val cover: String? = null)

/** Only protocol encoding/decoding lives here; no Android UI or vendor SDK dependency. */
object DlnaProtocol {
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"

    fun discoveryRequest(): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\nMX: 3\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)

    fun discoveryLocation(response: String): String? {
        if (!response.lineSequence().firstOrNull().orEmpty().startsWith("HTTP/1.1 200", true)) return null
        return response.lineSequence().drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("location", true)) {
                runCatching { httpUrl(line.substring(colon + 1).trim()) }.getOrNull()
            } else null
        }.firstOrNull()
    }

    fun devices(xml: String, location: String): List<DlnaDevice> {
        val root = parse(xml)
        val base = root.children("URLBase").firstOrNull()?.textContent?.trim().takeUnless { it.isNullOrEmpty() } ?: location
        val nodes = root.getElementsByTagNameNS("*", "device")
        return (0 until nodes.length).mapNotNull { index ->
            val device = nodes.item(index) as? Element ?: return@mapNotNull null
            if (!device.value("deviceType").startsWith(MEDIA_RENDERER)) return@mapNotNull null
            val services = device.children("serviceList").flatMap { it.children("service") }.mapNotNull { service ->
                val type = service.value("serviceType")
                val control = service.value("controlURL").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val scpd = service.value("SCPDURL")
                runCatching {
                    DlnaService(type, httpUrl(URI(base).resolve(control).toString()),
                        if (scpd.isBlank()) "" else httpUrl(URI(base).resolve(scpd).toString()))
                }.getOrNull()
            }
            val transport = services.filter { it.type.startsWith(AV_TRANSPORT) }
                .maxByOrNull { it.type.substringAfterLast(':').toIntOrNull() ?: 0 } ?: return@mapNotNull null
            val udn = device.value("UDN").ifBlank { "$location#$index" }
            DlnaDevice(udn, device.value("friendlyName").ifBlank { "DLNA 音箱" }, location, transport,
                services.firstOrNull { it.type.startsWith(RENDERING_CONTROL) })
        }.distinctBy { it.id }
    }

    fun actions(xml: String): Set<String> {
        val root = parse(xml)
        val nodes = root.getElementsByTagNameNS("*", "action")
        return (0 until nodes.length).mapNotNull { (nodes.item(it) as? Element)?.value("name")?.takeIf(String::isNotBlank) }.toSet()
    }

    fun envelope(service: String, action: String, arguments: Map<String, String>): String {
        require(action.matches(Regex("[A-Za-z][A-Za-z0-9]*")))
        require(arguments.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) })
        val args = arguments.entries.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${escape(service)}\">$args</u:$action></s:Body></s:Envelope>"
    }

    /** A SOAP Fault is an error even if a renderer incorrectly sends HTTP 200. */
    fun response(xml: String): Map<String, String> {
        if (xml.isBlank()) return emptyMap()
        val root = parse(xml)
        val faults = root.getElementsByTagNameNS("*", "Fault")
        if (faults.length > 0) {
            val fault = faults.item(0) as Element
            val code = fault.getElementsByTagNameNS("*", "errorCode").item(0)?.textContent.orEmpty()
            val detail = fault.getElementsByTagNameNS("*", "errorDescription").item(0)?.textContent.orEmpty()
            throw DlnaException(code, detail.ifBlank { "音箱拒绝了播放控制请求" })
        }
        val bodies = root.getElementsByTagNameNS("*", "Body")
        val body = bodies.item(0) as? Element ?: return emptyMap()
        val result = body.elementChildren().firstOrNull() ?: return emptyMap()
        return result.elementChildren().associate { (it.localName ?: it.tagName) to it.textContent.trim() }
    }

    fun metadata(url: String, track: DlnaTrack, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"listen-together\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(track.title)}</dc:title><dc:creator>${escape(track.artist)}</dc:creator>" +
            "<upnp:artist>${escape(track.artist)}</upnp:artist><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            (track.cover?.let { "<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>" } ?: "") +
            "<res protocolInfo=\"http-get:*:${escape(mime)}:*\"" +
            (if (track.durationMs > 0) " duration=\"${time(track.durationMs)}\"" else "") +
            ">${escape(url)}</res></item></DIDL-Lite>"

    fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    fun position(value: String?): Long? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || !seconds.isFinite() || seconds < 0 || seconds >= 60) return null
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong().takeIf { it >= 0 }
    }

    fun httpUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "音箱返回了无效的 HTTP 地址" }
        return uri.toString()
    }

    private fun escape(value: String): String = value.filter { it >= ' ' || it in "\r\n\t" }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun parse(xml: String): Element {
        require(xml.length <= 1_048_576) { "音箱返回的描述文件过大" }
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "不支持包含 DTD 的设备描述" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are disabled") }
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.elementChildren(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.children(name: String): List<Element> = elementChildren().filter { (it.localName ?: it.tagName) == name }
    private fun Element.value(name: String): String = children(name).firstOrNull()?.textContent?.trim().orEmpty()
}

class DlnaException(val code: String, detail: String) : Exception(if (code.isBlank()) detail else "$detail ($code)")
