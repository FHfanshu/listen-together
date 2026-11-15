package app.listentogether.output

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercise actual HTTP/SOAP and the speaker's GET/HEAD/Range path without touching a real account or device. */
class DlnaPlaybackIntegrationTest {
    @Test fun rendererCanLoadPlaySeekPollPauseAndChangeVolumeAtNormalSpeed() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/scpd") { exchange ->
            val body = "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><actionList>" +
                listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo", "GetTransportInfo", "SetVolume")
                    .joinToString("") { "<action><name>$it</name></action>" } + "</actionList></scpd>"
            val bytes = body.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/control") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").trim('"').substringAfter('#')
            val args = DlnaProtocol.response(exchange.requestBody.bufferedReader().readText())
            calls += action to args
            val values = when (action) { "GetPositionInfo" -> "<RelTime>00:00:12.500</RelTime>"; "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"; else -> "" }
            val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"${DlnaProtocol.AV_TRANSPORT}1\">$values</u:${action}Response></s:Body></s:Envelope>"
            val bytes = body.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val transport = DlnaService("${DlnaProtocol.AV_TRANSPORT}1", "$base/control", "$base/scpd")
            val rendering = DlnaService("${DlnaProtocol.RENDERING_CONTROL}1", "$base/control", "$base/scpd")
            val client = DlnaClient(DlnaDevice("test", "Test speaker", base, transport, rendering), DlnaHttp())
            client.prepare()
            assertTrue(client.seekSupported)
            assertTrue(client.volumeSupported)
            client.load("$base/audio?id=1&proof=test", DlnaTrack("测试歌曲", "测试歌手", 120000), "audio/mpeg")
            client.play()
            client.seek(12000)
            assertEquals(12500L, client.position())
            assertEquals("PLAYING", client.state())
            client.pause()
            client.volume(0.42f)
            client.stop()
            assertEquals(listOf("SetAVTransportURI", "Play", "Seek", "GetPositionInfo", "GetTransportInfo", "Pause", "SetVolume", "Stop"), calls.map { it.first })
            assertEquals("1", calls.single { it.first == "Play" }.second["Speed"])
            assertEquals("00:00:12", calls.single { it.first == "Seek" }.second["Target"])
            assertEquals("42", calls.single { it.first == "SetVolume" }.second["DesiredVolume"])
            assertEquals("$base/audio?id=1&proof=test", calls.first().second["CurrentURI"])
        } finally { server.stop(0) }
    }

    @Test fun lanRelayHidesCredentialsServesRangesAndInvalidatesPreviousTrack() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val data = "0123456789".toByteArray()
        server.createContext("/source") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val bounds = range?.removePrefix("bytes=")?.split('-')
            val from = bounds?.getOrNull(0)?.toIntOrNull() ?: 0
            val to = bounds?.getOrNull(1)?.toIntOrNull() ?: data.lastIndex
            val bytes = data.copyOfRange(from, to + 1)
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val source = "http://127.0.0.1:${server.address.port}/source?token=not-a-real-credential"
        try {
            LocalAudioRelay(InetAddress.getByName("127.0.0.1")).use { relay ->
                val track = relay.publish(source)
                assertFalse(track.url.contains("token"))
                val ranged = URL(track.url).openConnection() as HttpURLConnection
                ranged.setRequestProperty("Range", "bytes=2-5")
                try {
                    assertEquals(206, ranged.responseCode)
                    assertEquals("bytes 2-5/10", ranged.getHeaderField("Content-Range"))
                    assertEquals("2345", ranged.inputStream.bufferedReader().readText())
                } finally { ranged.disconnect() }
                val head = URL(track.url).openConnection() as HttpURLConnection
                head.requestMethod = "HEAD"
                try { assertEquals(200, head.responseCode); assertEquals(10L, head.contentLengthLong) }
                finally { head.disconnect() }
                relay.publish(source)
                val old = URL(track.url).openConnection() as HttpURLConnection
                try { assertEquals(404, old.responseCode) } finally { old.disconnect() }
                val arbitrary = URL(track.url.substringBefore("/audio/") + "/?url=https://example.com").openConnection() as HttpURLConnection
                try { assertEquals(404, arbitrary.responseCode) } finally { arbitrary.disconnect() }
            }
        } finally { server.stop(0) }
    }
}
