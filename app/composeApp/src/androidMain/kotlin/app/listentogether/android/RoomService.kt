package app.listentogether.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.listentogether.R
import app.listentogether.android.output.MusicMediaSession
import app.listentogether.model.PlaybackStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 在房间里就一直是前台服务，常驻一条通知。
 *
 * 不能只在"正在播放"时才前台：切歌时播放器会先停下再加载下一首，这一瞬间 App 掉回普通后台，
 * 系统会冻结进程或断网，下一首就再也放不出来。所以以"在不在房间"为准，而不是"有没有在响"。
 */
class RoomService : Service() {
    private val scope = MainScope()
    private val app get() = application as ListenApp
    private lateinit var media: MusicMediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        media = MusicMediaSession(app, scope)
        ensureChannel(this)
        // 先用占位内容立即进前台（必须在 5 秒内），再跟着房间状态刷新。
        startInForeground(build(NotiState()))
        val c = app.controller
        // 被系统重建时房间要等长连接恢复后才有；从没见过房间就先别停，等一会儿还没有再停。
        var seen = false
        scope.launch {
            delay(RESTORE_GRACE_MS)
            if (!seen) stopSelf()
        }
        scope.launch {
            combine(c.room, c.muted, c.interrupted) { room, muted, interrupted ->
                val pb = room?.playback
                NotiState(
                    room?.roomCode, pb?.item?.track?.let { "${it.title} · ${it.artistText}" }, muted,
                    room?.members?.count { it.online } ?: 0, interrupted,
                    video = room?.isVideo == true, paused = pb?.status == PlaybackStatus.PAUSED,
                )
            }.distinctUntilChanged().collect { st ->
                val code = st.code
                if (code == null) {
                    if (seen) stopSelf()
                } else {
                    seen = true
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, build(st))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> app.controller.toggleMute()
            ACTION_RESUME -> app.controller.resumePlayback()
            ACTION_LEAVE -> app.appScope.launch { runCatching { app.controller.leaveRoom() } }
        }
        // 被系统杀掉后重建：ListenApp 会恢复上次的房间。
        return START_STICKY
    }

    private fun startInForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private data class NotiState(
        val code: String? = null,
        val nowPlaying: String? = null,
        val muted: Boolean = false,
        val online: Int = 0,
        val interrupted: Boolean = false,
        // 放映厅切到后台只放声音，通知里按「看」来说。
        val video: Boolean = false,
        val paused: Boolean = false,
    )

    private fun build(st: NotiState): Notification {
        val (code, nowPlaying, muted, online, interrupted, video, paused) = st
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = nowPlaying ?: if (video) "等大家点视频" else "等大家点歌"
        val people = "${online.coerceAtLeast(1)} 人在${if (video) "看" else "听"}"
        val sub = when {
            code == null -> "一起听"
            interrupted -> "播放被打断了，点「恢复播放」接着听"
            video && paused -> "放映厅 $code · 已暂停 · $people"
            video -> "放映厅 $code · 后台只放声音 · $people"
            else -> "房间 $code · $people"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(title)
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(0xFFFF4D5E.toInt())
            .apply { if (interrupted) addAction(0, "恢复播放", action(ACTION_RESUME, 3)) }
            .addAction(0, if (muted) "取消静音" else "静音", action(ACTION_MUTE, 1))
            .addAction(0, "退出房间", action(ACTION_LEAVE, 2))
            .build()
        return if (app.controller.room.value?.isVideo == false) {
            Notification.Builder.recoverBuilder(this, notification).setStyle(media.notificationStyle()).build()
        } else notification
    }

    private fun action(name: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RoomService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        media.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "room"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_MUTE = "mute"
        private const val ACTION_LEAVE = "leave"
        private const val ACTION_RESUME = "resume"
        private const val RESTORE_GRACE_MS = 20_000L

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val ch = NotificationChannel(CHANNEL_ID, "一起听房间", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在房间里时常驻，保证后台切歌也能继续播放"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** 进房间时调用；App 在后台时系统不允许启动前台服务，失败了就等下次回到前台再试。 */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RoomService::class.java)) }
        }
    }
}
