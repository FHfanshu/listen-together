package app.listentogether.playlist

import app.listentogether.KeyValueStore
import app.listentogether.model.TrackRef
import app.listentogether.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class SavedPlaylist(
    val source: String,
    val id: String,
    val url: String,
    val name: String,
    val creator: String = "",
    val coverUrl: String? = null,
    val total: Int = 0,
) {
    val key: String get() = "$source:$id"
    val sourceName: String get() = if (source == "netease") "网易云" else "QQ 音乐"
    fun coverTrack() = TrackRef(source, id, coverUrl = coverUrl)
}

@Serializable
data class PlaylistPreview(
    val source: String,
    val id: String,
    val url: String,
    val name: String,
    val creator: String = "",
    val coverUrl: String? = null,
    val total: Int = 0,
    val complete: Boolean = false,
    val tracks: List<TrackRef> = emptyList(),
) {
    fun saved() = SavedPlaylist(source, id, url, name, creator, coverUrl, total)
}

/** Save references only; opening a saved list reads its current public contents again. */
class PlaylistLibrary(private val store: KeyValueStore) {
    private val serializer = ListSerializer(SavedPlaylist.serializer())
    private val mutable = MutableStateFlow(runCatching {
        store.get(KEY)?.let { AppJson.decodeFromString(serializer, it) }.orEmpty()
    }.getOrDefault(emptyList()))
    val saved: StateFlow<List<SavedPlaylist>> = mutable

    fun save(playlist: SavedPlaylist) = update(listOf(playlist) + mutable.value.filterNot { it.key == playlist.key })
    fun remove(key: String) = update(mutable.value.filterNot { it.key == key })
    private fun update(value: List<SavedPlaylist>) {
        store.set(KEY, AppJson.encodeToString(serializer, value))
        mutable.value = value
    }
    private companion object { const val KEY = "public_playlists_v1" }
}

/** A routing hint for pasted content. The server validates hosts, IDs and redirects. */
object PlaylistLinks {
    private val link = Regex("""(?i)https?://(?:163cn\.tv/|(?:y\.)?music\.163\.com/(?:\#/)?(?:m/)?playlist(?:[/?])|(?:[a-z0-9-]+\.)?y\.qq\.com/(?:n/(?:ryqq|yqq)/playlist/|[^\s]*?(?:taoge|playlist)\.html\?|base/fcgi-bin/u\?))""")
    fun contains(text: String): Boolean = link.containsMatchIn(text)
}
