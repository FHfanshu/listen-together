package app.listentogether.android

import android.app.Application
import app.listentogether.AppController
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

    override fun onCreate() {
        super.onCreate()
        player = ExoAudioPlayer(this)
        controller = AppController(PrefsStore(this), player, appScope)
        // 进了房间就挂上前台服务；退出房间后服务自己停。
        appScope.launch {
            controller.room.map { it?.roomCode }.distinctUntilChanged().collect { code ->
                if (code != null) RoomService.start(this@ListenApp)
            }
        }
    }
}
