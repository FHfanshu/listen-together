package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.MemberView
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.Snapshot
import kotlinx.coroutines.delay

/**
 * 收起房间后贴在底部的播放栏：点它回到房间，× 退出房间（会先确认）。
 * 顶上一条细进度线，每秒刷新一次就够了。
 */
@Composable
fun MiniPlayer(controller: AppController, room: Snapshot, onOpen: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val pb = room.playback
    val track = pb.item?.track
    val online = room.members.count { it.online }
    val playing = pb.status == PlaybackStatus.PLAYING
    val interrupted by controller.interrupted.collectAsState()
    var now by remember { mutableLongStateOf(controller.serverNowMs()) }
    LaunchedEffect(pb.seq, pb.status) {
        while (playing) {
            now = controller.serverNowMs()
            delay(1_000)
        }
    }
    val progress = if (track != null && playing && pb.startAt != null) {
        ((now - pb.startAt!!).toFloat() / track.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    } else 0f

    val press = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 10.dp, vertical = 8.dp)
            .pressScale(press, 0.98f).shadow(18.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(Palette.SurfaceHigh).pressClick(press, onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track, 44.dp, corner = 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    track?.title ?: "房间 ${room.roomCode}", fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    EqualizerBars(playing && !interrupted, Palette.Accent, size = 11.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (interrupted) "播放被打断了" else (track?.artistText?.let { "$it · " } ?: "等大家点歌 · ") + "$online 人在听",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (interrupted) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Palette.AccentSoft).clickable(onClick = controller::resumePlayback),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "恢复播放", tint = Palette.Accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(4.dp))
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onLeave),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "退出房间", tint = Palette.Muted, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.06f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Palette.AccentGradient))
        }
    }
}

/** 退出房间前确认：退出后不再同步播放，前台通知也会消失。 */
@Composable
fun LeaveRoomDialog(roomCode: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("退出房间 $roomCode？") },
        text = { Text("退出后就不再和大家同步播放了，随时可以从大厅再进来。", color = Palette.Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("退出", color = Palette.Accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("再听会儿", color = Palette.Text) } },
    )
}

/**
 * 点头像堆弹出：谁在听、谁暂时离开了。
 * 画在房间界面同一层里（遮罩 + 从底部滑上来的面板），不另开窗口，系统栏颜色不会跳。
 */
/** 在听 / 在看的人。放映厅里管理员可以在这里禁言别人。 */
@Composable
fun ListenersSheet(controller: AppController, room: Snapshot, me: String, visible: Boolean, onDismiss: () -> Unit, ui: UiActions? = null) {
    val (online, away) = room.members.sortedBy { it.joinedAt }.partition { it.online }
    BottomSheet(visible, onDismiss) {
        ListenersList(controller, ui, room, online, away, me)
    }
}

/** 房间里的底部面板：半透明遮罩 + 从底部滑出的圆角面板，点遮罩或返回关闭。 */
@Composable
fun BottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PlatformBackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { it },
            exit = slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Palette.Surface)
                    // 吃掉面板上的点击，免得穿透到遮罩把自己关掉
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
                        .size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Palette.Outline),
                )
                content()
            }
        }
    }
}

@Composable
private fun ListenersList(controller: AppController, ui: UiActions?, room: Snapshot, online: List<MemberView>, away: List<MemberView>, me: String) {
    val verb = if (room.isVideo) "看" else "听"
    // 每 5 秒刷一下，禁言到期后标签自己消失。
    var now by remember { mutableLongStateOf(controller.serverNowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = controller.serverNowMs()
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        item { Text("在${verb}的人 · ${online.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        items(online, key = { it.userId }) { m -> ListenerRow(controller, ui, room, m, me, now) }
        if (away.isNotEmpty()) {
            item {
                Text(
                    "暂时离开 · ${away.size}", style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(away, key = { it.userId }) { m -> ListenerRow(controller, ui, room, m, me, now, Modifier.alpha(0.5f)) }
        }
    }
}

@Composable
private fun ListenerRow(
    controller: AppController,
    ui: UiActions?,
    room: Snapshot,
    m: MemberView,
    me: String,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val video = room.video
    val mutedUntil = video?.mutedUntil(m.userId, now)
    // 管理员能禁言除自己和其他管理员以外的人。
    val canMute = ui != null && video != null && video.isAdmin(me) && m.userId != me && !video.isAdmin(m.userId)
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(m.userId, m.nickname, m.avatar, 44.dp, controller.avatarUrl(m.userId, m.avatar))
            if (m.online) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(Palette.Surface).padding(2.dp)
                        .clip(CircleShape).background(Palette.Online),
                )
            }
        }
        Text(
            m.nickname, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 14.dp),
        )
        if (video == null && m.userId == room.ownerId) Tag("房主", Palette.Accent)
        if (video?.isAdmin(m.userId) == true) Tag("管理员", Palette.Accent)
        if (mutedUntil != null) Tag(muteLabel(mutedUntil), Palette.Muted)
        if (m.userId == me) Tag("你", Palette.Muted)
        Spacer(Modifier.weight(1f))
        if (canMute) {
            Box {
                Text(
                    if (mutedUntil != null) "解除禁言" else "禁言",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Text,
                    modifier = Modifier.clip(CircleShape).background(Palette.SurfaceHigh)
                        .clickable {
                            if (mutedUntil != null) ui?.run { controller.mute(m.userId, 0) } else menu = true
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
                ) {
                    listOf(10 to "禁言 10 分钟", 60 to "禁言 1 小时", -1 to "一直禁言，直到解除").forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menu = false; ui?.run { controller.mute(m.userId, minutes) } },
                        )
                    }
                }
            }
        }
    }
}

private fun muteLabel(until: Long): String =
    if (until >= MUTE_FOREVER_MS) "禁言中" else "禁言到 ${wallClockText(until)}"

/** 服务器把「直到解除」存成 2100 年到期。 */
private const val MUTE_FOREVER_MS = 4_102_444_800_000L

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
