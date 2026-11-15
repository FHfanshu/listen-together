package app.listentogether.android

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.listentogether.model.DirectSource
import app.listentogether.player.AudioPlayer
import app.listentogether.player.VideoSource

/**
 * Media3 实现：直连 CDN 优先，出错后自动改用服务器代理并从原位置续播。
 * 放映厅也用这一个播放器：B 站、YouTube 的视频轨和音轨两路合成一路，画面由界面上的 Surface 接过去。
 */
@OptIn(UnstableApi::class)
class ExoAudioPlayer(context: Context) : AudioPlayer {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(MUSIC_ATTRS, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        // 熄屏后保持 CPU 和 Wi-Fi 唤醒，否则后台取下一首的播放地址可能卡住。
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    override var listener: AudioPlayer.Listener? = null

    private var proxyUrl: String? = null
    private var video: VideoSource? = null
    private var readyNotified = false
    override var usingProxy: Boolean = false
        private set

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !readyNotified) {
                    readyNotified = true
                    listener?.onReady()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 短暂失去焦点（来电、提示音）ExoPlayer 会自己恢复，不走这里；
                // 这里是长期失去焦点或拔耳机，播放器不会再自己开始。
                if (!playWhenReady && (
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                        )
                ) {
                    listener?.onInterrupted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 切换耳机/蓝牙时音频输出可能失效（5xxx 是 AudioTrack 一类错误），和网络无关：
                // 原地重建输出接着放，不要误判成直连失败。
                if (error.errorCode in AUDIO_OUTPUT_ERRORS) {
                    exo.prepare()
                    return
                }
                val v = video
                if (v != null) {
                    // YouTube 由手机自己解析，没有服务器代理可退。
                    if (!usingProxy && v.proxyVideoUrl.isNotEmpty()) {
                        listener?.onDirectFailed(error.errorCodeName)
                        switchVideo(v, exo.currentPosition, proxy = true)
                    } else {
                        listener?.onError(error.errorCodeName)
                    }
                    return
                }
                val proxy = proxyUrl
                if (!usingProxy && proxy != null) {
                    listener?.onDirectFailed(error.errorCodeName)
                    switchTo(proxy, emptyMap(), exo.currentPosition, proxy = true)
                } else {
                    listener?.onError(error.errorCodeName)
                }
            }
        })
    }

    override fun load(direct: DirectSource?, proxyUrl: String, startPositionMs: Long) {
        this.proxyUrl = proxyUrl
        video = null
        readyNotified = false
        // 听歌要抢音频焦点：别的 App 放声音时让出来，打完电话自己恢复。
        exo.setAudioAttributes(MUSIC_ATTRS, true)
        if (direct != null) {
            switchTo(direct.url, direct.headers, startPositionMs, proxy = false)
        } else {
            switchTo(proxyUrl, emptyMap(), startPositionMs, proxy = true)
        }
    }

    override fun loadVideo(source: VideoSource, startPositionMs: Long) {
        proxyUrl = null
        video = source
        readyNotified = false
        // 看视频时多半开着语音通话：不抢焦点，通话来了也不停。
        exo.setAudioAttributes(MOVIE_ATTRS, false)
        switchVideo(source, startPositionMs, proxy = false)
    }

    private fun switchVideo(v: VideoSource, positionMs: Long, proxy: Boolean) {
        usingProxy = proxy
        val source = when {
            proxy -> MergingMediaSource(progressive(v.proxyVideoUrl, emptyMap()), progressive(v.proxyAudioUrl, emptyMap()))
            // 画面和声音在同一路里（YouTube 没有分轨时的兜底格式）。
            v.audio.url.isEmpty() -> progressive(v.video.url, v.video.headers)
            else -> MergingMediaSource(progressive(v.video.url, v.video.headers), progressive(v.audio.url, v.audio.headers))
        }
        setSource(source, positionMs)
    }

    private fun progressive(url: String, headers: Map<String, String>): MediaSource {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val dataSource = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
            .apply { if (ua != null) setUserAgent(ua) }
        return ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url))
    }

    private fun switchTo(url: String, headers: Map<String, String>, positionMs: Long, proxy: Boolean) {
        usingProxy = proxy
        setSource(progressive(url, headers), positionMs)
    }

    private fun setSource(source: MediaSource, positionMs: Long) {
        val playWhenReady = exo.playWhenReady
        exo.setMediaSource(source, positionMs)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    override fun play() {
        exo.playWhenReady = true
    }

    override fun pause() {
        exo.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)

    override fun setRate(rate: Float) = exo.setPlaybackSpeed(rate)

    override fun setVolume(volume: Float) {
        exo.volume = volume
    }

    override val positionMs: Long get() = exo.currentPosition

    override val bufferedPositionMs: Long get() = exo.bufferedPosition

    override val isPlaying: Boolean get() = exo.isPlaying

    override fun stop() {
        proxyUrl = null
        video = null
        readyNotified = false
        exo.playWhenReady = false
        exo.stop()
        exo.clearMediaItems()
        exo.setPlaybackSpeed(1f)
    }

    override fun release() = exo.release()

    private companion object {
        val AUDIO_OUTPUT_ERRORS = 5000..5999
        val MUSIC_ATTRS: AudioAttributes =
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val MOVIE_ATTRS: AudioAttributes =
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    }
}
