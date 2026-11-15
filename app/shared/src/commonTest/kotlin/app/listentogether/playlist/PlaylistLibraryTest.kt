package app.listentogether.playlist

import app.listentogether.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistLibraryTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    @Test fun savedReferencesSurviveReopeningAndRefreshWithoutDuplicates() {
        val store = MemoryStore()
        val library = PlaylistLibrary(store)
        val playlist = SavedPlaylist("netease", "123", "https://music.163.com/playlist?id=123", "Original")
        library.save(playlist)
        library.save(playlist.copy(name = "Updated", total = 200))
        library.save(playlist.copy(source = "qq", url = "https://y.qq.com/n/ryqq/playlist/123"))
        val reopened = PlaylistLibrary(store)
        assertEquals(2, reopened.saved.value.size)
        assertEquals("Updated", reopened.saved.value.last().name)
        reopened.remove("netease:123")
        assertEquals(listOf("qq:123"), PlaylistLibrary(store).saved.value.map { it.key })
    }

    @Test fun corruptedSavedDataDoesNotBreakAppStartup() {
        val store = MemoryStore()
        store.values["public_playlists_v1"] = "{broken"
        assertTrue(PlaylistLibrary(store).saved.value.isEmpty())
    }

    @Test fun playlistRoutingRecognizesSharingFormatsWithoutHijackingSongLinks() {
        for (text in listOf(
            "分享 https://music.163.com/#/playlist?id=123 给你",
            "https://y.music.163.com/m/playlist?id=123",
            "https://music.163.com/playlist/123/456/?userid=789",
            "https://163cn.tv/abc",
            "https://y.qq.com/n/ryqq/playlist/123",
            "https://i2.y.qq.com/n3/other/pages/details/playlist.html?id=123",
            "https://c6.y.qq.com/base/fcgi-bin/u?__=abc",
        )) assertTrue(PlaylistLinks.contains(text), text)
        for (text in listOf("晴天", "https://music.163.com/song?id=123", "https://y.qq.com/n/ryqq/songDetail/abc", "https://evil.test/playlist?id=123")) {
            assertFalse(PlaylistLinks.contains(text), text)
        }
    }
}
