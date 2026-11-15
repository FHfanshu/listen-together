package app.listentogether.output

import app.listentogether.model.DirectSource
import app.listentogether.player.AudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Commands are serialized: a slow SetURI or Seek must never arrive after the following song's Play. */
class DlnaAudioPlayer(
    private val scope: CoroutineScope,
    private val track: () -> DlnaTrack,
    private val error: (String?) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AudioPlayer {
    override var listener: AudioPlayer.Listener? = null
    private data class Command(val generation: Int?, val operation: suspend () -> Unit)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var generation = 0
    private var client: DlnaClient? = null
    private var relay: LocalAudioRelay? = null
    private var poll: Job? = null
    private var pollQueued = false
    private var loaded = false
    private var playing = false
    private var measured = 0L
    private var measuredAt = 0L
    private var duration = 0L
    private var volume = 1f
    private var failures = 0
    private var lastSeekAt = 0L
    private var positionFresh = false
    private var requestedPlaying = false
    private var pendingSeek: Long? = null

    private val worker = scope.launch {
        for (command in commands) {
            if (command.generation != null && command.generation != generation) continue
            try {
                command.operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (command.generation == null || command.generation == generation) {
                    error(e.message?.takeUnless { it.contains("token=", true) } ?: "音箱连接失败，请检查 Wi-Fi 或切回本机")
                }
            }
        }
    }

    fun attach(value: DlnaClient, audioRelay: LocalAudioRelay) {
        stop()
        relay?.close()
        relay = audioRelay
        client = value
        error(null)
    }

    fun detach() {
        stop()
        relay?.close()
        relay = null
        client = null
    }

    override fun load(direct: DirectSource?, proxyUrl: String, startPositionMs: Long) {
        val target = client ?: return
        generation++
        val version = generation
        poll?.cancel()
        poll = null
        pollQueued = false
        loaded = false
        playing = false
        requestedPlaying = false
        pendingSeek = startPositionMs.takeIf { it > 0 && target.seekSupported }
        positionFresh = false
        failures = 0
        measured = if (target.seekSupported) startPositionMs else 0
        measuredAt = nowMs()
        val metadata = track()
        duration = metadata.durationMs
        // Serve the authenticated stream through a current-track-only LAN URL: receivers need no API token or TLS.
        enqueue(version) {
            val audioRelay = relay ?: return@enqueue
            val audio = withContext(Dispatchers.IO) { audioRelay.publish(proxyUrl) }
            if (version != generation) return@enqueue
            target.load(audio.url, metadata, audio.mime)
            if (version != generation) return@enqueue
            loaded = true
            target.volume(volume)
            if (version != generation) return@enqueue
            positionFresh = true
            measuredAt = nowMs()
            error(null)
            listener?.onReady()
            startPoll(version, target)
        }
    }

    override fun play() {
        val target = client ?: return
        requestedPlaying = true
        val version = generation
        enqueue(version) {
            if (!loaded || (volume == 0f && !target.volumeSupported)) return@enqueue
            target.play()
            if (version != generation) return@enqueue
            pendingSeek?.let { targetPosition ->
                if (target.seekSupported) target.seek(targetPosition)
                measured = targetPosition
                pendingSeek = null
            }
            if (version == generation) {
                playing = true
                measuredAt = nowMs()
                listener?.onPlayingChanged(true)
            }
        }
    }

    override fun pause() {
        val target = client ?: return
        measured = positionMs
        playing = false
        requestedPlaying = false
        enqueue(generation) {
            if (loaded) {
                if (target.pauseSupported) target.pause()
                else { pendingSeek = measured; target.stop() }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = client ?: return
        if (!target.seekSupported) return
        val now = nowMs()
        if (now - lastSeekAt < 3_000) return
        lastSeekAt = now
        val version = generation
        positionFresh = false
        enqueue(version) {
            if (!loaded) return@enqueue
            if (!playing) { pendingSeek = positionMs; return@enqueue }
            target.seek(positionMs)
            if (version == generation) {
                measured = positionMs
                measuredAt = nowMs()
                positionFresh = true
            }
        }
    }

    override fun setRate(rate: Float) { /* Always normal speed for this first implementation. */ }
    override fun setVolume(volume: Float) {
        val previous = this.volume
        this.volume = volume
        val target = client ?: return
        if (!target.volumeSupported) {
            if (volume == 0f && previous > 0f) {
                measured = positionMs
                playing = false
                enqueue(generation) {
                    if (loaded) {
                        if (target.pauseSupported) target.pause()
                        else { pendingSeek = measured; target.stop() }
                    }
                }
            } else if (previous == 0f && volume > 0f && requestedPlaying) play()
        } else enqueue(generation) { if (loaded) target.volume(volume) }
    }

    override val positionMs: Long get() {
        val estimate = measured + if (playing && positionFresh) (nowMs() - measuredAt).coerceAtMost(3_000) else 0
        return if (duration > 0) estimate.coerceIn(0, duration) else estimate.coerceAtLeast(0)
    }
    override val bufferedPositionMs: Long get() = positionMs
    override val isPlaying: Boolean get() = loaded && playing && failures < 3
    override val usingProxy: Boolean get() = true
    override val isRemoteOutput: Boolean get() = true
    override val supportsSeeking: Boolean get() = client?.seekSupported == true && positionFresh

    override fun stop() {
        generation++
        poll?.cancel()
        poll = null
        pollQueued = false
        playing = false
        requestedPlaying = false
        pendingSeek = null
        loaded = false
        positionFresh = false
        measured = 0
        relay?.clear()
        lastSeekAt = 0
        // A captured old device must still receive Stop even when the next generation is already loading.
        client?.let { previous -> enqueue(null) { runCatching { previous.stop() } } }
    }

    override fun release() {
        stop()
        relay?.close()
        relay = null
        commands.close()
        // Let the worker drain its final Stop rather than abandoning a playing remote speaker.
    }

    private fun enqueue(version: Int?, operation: suspend () -> Unit) { commands.trySend(Command(version, operation)) }

    private fun startPoll(version: Int, target: DlnaClient) {
        poll?.cancel()
        poll = scope.launch {
            while (version == generation) {
                delay(1_500)
                if (pollQueued) continue
                pollQueued = true
                enqueue(version) {
                    try {
                        val p = target.position()
                        if (version != generation) return@enqueue
                        positionFresh = p != null
                        if (p != null) {
                            measured = p
                            measuredAt = nowMs()
                            positionFresh = true
                        }
                        val state = target.state()
                        if (version != generation) return@enqueue
                        val wasPlaying = playing
                        if (state != null) playing = state == "PLAYING"
                        failures = 0
                        error(null)
                        if (playing && !wasPlaying) listener?.onPlayingChanged(true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures++
                        if (failures >= 3) {
                            positionFresh = false
                            error("音箱暂时没有响应，请检查 Wi-Fi 或切回本机")
                        }
                    } finally {
                        pollQueued = false
                    }
                }
            }
        }
    }
}
