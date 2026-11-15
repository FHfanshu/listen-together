package app.listentogether.player

import app.listentogether.model.DirectSource
import app.listentogether.model.VideoStream

/**
 * 放映厅的一个视频：视频轨和音轨是两路，播放器合成一路放。
 * 直连失败时两路都改走代理（代理画质更低，所以两路要一起换）。
 * audio.url 为空表示画面和声音在同一路；proxyVideoUrl 为空表示没有代理（YouTube 由手机自己解析）。
 */
data class VideoSource(
    val video: VideoStream,
    val audio: VideoStream,
    val proxyVideoUrl: String,
    val proxyAudioUrl: String,
)

/**
 * 各平台的播放器（Android 用 Media3，iOS 用 AVPlayer）。所有方法在主线程调用，回调也在主线程。
 *
 * load 时优先直连 CDN（带服务器给的请求头），直连出错自动改用代理地址并回调 [Listener.onDirectFailed]。
 */
interface AudioPlayer {
    interface Listener {
        /** 已缓冲到可以立即开播。 */
        fun onReady()
        fun onDirectFailed(reason: String)
        /** 直连和代理都失败。 */
        fun onError(reason: String)
        /** 开始/停止出声（含缓冲卡住和恢复）。 */
        fun onPlayingChanged(playing: Boolean) {}
        /** 被系统打断而停下：别的 App 抢走了音频焦点，或者拔耳机/蓝牙断开。不会自己恢复。 */
        fun onInterrupted() {}
    }

    var listener: Listener?

    fun load(direct: DirectSource?, proxyUrl: String, startPositionMs: Long)

    /**
     * 加载放映厅的视频。看视频时多半同时开着语音通话，所以不抢音频焦点、被打断也不停：
     * 别的 App 出声时只压低音量。不支持视频的平台直接报错。
     */
    fun loadVideo(source: VideoSource, startPositionMs: Long) {
        listener?.onError("这台设备还不支持放映厅")
    }
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    /** 本机音量 0..1。 */
    fun setVolume(volume: Float)
    val positionMs: Long
    /** 已经下载好、不用再联网就能播到的位置。 */
    val bufferedPositionMs: Long
    val isPlaying: Boolean
    /** 当前用的是直连还是代理，调试页显示用。 */
    val usingProxy: Boolean
    fun stop()
    fun release()
}
