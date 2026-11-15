package app.listentogether.output

import app.listentogether.player.AudioPlayer
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaAudioPlayerTest {
    private class Renderer(private val slowFirstLoad: Boolean = false, private val positionSupported: Boolean = true) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        private val requests = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
        val calls: List<Pair<String, Map<String, String>>> get() = synchronized(requests) { requests.toList() }
        val firstLoad = AtomicBoolean(false)
        private var loads = 0
        private var state = "STOPPED"
        init {
            server.createContext("/scpd") { e ->
                val xml = "<scpd><actionList>" + listOf("SetAVTransportURI", "Play", "Stop", "Pause", "Seek", "GetPositionInfo", "GetTransportInfo")
                    .filter { positionSupported || it != "GetPositionInfo" }
                    .joinToString("") { "<action><name>$it</name></action>" } + "</actionList></scpd>"
                val bytes = xml.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            server.createContext("/control") { e ->
                val action = e.requestHeaders.getFirst("SOAPACTION").trim('"').substringAfter('#')
                requests += action to DlnaProtocol.response(e.requestBody.bufferedReader().readText())
                if (action == "SetAVTransportURI") {
                    loads++
                    firstLoad.set(true)
                    if (loads == 1 && slowFirstLoad) Thread.sleep(400)
                }
                when (action) { "Play" -> state = "PLAYING"; "Pause" -> state = "PAUSED_PLAYBACK"; "Stop" -> state = "STOPPED" }
                val values = when (action) { "GetPositionInfo" -> "<RelTime>00:00:12</RelTime>"; "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"; else -> "" }
                val xml = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"${DlnaProtocol.AV_TRANSPORT}1\">$values</u:${action}Response></s:Body></s:Envelope>"
                val bytes = xml.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            server.createContext("/song") { e ->
                e.responseHeaders.add("Content-Type", "audio/mpeg")
                e.responseHeaders.add("Content-Range", "bytes 0-0/120000")
                e.sendResponseHeaders(206, 1); e.responseBody.use { it.write(byteArrayOf(0)) }
            }
            server.start()
        }
        suspend fun client(): DlnaClient = DlnaClient(DlnaDevice("test", "Test", base, DlnaService("${DlnaProtocol.AV_TRANSPORT}1", "$base/control", "$base/scpd")), DlnaHttp()).also { it.prepare() }
        override fun close() { server.stop(0) }
    }

    @Test fun playbackWaitsForReadySeeksAfterPlayAndNeverAdjustsSpeed() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            Renderer().use { renderer ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val errors = mutableListOf<String>()
                val ready = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val player = DlnaAudioPlayer(scope, { DlnaTrack("Test", durationMs = 120000) }, { if (it != null) errors += it })
                player.listener = object : AudioPlayer.Listener {
                    override fun onReady() { ready.complete(Unit) }
                    override fun onDirectFailed(reason: String) { }
                    override fun onError(reason: String) { errors += reason }
                    override fun onPlayingChanged(playing: Boolean) { if (playing) started.complete(Unit) }
                }
                try {
                    player.attach(renderer.client(), LocalAudioRelay(InetAddress.getByName("127.0.0.1")))
                    player.load(null, "${renderer.base}/song?token=test", 12_000)
                    withTimeout(5000) { ready.await() }
                    assertFalse(player.isPlaying)
                    player.setRate(0.97f)
                    player.play()
                    withTimeout(5000) { started.await() }
                    assertTrue(player.isPlaying)
                    val actions = renderer.calls.map { it.first }
                    assertTrue(actions.indexOf("Play") < actions.indexOf("Seek"))
                    assertEquals("1", renderer.calls.single { it.first == "Play" }.second["Speed"])
                    assertTrue(renderer.calls.first { it.first == "SetAVTransportURI" }.second.getValue("CurrentURI").startsWith("http://127.0.0.1:"))
                    assertFalse(renderer.calls.first { it.first == "SetAVTransportURI" }.second.getValue("CurrentURI").contains("token="))
                    // A renderer with no volume service must still become silent when the app is muted.
                    player.setVolume(0f)
                    withTimeout(5000) { while (renderer.calls.none { it.first == "Pause" }) delay(20) }
                    assertFalse(player.isPlaying)
                    assertTrue(errors.isEmpty(), errors.toString())
                    player.detach()
                    withTimeout(5000) { while (renderer.calls.count { it.first == "Stop" } < 1) delay(20) }
                } finally { player.release(); scope.cancel() }
            }
        } }
    }

    @Test fun aSupersededLoadCannotReportReadyForTheFollowingSong() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            Renderer(slowFirstLoad = true).use { renderer ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val ready = CompletableDeferred<Unit>()
                var readyCount = 0
                val player = DlnaAudioPlayer(scope, { DlnaTrack("Test") }, { })
                player.listener = object : AudioPlayer.Listener {
                    override fun onReady() { readyCount++; ready.complete(Unit) }
                    override fun onDirectFailed(reason: String) { }
                    override fun onError(reason: String) { }
                }
                try {
                    player.attach(renderer.client(), LocalAudioRelay(InetAddress.getByName("127.0.0.1")))
                    player.load(null, "${renderer.base}/song?track=1", 0)
                    withTimeout(5000) { while (!renderer.firstLoad.get()) delay(10) }
                    player.load(null, "${renderer.base}/song?track=2", 0)
                    withTimeout(5000) { ready.await() }
                    assertEquals(1, readyCount)
                    val loads = renderer.calls.filter { it.first == "SetAVTransportURI" }
                    assertEquals(2, loads.size)
                    assertTrue(loads[0].second["CurrentURI"] != loads[1].second["CurrentURI"])
                } finally { player.release(); scope.cancel() }
            }
        } }
    }

    @Test fun playbackRemainsActiveWhenTheRendererCannotReportPosition() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher) {
            Renderer(positionSupported = false).use { renderer ->
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val polled = CompletableDeferred<Unit>()
                var ready = false
                val errors = mutableListOf<String>()
                val player = DlnaAudioPlayer(scope, { DlnaTrack("Test") }, {
                    if (it != null) errors += it
                    else if (ready) polled.complete(Unit)
                })
                player.listener = object : AudioPlayer.Listener {
                    override fun onReady() { ready = true; player.play() }
                    override fun onDirectFailed(reason: String) { }
                    override fun onError(reason: String) { errors += reason }
                }
                try {
                    player.attach(renderer.client(), LocalAudioRelay(InetAddress.getByName("127.0.0.1")))
                    player.load(null, "${renderer.base}/song", 12_000)
                    withTimeout(5000) { polled.await() }
                    assertTrue(player.isPlaying)
                    assertFalse(player.supportsSeeking)
                    assertTrue(renderer.calls.none { it.first == "GetPositionInfo" || it.first == "Seek" })
                    assertTrue(errors.isEmpty(), errors.toString())
                } finally { player.release(); scope.cancel() }
            }
        } }
    }
}
