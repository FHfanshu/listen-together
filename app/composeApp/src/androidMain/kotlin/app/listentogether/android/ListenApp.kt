package app.listentogether.android

import android.app.Application
import app.listentogether.AppController
import app.listentogether.android.output.AudioOutputRouter
import app.listentogether.output.DlnaTrack
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 播放器和长连接跟随进程而不是 Activity，切到后台也继续同步播放。 */
class ListenApp : Application() {
    val appScope = MainScope()
    lateinit var player: ExoAudioPlayer
        private set
    lateinit var controller: AppController
        private set
    lateinit var output: AudioOutputRouter
        private set

    override fun onCreate() {
        super.onCreate()
        player = ExoAudioPlayer(this)
        output = AudioOutputRouter(this, player, appScope) {
            val track = controller.room.value?.playback?.item?.track
            DlnaTrack(track?.title ?: "一起听", track?.artistText.orEmpty(), track?.durationMs ?: 0, track?.coverUrl)
        }
        controller = AppController(PrefsStore(this), output, appScope)
        // 进了房间就挂上前台服务；退出房间后服务自己停。
        appScope.launch {
            controller.room.map { it?.roomCode }.distinctUntilChanged().collect { code ->
                if (code != null) RoomService.start(this@ListenApp)
            }
        }
    }
}
