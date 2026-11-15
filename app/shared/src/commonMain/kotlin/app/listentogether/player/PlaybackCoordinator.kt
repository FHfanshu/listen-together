package app.listentogether.player

import app.listentogether.model.PlaybackState
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.Snapshot
import app.listentogether.model.TrackRef
import app.listentogether.model.VideoInfo
import app.listentogether.net.ServerApi
import app.listentogether.sync.ClockSync
import app.listentogether.sync.outputSyncMode
import app.listentogether.sync.DriftAction
import app.listentogether.sync.DriftController
import app.listentogether.sync.SeekPlanner
import app.listentogether.sync.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 调试页显示的同步指标。 */
data class SyncDebug(
    val offsetMs: Long? = null,
    val rttMs: Long? = null,
    val expectedMs: Long? = null,
    val actualMs: Long? = null,
    val driftMs: Long? = null,
    val rate: Float = 1f,
    val usingProxy: Boolean = false,
    val seeks: Int = 0,
    val status: String = PlaybackStatus.IDLE,
    val mode: SyncMode = SyncMode.DEFAULT,
)

/**
 * 把服务器的播放状态落到本地播放器上：
 * - 新的 seq：取播放地址并预缓冲，缓冲好后上报 track_status=ready；
 * - playing：在 startAt 对应的本地时刻开播，迟到则直接跳到当前应在的位置；
 * - 播放中每 500ms 比较实际进度与 serverNow - startAt，交给 [DriftController] 纠偏。
 * 听歌房间不能拖进度；放映厅里有人暂停（paused）就停在服务器给的位置，
 * 继续或拖进度时服务器挪动 startAt，这里跳到新位置、等提前量过去再一起出声。
 */
class PlaybackCoordinator(
    private val scope: CoroutineScope,
    private val room: StateFlow<Snapshot?>,
    private val clock: ClockSync,
    private val player: AudioPlayer,
    private val api: () -> ServerApi?,
    /** 本机选的音质，每次取地址时读。 */
    private val quality: () -> String,
    /** 放映厅本机选的画质（B 站 qn），null 表示用服务器默认。 */
    private val videoQn: () -> Int? = { null },
    /** 由手机自己解析的视频来源（YouTube）；不支持的平台为 null。 */
    private val youtube: LocalVideoResolver? = youTubeResolver(),
    /** 本机选的同步档位，之后可用 [setSyncMode] 改。 */
    syncMode: SyncMode,
    private val reportStatus: suspend (seq: Int, status: String) -> Unit,
) : AudioPlayer.Listener {
    private var mode = syncMode
    private var effectiveMode = outputSyncMode(mode, player.isRemoteOutput)
    private var drift = effectiveMode.controller()
    private val seekPlan = SeekPlanner()
    private val _debug = MutableStateFlow(SyncDebug())
    val debug: StateFlow<SyncDebug> = _debug
    private val _interrupted = MutableStateFlow(false)
    /** 房间在放歌，但本机被别的 App 或拔耳机打断停下了，等用户点恢复。 */
    val interrupted: StateFlow<Boolean> = _interrupted
    private val _playingQuality = MutableStateFlow<String?>(null)
    /** 当前这首实际在放的音质；没在放时为 null。 */
    val playingQuality: StateFlow<String?> = _playingQuality
    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    /** 放映厅当前视频的画质信息；不在放视频时为 null。 */
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo
    private val _videoError = MutableStateFlow<String?>(null)
    /** 本机解析当前视频失败的原因（比如 YouTube 连不上），画面上提示用；换视频时清掉。 */
    val videoError: StateFlow<String?> = _videoError

    private var current: PlaybackState? = null
    private var isVideo = false
    /** 这个 seq 已经按服务器时间线开播或停住过；之后 startAt 变了（继续、拖进度）就走 [restart]。 */
    private var onTimeline = false
    private var loadedSeq = -1
    private var ready = false
    private var reported = false
    private var loadJob: Job? = null
    private var startJob: Job? = null
    private var scheduledStartAt: Long? = null
    private var driftJob: Job? = null
    /** 跳转或卡顿恢复后的头 1 秒，播放器上报的进度还不稳定，这段时间不纠偏。 */
    private var settleUntil = 0L
    private var seeks = 0
    /** 刚跳过，等稳定后的第一次测量用来校正提前量。 */
    private var landing = false

    fun start() {
        player.listener = this
        scope.launch {
            room.map { it?.let { s -> s.playback to s.isVideo } }.distinctUntilChanged().collect {
                isVideo = it?.second ?: false
                onPlayback(it?.first)
            }
        }
    }

    private fun onPlayback(pb: PlaybackState?) {
        current = pb
        val item = pb?.item
        if (pb == null || item == null || pb.status == PlaybackStatus.IDLE) {
            _interrupted.value = false
            stopAll()
            return
        }
        if (pb.seq != loadedSeq) load(pb, item.track)
        if (ready) applyStatus(pb)
        _debug.value = _debug.value.copy(status = pb.status)
    }

    private fun applyStatus(pb: PlaybackState) {
        when (pb.status) {
            PlaybackStatus.PREPARING -> report(pb.seq, TRACK_READY)
            PlaybackStatus.PAUSED -> holdPaused(pb)
            PlaybackStatus.PLAYING -> if (onTimeline) restart(pb) else scheduleStart(pb)
        }
    }

    /** 放映厅暂停：停在服务器给的位置。 */
    private fun holdPaused(pb: PlaybackState) {
        _interrupted.value = false
        startJob?.cancel()
        driftJob?.cancel()
        onTimeline = true
        scheduledStartAt = null
        player.pause()
        player.setRate(1f)
        drift.reset()
        val pos = pb.positionMs ?: return
        if (abs(player.positionMs - pos) > SEEK_TOLERANCE_MS) player.seekTo(pos)
    }

    /**
     * 放映厅里继续播放或拖了进度：服务器把 startAt 挪成「现在 + 提前量 − 新位置」。
     * 先停住跳到提前量之后的位置，等时间线走到那里再出声，大家就在同一刻从同一处开始。
     */
    private fun restart(pb: PlaybackState) {
        if (pb.startAt == scheduledStartAt && (startJob?.isActive == true || driftJob?.isActive == true)) return
        startJob?.cancel()
        driftJob?.cancel()
        scheduledStartAt = pb.startAt
        startJob = scope.launch {
            val target = (expectedPositionMs(pb) ?: return@launch) + RESTART_LEAD_MS
            player.pause()
            player.setRate(1f)
            drift.reset()
            if (abs(player.positionMs - target) > SEEK_TOLERANCE_MS) player.seekTo(target)
            val wait = target - (expectedPositionMs(pb) ?: target)
            if (wait > 0) delay(wait)
            player.play()
            settleUntil = clock.localNowMs() + SETTLE_MS
            driftJob = scope.launch { driftLoop(pb) }
        }
    }

    /** 被打断后恢复：直接跳到房间当前应在的位置接着放。 */
    fun resume() {
        _interrupted.value = false
        val pb = current ?: return
        if (pb.status != PlaybackStatus.PLAYING || pb.seq != loadedSeq) return
        if (!ready) return refresh()
        startJob?.cancel()
        driftJob?.cancel()
        scheduledStartAt = null
        onTimeline = false
        scheduleStart(pb)
    }

    /** 重新取地址、重新缓冲当前这首，用来摆脱各种说不清的"没声音"。 */
    fun refresh() {
        _interrupted.value = false
        val pb = current ?: return
        loadedSeq = -1
        onPlayback(pb)
    }

    private fun load(pb: PlaybackState, track: TrackRef) {
        // 换歌就当重新开始，之前的打断不再拦着；下一首照常放。
        _interrupted.value = false
        stopAll()
        loadedSeq = pb.seq
        val video = isVideo
        loadJob = scope.launch {
            val server = api() ?: return@launch
            val start = expectedPositionMs(pb) ?: pb.positionMs ?: 0
            if (video) {
                val qn = videoQn()
                val local = if (VideoSources.isLocal(track.source)) youtube else null
                val result = when {
                    local != null -> runCatching { local.resolve(track, qn) }.recoverCatching { local.resolve(track, qn) }
                    VideoSources.isLocal(track.source) -> Result.failure(IllegalStateException("这台设备还不能放 YouTube"))
                    else -> runCatching { server.video(track, qn) }.recoverCatching { server.video(track, qn, refresh = true) }
                }
                val info = result.getOrNull()
                _videoError.value = result.exceptionOrNull()?.message
                if (info == null) {
                    report(pb.seq, TRACK_FAILED)
                    return@launch
                }
                _videoInfo.value = info
                val proxy = { path: String -> if (path.isEmpty()) "" else server.withToken(path) }
                player.loadVideo(VideoSource(info.video, info.audio, proxy(info.proxyVideo), proxy(info.proxyAudio)), start)
                return@launch
            }
            val q = quality()
            val info = runCatching { server.play(track, quality = q) }.recoverCatching { server.play(track, refresh = true, quality = q) }.getOrNull()
            if (info == null) {
                report(pb.seq, TRACK_FAILED)
                return@launch
            }
            _playingQuality.value = info.quality
            player.load(info.direct, server.proxyUrl(info), start)
        }
    }

    override fun onReady() {
        ready = true
        val pb = current ?: return
        if (pb.seq != loadedSeq) return
        applyStatus(pb)
    }

    override fun onDirectFailed(reason: String) {
        val track = current?.item?.track ?: return
        _debug.value = _debug.value.copy(usingProxy = true)
        scope.launch { runCatching { api()?.reportDirectFailed(track) } }
    }

    override fun onPlayingChanged(playing: Boolean) {
        if (!playing) return
        settleUntil = clock.localNowMs() + SETTLE_MS
    }

    override fun onInterrupted() {
        if (current?.status == PlaybackStatus.PLAYING) _interrupted.value = true
    }

    override fun onError(reason: String) {
        val pb = current ?: return
        if (pb.seq == loadedSeq) report(pb.seq, TRACK_FAILED)
    }

    private fun report(seq: Int, status: String) {
        if (reported) return
        reported = true
        scope.launch { runCatching { reportStatus(seq, status) } }
    }

    private fun scheduleStart(pb: PlaybackState) {
        if (startJob?.isActive == true || driftJob?.isActive == true) return
        val startAt = pb.startAt ?: return
        onTimeline = true
        scheduledStartAt = startAt
        startJob = scope.launch {
            val wait = startAt - clock.serverNowMs()
            if (wait > 0) {
                player.seekTo(0)
                delay(wait)
            } else {
                seekToExpected(-wait)
            }
            drift.reset()
            player.setRate(1f)
            player.play()
            settleUntil = clock.localNowMs() + SETTLE_MS
            driftJob = scope.launch { driftLoop(pb) }
        }
    }

    private suspend fun driftLoop(pb: PlaybackState) {
        while (true) {
            delay(500)
            val outputMode = outputSyncMode(mode, player.isRemoteOutput)
            if (outputMode != effectiveMode) {
                effectiveMode = outputMode
                drift = effectiveMode.controller()
                player.setRate(1f)
            }
            val expected = expectedPositionMs(pb) ?: continue
            val actual = player.positionMs
            val best = clock.best
            _debug.value = _debug.value.copy(
                offsetMs = best?.offsetMs,
                rttMs = best?.rttMs,
                expectedMs = expected,
                actualMs = actual,
                driftMs = actual - expected,
                rate = drift.currentRate,
                mode = effectiveMode,
                usingProxy = player.usingProxy,
                seeks = seeks,
            )
            if (!player.isPlaying || clock.localNowMs() < settleUntil) continue
            if (landing) {
                landing = false
                seekPlan.onLanded(actual - expected)
            }
            val duration = pb.item?.track?.durationMs ?: 0
            if (duration > 0 && expected >= duration) continue
            when (val action = drift.decide(expected, actual)) {
                DriftAction.None -> Unit
                is DriftAction.SetRate -> player.setRate(action.rate)
                // 网络跟不上时短时间内会反复落后，这时宁可先慢着，也不反复打断播放。
                is DriftAction.Seek -> {
                    // 控制器已经把倍速记成 1.0，播放器也要跟着复位，不然跳完还在按 0.97/1.03 跑，误差又越攒越大。
                    player.setRate(1f)
                    if (player.supportsSeeking && seekPlan.canSeek(clock.localNowMs())) seekToExpected(action.positionMs)
                }
            }
        }
    }

    /** 换同步档位：倍速先复位，换一套纠偏参数接着跑，不打断播放。 */
    fun setSyncMode(newMode: SyncMode) {
        if (newMode == mode) return
        mode = newMode
        effectiveMode = outputSyncMode(newMode, player.isRemoteOutput)
        drift = effectiveMode.controller()
        player.setRate(1f)
        _debug.value = _debug.value.copy(rate = 1f, mode = effectiveMode)
    }

    /** 跳到房间期望的位置，按缓冲区内/外分别加上学到的提前量。 */
    private fun seekToExpected(expectedMs: Long) {
        // 留 1 秒余量：目标贴着缓冲边缘时，落地后马上又要联网。
        val inBuffer = expectedMs >= player.positionMs && expectedMs + 1_000 < player.bufferedPositionMs
        val positionMs = seekPlan.target(expectedMs, inBuffer)
        if (abs(player.positionMs - positionMs) < 30) return
        player.seekTo(positionMs)
        val now = clock.localNowMs()
        seekPlan.onSeek(now, inBuffer)
        landing = true
        settleUntil = now + SETTLE_MS
        seeks++
    }

    private fun expectedPositionMs(pb: PlaybackState): Long? {
        val startAt = pb.startAt ?: return null
        if (pb.status != PlaybackStatus.PLAYING) return null
        return (clock.serverNowMs() - startAt).coerceAtLeast(0)
    }

    private fun stopAll() {
        loadJob?.cancel()
        startJob?.cancel()
        driftJob?.cancel()
        player.stop()
        _playingQuality.value = null
        _videoError.value = null
        _videoInfo.value = null
        loadedSeq = -1
        onTimeline = false
        scheduledStartAt = null
        ready = false
        reported = false
        seeks = 0
        drift.reset()
        seekPlan.reset()
        landing = false
        // 对时结果与歌曲无关，切歌时保留。
        _debug.value = SyncDebug(offsetMs = clock.best?.offsetMs, rttMs = clock.best?.rttMs, mode = mode)
    }

    companion object {
        const val TRACK_READY = "ready"
        const val TRACK_FAILED = "failed"
        private const val SETTLE_MS = 1_000L
        /** 和服务器的 StartLead 一致：继续、拖进度后多久一起出声。 */
        private const val RESTART_LEAD_MS = 800L
        private const val SEEK_TOLERANCE_MS = 150L
    }
}
