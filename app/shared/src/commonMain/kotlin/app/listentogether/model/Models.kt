package app.listentogether.model

import kotlinx.serialization.Serializable

// 与服务端 internal/lt/room/types.go、internal/lt/playurl 的 JSON 一一对应。

@Serializable
data class TrackRef(
    val source: String,
    val id: String,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long = 0,
    val coverUrl: String? = null,
) {
    val key: String get() = "$source:$id"
    val artistText: String get() = artists.joinToString(" / ")
}

@Serializable
data class QueueItem(
    val itemId: Int,
    val track: TrackRef,
    val requestedBy: String = "",
    val addedAt: Long = 0,
    val voterIds: List<String> = emptyList(),
)

object PlaybackStatus {
    const val IDLE = "idle"
    const val PREPARING = "preparing"
    const val PLAYING = "playing"
    /** 只出现在放映厅：停在 [PlaybackState.positionMs]。 */
    const val PAUSED = "paused"
}

object RoomKind {
    const val MUSIC = "music"
    const val VIDEO = "video"
}

@Serializable
data class PlaybackState(
    val status: String = PlaybackStatus.IDLE,
    val item: QueueItem? = null,
    val startAt: Long? = null,
    val skipperIds: List<String> = emptyList(),
    /** 当前歌收到的赞（同一人可以连点）和点过赞的人。 */
    val likes: Int = 0,
    val likerIds: List<String> = emptyList(),
    val seq: Int = 0,
    /** 放映厅暂停时停在哪里。 */
    val positionMs: Long? = null,
)

/** 房间记录的一条，昵称是发生时的昵称。 */
@Serializable
data class Activity(
    val id: Int,
    val at: Long = 0,
    /** add：推荐了歌；like：点赞；skip：歌被跳过（names 是投跳过票的人）。 */
    val kind: String = "",
    val userId: String = "",
    val nickname: String = "",
    val track: TrackRef,
    val names: List<String> = emptyList(),
) {
    companion object {
        const val ADD = "add"
        const val LIKE = "like"
        const val SKIP = "skip"
    }
}

/** 有人点了一次赞，所有人都收到，用来放特效。 */
@Serializable
data class LikeEvent(val userId: String, val seq: Int = 0, val count: Int = 1, val likes: Int = 0, val likerIds: List<String> = emptyList())

@Serializable
data class MemberView(
    val userId: String,
    val nickname: String = "",
    /** 空 = 按 userId 生成；"preset:N" = 内置头像；"photo:版本" = 上传的照片。 */
    val avatar: String = "",
    val online: Boolean = false,
    val joinedAt: Long = 0,
)

@Serializable
data class Snapshot(
    val roomCode: String,
    /** 旧服务器不带，按听歌房间。 */
    val kind: String = RoomKind.MUSIC,
    val ownerId: String = "",
    val members: List<MemberView> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val next: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
    val history: List<QueueItem> = emptyList(),
    /** 新的在前。 */
    val activity: List<Activity> = emptyList(),
    val rev: Int = 0,
    val serverNowMs: Long = 0,
    /** 以下只有放映厅有。 */
    val video: VideoState? = null,
    val chat: List<ChatMessage> = emptyList(),
) {
    val isVideo: Boolean get() = kind == RoomKind.VIDEO
}

/** 放映厅的进度控制档位。 */
object ControlPolicy {
    const val FREE = "free"
    const val REQUESTER = "requester"
    const val ADMINS = "admins"

    fun title(p: String) = when (p) {
        FREE -> "自由"
        REQUESTER -> "点播人做主"
        ADMINS -> "管理员做主"
        else -> p
    }

    fun description(p: String) = when (p) {
        FREE -> "所有人都能直接控制进度"
        REQUESTER -> "点了当前视频的人和管理员能直接控制"
        ADMINS -> "只有管理员能直接控制"
        else -> ""
    }
}

@Serializable
data class VideoState(
    /** 房间的档位；服主锁定时就是服主的默认档位。 */
    val policy: String = ControlPolicy.REQUESTER,
    val locked: Boolean = false,
    /** 有权限的人掉线兜底后实际生效的档位。 */
    val effective: String = ControlPolicy.REQUESTER,
    val adminIds: List<String> = emptyList(),
    val requests: List<ControlRequest> = emptyList(),
    /** 被禁言的人 -> 到期时间（服务器 Unix 毫秒）；2100 年以后表示要等管理员解除。 */
    val muted: Map<String, Long> = emptyMap(),
) {
    fun isAdmin(userId: String) = userId in adminIds

    /** 现在还在禁言就返回到期时间。 */
    fun mutedUntil(userId: String, serverNowMs: Long): Long? = muted[userId]?.takeIf { it > serverNowMs }

    /** 和服务器的判断一致：自由档、管理员、或点播人做主时自己点了当前视频。 */
    fun canControl(userId: String, requestedBy: String?): Boolean =
        effective == ControlPolicy.FREE || isAdmin(userId) ||
            (effective == ControlPolicy.REQUESTER && requestedBy == userId)
}

object ControlAction {
    const val PAUSE = "pause"
    const val PLAY = "play"
    const val SEEK = "seek"
    const val NEXT = "next"
}

@Serializable
data class ControlRequest(
    val id: Int,
    val userId: String = "",
    val nickname: String = "",
    val action: String = "",
    val positionMs: Long = 0,
    val expiresAt: Long = 0,
)

/**
 * 放映厅消息流里的一条：朋友弹幕（danmaku）、系统提示（system），
 * 或换到新视频时的分隔（video，text 是标题，nickname 是点播人）。at 是服务器时间。
 */
@Serializable
data class ChatMessage(
    val id: Int,
    val at: Long = 0,
    val kind: String = "",
    val userId: String = "",
    val nickname: String = "",
    val text: String = "",
) {
    val isDanmaku: Boolean get() = kind == "danmaku"
    val isVideoStart: Boolean get() = kind == "video"
}

@Serializable
data class VideoUpdate(val video: VideoState = VideoState(), val rev: Int = 0)

/** 自己（或别人）的控制请求有了结果：accepted / rejected / expired。 */
@Serializable
data class ControlResult(val requestId: Int = 0, val userId: String = "", val result: String = "", val byNickname: String = "")

@Serializable
data class VideoStream(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
data class VideoQuality(val qn: Int, val label: String = "")

/** /api/v1/video 的返回：视频轨和音轨分开两路。 */
@Serializable
data class VideoInfo(
    val video: VideoStream,
    val audio: VideoStream,
    val proxyVideo: String = "",
    val proxyAudio: String = "",
    val qn: Int = 0,
    val qualities: List<VideoQuality> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val expiresAt: Long = 0,
)

@Serializable
data class RoomSummary(
    val roomCode: String,
    val kind: String = RoomKind.MUSIC,
    val ownerNickname: String = "",
    val memberCount: Int = 0,
    val onlineCount: Int = 0,
    val nowPlaying: TrackRef? = null,
)

/** 网易云播客（以前叫电台）。 */
@Serializable
data class Podcast(
    val id: String,
    val name: String = "",
    val coverUrl: String? = null,
    val dj: String = "",
    val category: String = "",
    val programCount: Int = 0,
)

/** 播客节目的一页，从新到旧。 */
@Serializable
data class ProgramPage(
    val tracks: List<TrackRef> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
)

@Serializable
data class DirectSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long = 0,
)

@Serializable
data class PlayInfo(
    val direct: DirectSource? = null,
    val proxy: String = "",
    val durationMs: Long = 0,
    /** 实际拿到的音质：standard（320k）或 lossless。选了无损但这首没有时是 standard。 */
    val quality: String = AudioQuality.STANDARD,
)

object AudioQuality {
    const val STANDARD = "standard"
    const val LOSSLESS = "lossless"
}

@Serializable
data class JoinResult(val token: String, val userId: String, val nickname: String)

@Serializable
data class Me(val userId: String, val nickname: String, val avatar: String = "")

// 广播消息的 d 字段。
@Serializable
data class QueueUpdate(val queue: List<QueueItem> = emptyList(), val next: QueueItem? = null, val rev: Int = 0)

@Serializable
data class PlaybackUpdate(val playback: PlaybackState = PlaybackState(), val serverNowMs: Long = 0, val rev: Int = 0)

@Serializable
data class MembersUpdate(val members: List<MemberView> = emptyList(), val ownerId: String = "", val rev: Int = 0)

@Serializable
data class Notice(val kind: String = "", val text: String = "")
