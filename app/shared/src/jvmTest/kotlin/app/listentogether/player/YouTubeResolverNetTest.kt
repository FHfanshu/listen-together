package app.listentogether.player

import app.listentogether.model.TrackRef
import app.listentogether.model.VideoStream
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 真连 YouTube 的测试，要能访问外网：LT_NET_TESTS=1 ./gradlew :shared:jvmTest */
class YouTubeResolverNetTest {
    private val enabled = System.getenv("LT_NET_TESTS") == "1"

    @Test
    fun resolvesAndStreamsBothTracks() = runBlocking {
        if (!enabled) return@runBlocking
        for (id in listOf("dQw4w9WgXcQ", "DYptgVvkVLQ")) {
            val info = NewPipeYouTube.resolve(TrackRef("youtube", id), 64)
            println("$id -> ${info.width}x${info.height} qn=${info.qn} ${info.qualities} dur=${info.durationMs}")
            assertTrue(info.height in 1..720, "height ${info.height}")
            assertTrue(info.durationMs > 0)
            assertEquals(206, rangeStatus(info.video), "video range")
            if (info.audio.url.isNotEmpty()) assertEquals(206, rangeStatus(info.audio), "audio range")
        }
    }

    private fun rangeStatus(s: VideoStream): Int {
        val conn = URL(s.url).openConnection() as HttpURLConnection
        s.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Range", "bytes=0-65535")
        val code = conn.responseCode
        if (code < 400) conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return code
    }
}
