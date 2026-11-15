package app.listentogether.net

import app.listentogether.model.Activity
import app.listentogether.model.ChatMessage
import app.listentogether.model.ControlResult
import app.listentogether.model.VideoUpdate
import app.listentogether.model.LikeEvent
import app.listentogether.model.MembersUpdate
import app.listentogether.model.Notice
import app.listentogether.model.PlaybackUpdate
import app.listentogether.model.QueueUpdate
import app.listentogether.model.Snapshot
import app.listentogether.sync.ClockSync
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** 服务器 ack 里的错误，code 见规格“错误码”表。 */
class RoomError(val code: String, message: String, val itemId: Int? = null) : Exception(message.ifEmpty { code })

sealed interface SocketEvent {
    data class Connected(val userId: String, val nickname: String, val avatar: String, val roomCode: String?, val roomError: RoomError?) : SocketEvent
    data object Disconnected : SocketEvent
    data object AuthFailed : SocketEvent
    data class SnapshotReceived(val snapshot: Snapshot) : SocketEvent
    data class QueueChanged(val update: QueueUpdate) : SocketEvent
    data class PlaybackChanged(val update: PlaybackUpdate) : SocketEvent
    data class MembersChanged(val update: MembersUpdate) : SocketEvent
    data class NoticeReceived(val notice: Notice) : SocketEvent
    data class LikeReceived(val like: LikeEvent) : SocketEvent
    data class ActivityAdded(val activity: Activity) : SocketEvent
    data class Kicked(val roomCode: String) : SocketEvent
    data class VideoChanged(val update: VideoUpdate) : SocketEvent
    data class ChatAdded(val message: ChatMessage) : SocketEvent
    data class ControlResultReceived(val result: ControlResult) : SocketEvent
}

/**
 * 与服务器的 WebSocket 长连接：首条 hello 鉴权并（重连时）回到原房间，之后收发请求和广播。
 * 断线后按 1s、2s、4s… 最长 10s 退避重连；每次连上先连续对时几次，之后每 20 秒对时一次。
 */
class RoomSocket(
    private val client: HttpClient,
    private val url: String,
    private val token: () -> String?,
    private val clock: ClockSync,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<SocketEvent> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 重连时 hello 里带上的房间号；进出房间时由上层更新。 */
    var desiredRoom: String? = null

    private var loop: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private val pendingLock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var nextId = 0

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        scope.launch { session?.close() }
    }

    /** 发送请求并等待 ack，失败时抛 [RoomError]。返回 ack 的 d 字段。 */
    suspend fun request(type: String, data: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 10_000): JsonElement {
        val s = session ?: throw RoomError("OFFLINE", "未连接服务器")
        val deferred = CompletableDeferred<JsonObject>()
        val id = pendingLock.withLock {
            val id = (++nextId).toString()
            pending[id] = deferred
            id
        }
        try {
            val msg = buildJsonObject {
                put("t", type)
                put("id", id)
                put("d", data)
            }
            s.send(Frame.Text(msg.toString()))
            val ack = withTimeout(timeoutMs) { deferred.await() }
            if (ack["ok"]?.jsonPrimitive?.content != "true") {
                val err = ack["err"] as? JsonObject
                throw RoomError(
                    code = err?.get("code")?.jsonPrimitive?.content ?: "UNKNOWN",
                    message = err?.get("msg")?.jsonPrimitive?.content.orEmpty(),
                    itemId = err?.get("itemId")?.jsonPrimitive?.intOrNull,
                )
            }
            return ack["d"] ?: JsonNull
        } finally {
            pendingLock.withLock { pending.remove(id) }
        }
    }

    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            var authFailed = false
            try {
                val s = client.webSocketSession(url)
                session = s
                coroutineScope {
                    val reader = launch {
                        for (frame in s.incoming) {
                            if (frame is Frame.Text) handle(frame.readText())
                        }
                    }
                    val hello = try {
                        request("hello", buildJsonObject {
                            put("token", token().orEmpty())
                            desiredRoom?.let { put("roomCode", it) }
                        }).jsonObject
                    } catch (e: RoomError) {
                        if (e.code == "UNAUTHORIZED") authFailed = true
                        throw e
                    }
                    clock.reset()
                    _connected.value = true
                    backoffMs = 1_000
                    val roomError = (hello["roomError"] as? JsonObject)?.let {
                        RoomError(it["code"]?.jsonPrimitive?.content ?: "UNKNOWN", it["msg"]?.jsonPrimitive?.content.orEmpty())
                    }
                    _events.emit(
                        SocketEvent.Connected(
                            userId = hello["userId"]?.jsonPrimitive?.content.orEmpty(),
                            nickname = hello["nickname"]?.jsonPrimitive?.content.orEmpty(),
                            avatar = hello["avatar"]?.jsonPrimitive?.content.orEmpty(),
                            roomCode = hello["roomCode"]?.jsonPrimitive?.content,
                            roomError = roomError,
                        ),
                    )
                    launch { syncClockLoop() }
                    reader.join()
                    throw RoomError("CLOSED", "连接已断开")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                session = null
                if (_connected.value) {
                    _connected.value = false
                    _events.tryEmit(SocketEvent.Disconnected)
                }
                pendingLock.withLock {
                    pending.values.forEach { it.completeExceptionally(RoomError("OFFLINE", "连接已断开")) }
                    pending.clear()
                }
            }
            if (authFailed) {
                _events.emit(SocketEvent.AuthFailed)
                return
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(10_000)
        }
    }

    private suspend fun syncClockLoop() {
        repeat(8) {
            syncOnce()
            delay(150)
        }
        while (true) {
            delay(20_000)
            syncOnce()
        }
    }

    private suspend fun syncOnce() {
        val t0 = clock.localNowMs()
        val d = runCatching { request("time_sync", buildJsonObject { put("t0", t0) }, timeoutMs = 5_000).jsonObject }.getOrNull() ?: return
        val t3 = clock.localNowMs()
        clock.addSample(t0, d["t1"]!!.jsonPrimitive.long, d["t2"]!!.jsonPrimitive.long, t3)
    }

    private suspend fun handle(text: String) {
        val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = msg["t"]?.jsonPrimitive?.content ?: return
        if (type == "ack") {
            val id = msg["id"]?.jsonPrimitive?.content ?: return
            pendingLock.withLock { pending[id] }?.complete(msg)
            return
        }
        val d = msg["d"] ?: return
        val event = runCatching {
            when (type) {
                "snapshot" -> SocketEvent.SnapshotReceived(AppJson.decodeFromJsonElement(d))
                "queue" -> SocketEvent.QueueChanged(AppJson.decodeFromJsonElement(d))
                "playback" -> SocketEvent.PlaybackChanged(AppJson.decodeFromJsonElement(d))
                "members" -> SocketEvent.MembersChanged(AppJson.decodeFromJsonElement(d))
                "notice" -> SocketEvent.NoticeReceived(AppJson.decodeFromJsonElement(d))
                "like" -> SocketEvent.LikeReceived(AppJson.decodeFromJsonElement(d))
                "activity" -> SocketEvent.ActivityAdded(AppJson.decodeFromJsonElement(d))
                "video" -> SocketEvent.VideoChanged(AppJson.decodeFromJsonElement(d))
                "chat" -> SocketEvent.ChatAdded(AppJson.decodeFromJsonElement(d))
                "control_result" -> SocketEvent.ControlResultReceived(AppJson.decodeFromJsonElement(d))
                "kicked" -> SocketEvent.Kicked(d.jsonObject["roomCode"]?.jsonPrimitive?.content.orEmpty())
                else -> null
            }
        }.getOrNull() ?: return
        _events.emit(event)
    }
}

