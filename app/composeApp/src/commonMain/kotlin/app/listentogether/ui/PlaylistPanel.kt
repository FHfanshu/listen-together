package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.TrackRef
import app.listentogether.playlist.PlaylistPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

@Composable
internal fun PlaylistTabs(selected: Boolean, onSearch: () -> Unit, onPlaylists: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onSearch) { Text("搜索", color = if (!selected) Palette.Accent else Palette.Muted) }
        TextButton(onClick = onPlaylists) { Text("歌单", color = if (selected) Palette.Accent else Palette.Muted) }
    }
}

@Composable
internal fun PlaylistPanel(
    controller: AppController,
    ui: UiActions,
    initialLink: String,
    added: Set<String>,
    onAdded: (TrackRef) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val saved by controller.playlists.saved.collectAsState()
    val room by controller.room.collectAsState()
    var link by remember { mutableStateOf(initialLink) }
    var preview by remember { mutableStateOf<PlaylistPreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    val queued = room?.queue.orEmpty().mapTo(HashSet()) { it.track.key } + added
    val current = preview
    val available = current?.tracks.orEmpty().filterNot { it.key in queued }.distinctBy { it.key }
    val chosen = available.filter { it.key in selected }

    fun resolve(value: String) {
        if (value.isBlank() || adding) return
        // Manual pasting/opening also consumes the link, before clipboard auto-detection can run.
        ClipMemory.takeOnce(value.trim())
        loadJob?.cancel()
        link = value
        loading = true
        error = null
        selecting = false
        selected = emptySet()
        loadJob = scope.launch {
            try {
                val result = withTimeout(45_000) { controller.playlist(value) }
                preview = result
                if (saved.any { it.key == result.saved().key }) controller.playlists.save(result.saved())
            } catch (_: TimeoutCancellationException) {
                error = "歌单读取超时，请稍后重试"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describeError(e)
            } finally { loading = false }
        }
    }

    fun add(tracks: List<TrackRef>) {
        if (adding || loading || tracks.isEmpty()) return
        adding = true
        addedCount = 0
        error = null
        addJob = scope.launch {
            try {
                for (track in tracks) {
                    if (controller.room.value?.queue.orEmpty().any { it.track.key == track.key }) {
                        selected = selected - track.key
                        continue
                    }
                    // Room messages share a 5/s limit with sync/status traffic.
                    if (addedCount > 0) delay(350)
                    controller.addSong(track)
                    onAdded(track)
                    selected = selected - track.key
                    addedCount++
                }
                if (selected.isEmpty()) selecting = false
                ui.toast("已点 $addedCount 首")
            } catch (e: CancellationException) {
                ui.toast("已点 $addedCount 首")
                throw e
            } catch (e: Exception) {
                error = "已确认点入 $addedCount 首。${describeError(e)}，请查看房间队列后重试。"
            } finally { adding = false }
        }
    }

    fun back() {
        loadJob?.cancel()
        addJob?.cancel()
        if (preview != null && initialLink.isBlank()) { preview = null; selecting = false; selected = emptySet(); error = null }
        else onBack()
    }
    PlatformBackHandler { back() }
    LaunchedEffect(Unit) { if (initialLink.isNotBlank()) resolve(initialLink) }

    Column(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onClick = { back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Palette.Text) }
            Text("点歌", modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("回房间") }
        }
        if (current == null) {
            PlaylistTabs(true, onSearch = onBack, onPlaylists = {})
            OutlinedTextField(
                value = link, onValueChange = { link = it }, enabled = !loading,
                placeholder = { Text("粘贴网易云 / QQ 歌单链接或分享文案") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), maxLines = 3,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("支持公开歌单，收藏保存在本机", Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { resolve(link) }, enabled = !loading && link.isNotBlank()) { Text("打开歌单") }
            }
        } else {
            val info = current.saved()
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(info.coverTrack(), 72.dp, corner = 12.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(info.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(info.sourceName, info.creator).filter { it.isNotBlank() }.joinToString(" · "), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    Text("读取 ${current.tracks.size} / ${current.total} 首", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val isSaved = saved.any { it.key == info.key }
                TextButton(onClick = {
                    if (isSaved) controller.playlists.remove(info.key) else controller.playlists.save(info)
                    ui.toast(if (isSaved) "已取消收藏" else "已收藏到本机")
                }) { Text(if (isSaved) "已收藏" else "收藏歌单") }
                TextButton(onClick = { resolve(current.url) }, enabled = !loading && !adding) { Text("刷新") }
                TextButton(onClick = { selecting = !selecting; selected = emptySet() }, enabled = !loading && !adding) { Text(if (selecting) "取消多选" else "多选") }
            }
            if (!current.complete) Text("歌曲未全部读到，可能有权限限制或部分详情暂时不可用。可刷新重试，已读歌曲可以点歌。",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Palette.Accent, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        error?.let { Text(it, Modifier.padding(16.dp), color = Palette.Accent, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (current == null) {
                if (saved.isEmpty()) item {
                    Text("把常听的歌单收藏在这里，下次直接挑歌。", Modifier.padding(vertical = 28.dp), color = Palette.Muted)
                }
                items(saved, key = { it.key }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !loading) { resolve(playlist.url) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(playlist.coverTrack(), 52.dp, corner = 10.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.sourceName} · ${playlist.total} 首", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { controller.playlists.remove(playlist.key) }) { Icon(Icons.Rounded.Close, "取消收藏") }
                    }
                }
            } else itemsIndexed(current.tracks, key = { index, track -> "$index:${track.key}" }) { _, track ->
                TrackRow(track, track.key in queued, selection = if (selecting) track.key in selected else null,
                    enabled = !adding && !loading && (!selecting || track.key !in queued), onAdd = {
                        if (selecting) selected = if (track.key in selected) selected - track.key else selected + track.key
                        else add(listOf(track))
                    })
            }
        }
        if (adding) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已点 $addedCount 首…", Modifier.weight(1f), color = Palette.Muted)
            TextButton(onClick = { addJob?.cancel() }) { Text("停止") }
        } else if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = if (chosen.size == available.size) emptySet() else available.mapTo(HashSet()) { it.key } }, enabled = !loading) { Text(if (chosen.size == available.size && chosen.isNotEmpty()) "清空" else "全选可点歌曲") }
            TextButton(onClick = { add(chosen) }, enabled = chosen.isNotEmpty() && !loading) { Text("点入房间 · ${chosen.size} 首") }
        }
    }
}
