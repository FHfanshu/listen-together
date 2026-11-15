package app.listentogether.android.output

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.listentogether.android.ListenApp
import app.listentogether.android.MainActivity
import app.listentogether.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Publish music metadata and transport controls to Android's media notifications and lock screen. */
class MusicMediaSession(private val app: ListenApp, scope: CoroutineScope) {
    private val session = MediaSession(app, "一起听")
    private var metadataKey: String? = null
    private var remote: Boolean? = null
    private val volume = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 100) {
        override fun onSetVolumeTo(value: Int) { Handler(Looper.getMainLooper()).post { app.controller.setVolume(value / 100f) } }
        override fun onAdjustVolume(direction: Int) { onSetVolumeTo((currentVolume + direction * 5).coerceIn(0, 100)) }
    }
    private val job: Job

    init {
        session.setSessionActivity(PendingIntent.getActivity(app, 70, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = app.controller.resumePlayback()
            override fun onPause() {
                app.output.pause()
                app.output.listener?.onInterrupted()
                publish()
            }
            override fun onStop() = onPause()
            override fun onSkipToNext() { app.appScope.launch { runCatching { app.controller.skip(true) } } }
            // Music room progress is shared. Do not advertise local system seeking as a room-wide control.
        }, Handler(Looper.getMainLooper()))
        job = scope.launch {
            while (isActive) { publish(); delay(1_000) }
        }
    }

    private fun publish() {
        val room = app.controller.room.value
        val track = room?.playback?.item?.track
        val enabled = room != null && !room.isVideo && track != null
        session.isActive = enabled
        if (!enabled) { metadataKey = null; return }
        track ?: return
        val key = "${track.key}:${room.playback.seq}"
        if (metadataKey != key) {
            metadataKey = key
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.key)
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artistText)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, track.coverUrl.orEmpty())
                .build())
        }
        val dlna = app.output.state.value.device != null
        if (remote != dlna) {
            remote = dlna
            if (dlna) session.setPlaybackToRemote(volume)
            else session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        }
        if (dlna) volume.setCurrentVolume((if (app.controller.muted.value) 0f else app.controller.volume.value).times(100).toInt())
        val state = when {
            room.playback.status == PlaybackStatus.PREPARING -> PlaybackState.STATE_BUFFERING
            app.output.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP)
            .setState(state, app.output.positionMs, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
    }

    fun notificationStyle(): Notification.MediaStyle = Notification.MediaStyle().setMediaSession(session.sessionToken)
    fun release() { job.cancel(); session.isActive = false; session.release() }
}
