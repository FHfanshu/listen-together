package app.listentogether.player

import app.listentogether.model.TrackRef
import app.listentogether.model.VideoInfo

/**
 * 在手机上自己解析视频地址。YouTube 的地址绑定解析它的那台机器的 IP，服务器解析出来手机放不了，
 * 所以由每台手机用自己的网络（要能访问 YouTube）解析、直连播放，没有服务器代理。
 */
interface LocalVideoResolver {
    /** qn 沿用 B 站画质编号（16/32/64/80 = 360P/480P/720P/1080P），null 用默认。 */
    suspend fun resolve(track: TrackRef, qn: Int?): VideoInfo
}

object VideoSources {
    const val BILIBILI = "bilibili"
    const val YOUTUBE = "youtube"

    /** 由手机自己解析的来源。 */
    fun isLocal(source: String) = source == YOUTUBE
}

/** 本平台的 YouTube 解析；不支持的平台返回 null。 */
expect fun youTubeResolver(): LocalVideoResolver?

/** B 站画质编号和高度互换，YouTube 也用这套编号，画质面板不用分两套。 */
object VideoQn {
    const val DEFAULT_YOUTUBE = 64

    fun height(qn: Int): Int = when {
        qn >= 120 -> 2160
        qn >= 112 -> 1080
        qn >= 80 -> 1080
        qn >= 64 -> 720
        qn >= 32 -> 480
        else -> 360
    }

    fun ofHeight(h: Int): Int = when {
        h >= 2160 -> 120
        h >= 1080 -> 80
        h >= 720 -> 64
        h >= 480 -> 32
        else -> 16
    }
}
