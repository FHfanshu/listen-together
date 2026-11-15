package app.listentogether.player

import app.listentogether.model.TrackRef
import app.listentogether.model.VideoInfo
import app.listentogether.model.VideoQuality
import app.listentogether.model.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.schabi.newpipe.extractor.stream.VideoStream as NpVideoStream

actual fun youTubeResolver(): LocalVideoResolver? = NewPipeYouTube

/** 用 NewPipeExtractor 在本机解析 YouTube：取一路纯视频（优先 H.264 MP4）和一路音频（优先 M4A 原声）。 */
object NewPipeYouTube : LocalVideoResolver {
    @Volatile private var inited = false

    private fun ensureInit() {
        if (inited) return
        synchronized(this) {
            if (!inited) {
                NewPipe.init(UrlConnectionDownloader, Localization("zh", "CN"), ContentCountry("US"))
                inited = true
            }
        }
    }

    override suspend fun resolve(track: TrackRef, qn: Int?): VideoInfo = withContext(Dispatchers.IO) {
        ensureInit()
        val info = try {
            StreamInfo.getInfo("https://www.youtube.com/watch?v=${track.id}")
        } catch (e: ReCaptchaException) {
            throw IOException("YouTube 要求人机验证，换个网络或节点试试", e)
        } catch (e: ContentNotAvailableException) {
            throw IOException("这个 YouTube 视频放不了：${e.message}", e)
        } catch (e: IOException) {
            throw IOException("连不上 YouTube（要能访问外网）", e)
        }
        pick(info, VideoQn.height(qn ?: VideoQn.DEFAULT_YOUTUBE), track.durationMs)
    }

    internal fun pick(info: StreamInfo, maxHeight: Int, fallbackDurationMs: Long): VideoInfo {
        val videos = info.videoOnlyStreams.filter { it.progressive() }
        val audios = info.audioStreams.filter { it.progressive() }
        val audio = bestAudio(audios)
        val durationMs = if (info.duration > 0) info.duration * 1000 else fallbackDurationMs

        // 有纯视频轨和音轨就分两路；没有时退回合在一起的那路（一般最高 360P）。
        if (videos.isNotEmpty() && audio != null) {
            // 优先 H.264（几乎所有手机都能硬解），没有再用 VP9/AV1。
            val family = videos.filter { it.isAvc() }.ifEmpty { videos.filter { it.format == MediaFormat.WEBM }.ifEmpty { videos } }
            val v = family.filter { it.height in 1..maxHeight }.maxByOrNull { it.height * 1000 + it.fps }
                ?: family.minBy { it.height }
            val heights = family.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
            return VideoInfo(
                video = stream(v.content),
                audio = stream(audio.content),
                qn = VideoQn.ofHeight(v.height),
                qualities = heights.map { VideoQuality(VideoQn.ofHeight(it), "${it}P") }.distinctBy { it.qn },
                width = v.width,
                height = v.height,
                durationMs = durationMs,
            )
        }
        val muxed = info.videoStreams.filter { it.progressive() }.maxByOrNull { it.height }
            ?: throw IOException("这个 YouTube 视频没有能直接播放的格式")
        return VideoInfo(
            video = stream(muxed.content),
            audio = VideoStream(""),
            qn = VideoQn.ofHeight(muxed.height),
            width = muxed.width,
            height = muxed.height,
            durationMs = durationMs,
        )
    }

    private fun bestAudio(list: List<AudioStream>): AudioStream? {
        // 多语言视频会有配音轨和解说轨，只要原声。
        val original = list.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { list }
        val m4a = original.filter { it.format == MediaFormat.M4A }
        return (m4a.ifEmpty { original }).maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
    }

    private fun org.schabi.newpipe.extractor.stream.Stream.progressive() =
        deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl && content.isNotBlank()

    private fun NpVideoStream.isAvc() = format == MediaFormat.MPEG_4 && (codec?.startsWith("avc") ?: true)

    /** 不同客户端的地址要配对应的 UA 才给下载。 */
    internal fun stream(url: String): VideoStream {
        val headers = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
        } else {
            mapOf("User-Agent" to DESKTOP_UA, "Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
        }
        return VideoStream(url, headers)
    }

    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
}

/** NewPipeExtractor 发请求用的下载器，只用 JDK 自带的 HttpURLConnection。 */
private object UrlConnectionDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = request.httpMethod()
        var hasUa = false
        request.headers().forEach { (k, values) ->
            if (k.equals("User-Agent", true)) hasUa = true
            conn.setRequestProperty(k, values.joinToString(", "))
        }
        if (!hasUa) conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        request.dataToSend()?.let { body ->
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code == 429) throw ReCaptchaException("rate limited", request.url())
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
        val headers = conn.headerFields.filterKeys { it != null }
        return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
    }
}
