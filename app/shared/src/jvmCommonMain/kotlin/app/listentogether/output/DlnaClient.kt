package app.listentogether.output

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android implementation supplies a Wi-Fi-bound connection factory, bypassing VPN routes for LAN control. */
class DlnaHttp(private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun get(url: String): String = request(url, null, null)

    suspend fun action(service: DlnaService, action: String, args: Map<String, String>): Map<String, String> {
        val xml = request(service.controlUrl, "${service.type}#$action", DlnaProtocol.envelope(service.type, action, args))
        return DlnaProtocol.response(xml)
    }

    private suspend fun request(url: String, action: String?, body: String?): String = withContext(Dispatchers.IO) {
        val c = connection(URL(DlnaProtocol.httpUrl(url)))
        try {
            c.connectTimeout = 4_000
            c.readTimeout = 5_000
            c.instanceFollowRedirects = false
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPACTION", "\"$action\"")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val result = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 1_048_576) { "音箱返回的数据过大" }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) {
                // Preserve UPnP error codes such as 710 (seek unsupported) without logging a media URL/token.
                if (result.isNotBlank()) DlnaProtocol.response(result)
                throw DlnaException(code.toString(), "音箱请求失败")
            }
            result
        } finally {
            c.disconnect()
        }
    }
}

class DlnaClient(val device: DlnaDevice, private val http: DlnaHttp) {
    private var positionSupported = false
    private var stateSupported = false
    var seekSupported: Boolean = false
        private set
    var volumeSupported: Boolean = false
        private set
    var pauseSupported: Boolean = false
        private set

    suspend fun prepare() {
        val actions = if (device.transport.descriptionUrl.isNotEmpty()) {
            DlnaProtocol.actions(http.get(device.transport.descriptionUrl))
        } else emptySet()
        if (actions.isNotEmpty()) require("SetAVTransportURI" in actions && "Play" in actions) { "这台设备不支持音乐投放" }
        positionSupported = "GetPositionInfo" in actions
        stateSupported = "GetTransportInfo" in actions
        seekSupported = "Seek" in actions && positionSupported
        pauseSupported = "Pause" in actions
        volumeSupported = device.rendering?.descriptionUrl?.takeIf(String::isNotEmpty)?.let { url ->
            runCatching { "SetVolume" in DlnaProtocol.actions(http.get(url)) }.getOrDefault(false)
        } ?: false
    }

    suspend fun load(url: String, track: DlnaTrack, mime: String) {
        http.action(device.transport, "SetAVTransportURI", instance() + mapOf(
            "CurrentURI" to DlnaProtocol.httpUrl(url), "CurrentURIMetaData" to DlnaProtocol.metadata(url, track, mime),
        ))
    }

    suspend fun play() { http.action(device.transport, "Play", instance() + ("Speed" to "1")) }
    suspend fun pause() {
        if (!pauseSupported) throw DlnaException("701", "这台音箱不支持暂停")
        http.action(device.transport, "Pause", instance())
    }
    suspend fun stop() { http.action(device.transport, "Stop", instance()) }
    suspend fun seek(ms: Long) {
        if (seekSupported) http.action(device.transport, "Seek", instance() + mapOf("Unit" to "REL_TIME", "Target" to DlnaProtocol.time(ms)))
    }
    suspend fun position(): Long? = if (positionSupported) DlnaProtocol.position(http.action(device.transport, "GetPositionInfo", instance())["RelTime"]) else null
    suspend fun state(): String? = if (stateSupported) http.action(device.transport, "GetTransportInfo", instance())["CurrentTransportState"] else null
    suspend fun volume(volume: Float) {
        if (!volumeSupported) return
        device.rendering?.let {
            http.action(it, "SetVolume", instance() + mapOf("Channel" to "Master", "DesiredVolume" to (volume.coerceIn(0f, 1f) * 100).toInt().toString()))
        }
    }
    private fun instance() = mapOf("InstanceID" to "0")
}
