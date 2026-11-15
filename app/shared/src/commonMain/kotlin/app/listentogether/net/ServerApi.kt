package app.listentogether.net

import app.listentogether.model.AudioQuality
import app.listentogether.model.JoinResult
import app.listentogether.model.Me
import app.listentogether.model.PlayInfo
import app.listentogether.model.Podcast
import app.listentogether.model.ProgramPage
import app.listentogether.model.RoomSummary
import app.listentogether.model.TrackRef
import app.listentogether.model.VideoInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true // 服务端 Go 的空切片会序列化成 null
    explicitNulls = false
}

/** 平台提供带具体引擎的 HttpClient。 */
expect fun createPlatformHttpClient(config: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient

fun createHttpClient(): HttpClient = createPlatformHttpClient {
    install(ContentNegotiation) { json(AppJson) }
    install(WebSockets)
    expectSuccess = false
}

@Serializable
data class ServerInfo(val serverId: String, val publicKey: String, val proof: String? = null)

class ApiException(val code: String, message: String, val status: Int = 0) : Exception(message)

/** 一起听服务器的 HTTP 接口（/api/v1）。 */
class ServerApi(
    private val client: HttpClient,
    baseUrl: String,
    private val token: () -> String?,
) {
    val baseUrl: String = baseUrl.trimEnd('/')
    private val api = "${this.baseUrl}/api/v1"

    /** 服务器身份（公开接口）；老版本服务器没有这个接口时返回 null。 */
    suspend fun info(nonce: String): ServerInfo? {
        val resp = client.get("$api/info") { parameter("nonce", nonce) }
        if (resp.status.value == 404) return null
        return resp.parse()
    }

    suspend fun join(inviteCode: String, nickname: String): JoinResult =
        client.post("$api/join") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(inviteCode.trim(), nickname.trim()))
        }.parse()

    suspend fun me(): Me = client.get("$api/me") { auth() }.parse()

    suspend fun updateProfile(nickname: String? = null, avatar: String? = null): Me =
        client.patch("$api/me") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ProfileRequest(nickname, avatar))
        }.parse()

    /** 上传压缩好的头像图片（JPEG/PNG/WebP，≤ 512KB）。 */
    suspend fun uploadAvatar(image: ByteArray): Me =
        client.put("$api/me/avatar") {
            auth()
            contentType(ContentType.Image.JPEG)
            setBody(image)
        }.parse()

    /** 照片头像地址；版本号变了 URL 就变，图片可以长期缓存。 */
    fun avatarUrl(userId: String, avatar: String): String? =
        avatar.removePrefix("photo:").takeIf { avatar.startsWith("photo:") }?.let { "$api/avatars/$userId?v=$it" }

    suspend fun rooms(): List<RoomSummary> = client.get("$api/rooms") { auth() }.parse<RoomsResponse>().rooms

    suspend fun search(query: String, source: String): List<TrackRef> =
        client.get("$api/search") {
            auth()
            parameter("q", query)
            parameter("source", source)
        }.parse<SearchResponse>().tracks

    suspend fun podcasts(query: String): List<Podcast> =
        client.get("$api/podcasts") {
            auth()
            parameter("q", query)
        }.parse<PodcastsResponse>().podcasts

    suspend fun programs(podcastId: String, offset: Int): ProgramPage =
        client.get("$api/podcasts/${podcastId.encodeURLPathPart()}/programs") {
            auth()
            parameter("offset", offset)
        }.parse()

    suspend fun play(track: TrackRef, refresh: Boolean = false, quality: String = AudioQuality.STANDARD): PlayInfo =
        client.get("$api/play/${track.source.encodeURLPathPart()}/${track.id.encodeURLPathPart()}") {
            auth()
            if (refresh) parameter("refresh", "1")
            if (quality != AudioQuality.STANDARD) parameter("quality", quality)
        }.parse()

    /** 歌词原文（LRC）；这首歌没有歌词时返回 null。 */
    suspend fun lyrics(track: TrackRef): String? {
        val resp = client.get("$api/lyrics/${track.source.encodeURLPathPart()}/${track.id.encodeURLPathPart()}") { auth() }
        if (resp.status.value == 404) return null
        if (!resp.status.isSuccess()) throw ApiException("HTTP_${resp.status.value}", "歌词加载失败", resp.status.value)
        return resp.bodyAsText().ifBlank { null }
    }

    /** 放映厅的视频轨和音轨。qn 是想要的画质（B 站编号），拿不到时服务器给不超过它的最高一档。 */
    suspend fun video(track: TrackRef, qn: Int? = null, refresh: Boolean = false): VideoInfo =
        client.get("$api/video/${track.id.encodeURLPathPart()}") {
            auth()
            parameter("source", track.source)
            qn?.let { parameter("qn", it) }
            if (refresh) parameter("refresh", "1")
        }.parse()

    suspend fun reportDirectFailed(track: TrackRef) {
        client.post("$api/play/${track.source.encodeURLPathPart()}/${track.id.encodeURLPathPart()}/direct_failed") { auth() }
    }

    /** 代理地址带上 token，播放器拉流时不方便加请求头。 */
    fun proxyUrl(info: PlayInfo): String = withToken(info.proxy)

    fun withToken(proxyPath: String): String {
        val path = if (proxyPath.startsWith("http")) proxyPath else baseUrl + proxyPath
        return path + (if ('?' in path) "&" else "?") + "token=" + (token() ?: "")
    }

    fun webSocketUrl(): String = baseUrl.replaceFirst("http", "ws") + "/api/v1/ws"

    private fun HttpRequestBuilder.auth() {
        token()?.let { bearerAuth(it) }
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        if (status.isSuccess()) return body()
        val text = bodyAsText()
        val (code, msg) = runCatching {
            val obj = AppJson.parseToJsonElement(text).jsonObject
            val err = obj["error"]?.jsonPrimitive?.content ?: "HTTP_${status.value}"
            err to (obj["msg"]?.jsonPrimitive?.content ?: err)
        }.getOrDefault("HTTP_${status.value}" to text.take(200))
        throw ApiException(code, msg, status.value)
    }

    @Serializable private data class ProfileRequest(val nickname: String? = null, val avatar: String? = null)
    @Serializable private data class JoinRequest(val inviteCode: String, val nickname: String)
    @Serializable private data class RoomsResponse(val rooms: List<RoomSummary> = emptyList())
    @Serializable private data class SearchResponse(val tracks: List<TrackRef> = emptyList())
    @Serializable private data class PodcastsResponse(val podcasts: List<Podcast> = emptyList())
}
