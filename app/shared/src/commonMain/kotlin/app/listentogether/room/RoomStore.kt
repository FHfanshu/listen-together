package app.listentogether.room

import app.listentogether.model.Snapshot
import app.listentogether.net.SocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 当前房间的状态，只由服务器消息驱动：快照整体替换，queue / playback / members 广播各自覆盖对应部分。
 * 广播在同一条连接上按顺序到达；断线重连后服务器会重新发快照，所以不需要补齐缺失的 rev。
 */
class RoomStore {
    private companion object {
        const val ACTIVITY_MAX = 100
        const val CHAT_MAX = 50
    }

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state

    fun clear() {
        _state.value = null
    }

    fun apply(event: SocketEvent) {
        val cur = _state.value
        _state.value = when (event) {
            is SocketEvent.SnapshotReceived -> event.snapshot
            is SocketEvent.QueueChanged -> cur?.copy(queue = event.update.queue, next = event.update.next, rev = maxOf(cur.rev, event.update.rev))
            is SocketEvent.PlaybackChanged -> cur?.copy(playback = event.update.playback, rev = maxOf(cur.rev, event.update.rev))
            is SocketEvent.MembersChanged -> cur?.copy(members = event.update.members, ownerId = event.update.ownerId, rev = maxOf(cur.rev, event.update.rev))
            is SocketEvent.LikeReceived -> cur?.takeIf { it.playback.seq == event.like.seq }?.let {
                it.copy(playback = it.playback.copy(likes = event.like.likes, likerIds = event.like.likerIds))
            } ?: cur
            is SocketEvent.ActivityAdded -> cur?.takeIf { s -> s.activity.none { it.id == event.activity.id } }?.let {
                it.copy(activity = (listOf(event.activity) + it.activity).take(ACTIVITY_MAX))
            } ?: cur
            is SocketEvent.VideoChanged -> cur?.copy(video = event.update.video, rev = maxOf(cur.rev, event.update.rev))
            is SocketEvent.ChatAdded -> cur?.takeIf { s -> s.chat.none { it.id == event.message.id } }?.let {
                it.copy(chat = (it.chat + event.message).takeLast(CHAT_MAX))
            } ?: cur
            is SocketEvent.Kicked -> if (cur?.roomCode == event.roomCode) null else cur
            else -> cur
        }
    }
}
