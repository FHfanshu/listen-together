package app.listentogether.android.output

import android.content.Context
import android.os.PowerManager
import app.listentogether.android.ExoAudioPlayer
import app.listentogether.model.DirectSource
import app.listentogether.output.DlnaAudioPlayer
import app.listentogether.output.DlnaClient
import app.listentogether.output.DlnaDevice
import app.listentogether.output.DlnaTrack
import app.listentogether.output.LocalAudioRelay
import app.listentogether.player.AudioPlayer
import app.listentogether.player.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioOutputState(
    val device: DlnaDevice? = null,
    val devices: List<DlnaDevice> = emptyList(),
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val seekControlAvailable: Boolean = true,
    val volumeControlAvailable: Boolean = true,
    val error: String? = null,
) {
    val label: String get() = device?.name ?: "本机 / 蓝牙"
}

class AudioOutputRouter(
    context: Context,
    private val local: ExoAudioPlayer,
    private val scope: CoroutineScope,
    track: () -> DlnaTrack,
) : AudioPlayer {
    private val stateMutable = MutableStateFlow(AudioOutputState())
    val state: StateFlow<AudioOutputState> = stateMutable
    private val discovery = DlnaDiscovery(context)
    private val remote = DlnaAudioPlayer(scope, track, error = { message -> stateMutable.value = stateMutable.value.copy(error = message) })
    private var active: AudioPlayer = local
    private var scan: Job? = null
    private var wakeRenewal: Job? = null
    private var volume = 1f
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "listen-together:dlna").apply { setReferenceCounted(false) }
    override var listener: AudioPlayer.Listener? = null

    init {
        local.listener = bridge(local)
        remote.listener = bridge(remote)
    }

    private fun bridge(source: AudioPlayer) = object : AudioPlayer.Listener {
        override fun onReady() { if (active === source) listener?.onReady() }
        override fun onDirectFailed(reason: String) { if (active === source) listener?.onDirectFailed(reason) }
        override fun onError(reason: String) { if (active === source) listener?.onError(reason) }
        override fun onPlayingChanged(playing: Boolean) { if (active === source) listener?.onPlayingChanged(playing) }
        override fun onInterrupted() { if (active === source) listener?.onInterrupted() }
    }

    fun scan() {
        if (scan?.isActive == true) return
        stateMutable.value = stateMutable.value.copy(scanning = true, error = null)
        scan = scope.launch {
            try {
                val devices = discovery.scan()
                stateMutable.value = stateMutable.value.copy(devices = devices)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "没有找到音箱，请检查 Wi-Fi")
            } finally {
                stateMutable.value = stateMutable.value.copy(scanning = false)
            }
        }
    }

    suspend fun select(device: DlnaDevice) {
        if (stateMutable.value.connecting) return
        stateMutable.value = stateMutable.value.copy(connecting = true, error = null)
        try {
            val network = discovery.wifi() ?: throw IllegalStateException("请先连接与音箱相同的 Wi-Fi")
            val client = DlnaClient(device, discovery.http(network))
            client.prepare()
            val address = discovery.address(network) ?: throw IllegalStateException("当前 Wi-Fi 没有可用地址")
            val relay = LocalAudioRelay(address)
            active.stop()
            local.setRate(1f)
            remote.attach(client, relay)
            remote.setVolume(volume)
            active = remote
            stateMutable.value = stateMutable.value.copy(device = device, seekControlAvailable = client.seekSupported, volumeControlAvailable = client.volumeSupported)
        } finally {
            stateMutable.value = stateMutable.value.copy(connecting = false)
        }
    }

    fun usePhone() {
        if (active === remote) remote.detach()
        active = local
        local.setRate(1f)
        local.setVolume(volume)
        releaseWakeLock()
        stateMutable.value = stateMutable.value.copy(device = null, error = null, seekControlAvailable = true, volumeControlAvailable = true)
    }

    fun showError(message: String) { stateMutable.value = stateMutable.value.copy(error = message) }

    override fun load(direct: DirectSource?, proxyUrl: String, startPositionMs: Long) {
        if (active === remote && wakeRenewal?.isActive != true) {
            // Bound each acquisition so cancellation also has an OS-enforced cleanup deadline.
            wakeRenewal = scope.launch {
                while (isActive && active === remote) {
                    wakeLock.acquire(10 * 60_000L)
                    delay(5 * 60_000L)
                }
            }
        }
        active.load(direct, proxyUrl, startPositionMs)
    }
    override fun loadVideo(source: VideoSource, startPositionMs: Long) {
        // Split video/audio sources are not supported by the audio DLNA route.
        usePhone()
        local.loadVideo(source, startPositionMs)
    }
    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)
    override fun setRate(rate: Float) = active.setRate(if (isRemoteOutput) 1f else rate)
    override fun setVolume(volume: Float) { this.volume = volume; active.setVolume(volume) }
    override val positionMs: Long get() = active.positionMs
    override val bufferedPositionMs: Long get() = active.bufferedPositionMs
    override val isPlaying: Boolean get() = active.isPlaying
    override val usingProxy: Boolean get() = active.usingProxy
    override val isRemoteOutput: Boolean get() = active === remote
    override val supportsSeeking: Boolean get() = active.supportsSeeking
    override fun stop() { active.stop(); releaseWakeLock() }
    override fun release() {
        scan?.cancel()
        remote.release()
        local.release()
        releaseWakeLock()
    }
    private fun releaseWakeLock() {
        wakeRenewal?.cancel()
        wakeRenewal = null
        if (wakeLock.isHeld) wakeLock.release()
    }
}
