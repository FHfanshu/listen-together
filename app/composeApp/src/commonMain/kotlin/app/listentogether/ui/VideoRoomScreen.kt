package app.listentogether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlin.random.Random
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.listentogether.model.VideoState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.shadow
import app.listentogether.model.TrackRef
import app.listentogether.AppController
import app.listentogether.model.ChatMessage
import app.listentogether.model.ControlAction
import app.listentogether.model.ControlPolicy
import app.listentogether.model.ControlRequest
import app.listentogether.model.MemberView
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private enum class VideoTab { Chat, Queue }

/**
 * 放映厅，从上到下分层：
 * 1. 播放器（固定）：画面、弹幕、控制条；别人发来的控制请求浮在画面顶上。
 * 2. 信息条（固定）：标题、点播人、点赞、投票跳过、在看的人、当前档位。
 * 3. 标签（固定）：聊天 / 待播。
 * 4. 标签内容：只有这一块滚动，两个标签各滚各的。
 */
@Composable
fun VideoRoomScreen(controller: AppController, room: Snapshot, ui: UiActions, onMinimize: () -> Unit, onLeave: () -> Unit) {
    val session by controller.session.collectAsState()
    val connected by controller.connected.collectAsState()
    val me = session?.userId.orEmpty()
    var searching by remember { mutableStateOf(false) }
    var showListeners by remember { mutableStateOf(false) }
    var showSyncMode by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var danmakuOn by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(VideoTab.Chat) }
    // 在待播标签时有新弹幕，聊天标签上亮个小红点。
    var seenChatId by rememberSaveable { mutableIntStateOf(0) }
    val likeAnchor = remember { arrayOfNulls<Offset>(1) }
    val likeFlow = remember(controller) { controller.likes.map { it.userId } }

    // 在放映厅里复制了 B 站或 YouTube 链接：后台悄悄解析，解析出来在聊天区底部浮一张小卡片，点一下加到待播；
    // 不打开点视频面板、不暂停，20 秒没理会就自己收起。
    var clipTrack by remember { mutableStateOf<TrackRef?>(null) }
    val clip = rememberClipboardText()
    LaunchedEffect(clip) {
        val c = clip?.trim().orEmpty()
        val from = videoLinkSource(c)
        if (c.isEmpty() || from == null || !ClipMemory.takeOnce(c)) return@LaunchedEffect
        val found = runCatching { controller.search(c, from) }.getOrNull()?.firstOrNull() ?: return@LaunchedEffect
        val r = controller.room.value
        val queued = r != null && (r.playback.item?.track?.key == found.key || r.next?.track?.key == found.key || r.queue.any { it.track.key == found.key })
        if (!queued) clipTrack = found
    }
    LaunchedEffect(clipTrack) {
        if (clipTrack != null) {
            delay(20_000)
            clipTrack = null
        }
    }

    // 竖屏短视频：小窗变高，全屏不横过来，换成沉浸页。比例以本机解析到的视频宽高为准。
    val videoInfo by controller.videoInfo.collectAsState()
    val ratio = videoInfo?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    val portrait = ratio < PORTRAIT_RATIO
    FullscreenEffect(fullscreen, landscape = !portrait)
    val sheetOpen = showListeners || showSyncMode || showPolicy || showQuality
    PlatformBackHandler(enabled = !searching && !sheetOpen) { if (fullscreen) fullscreen = false else onMinimize() }
    if (searching) {
        SearchPanel(controller, ui, onClose = { searching = false }, videoOnly = true)
        return
    }

    val video = room.video
    val membersById = room.members.associateBy { it.userId }
    val online = room.members.filter { it.online }
    val photo = { m: MemberView -> controller.avatarUrl(m.userId, m.avatar) }
    val canControl = video?.canControl(me, room.playback.item?.requestedBy) ?: false
    val isAdmin = video?.isAdmin(me) ?: false
    val requests = if (canControl) video?.requests.orEmpty() else emptyList()
    val lastDanmakuId = room.chat.lastOrNull { it.isDanmaku }?.id ?: 0
    if (tab == VideoTab.Chat) seenChatId = maxOf(seenChatId, lastDanmakuId)

    if (fullscreen && portrait) {
        ShortVideoImmersive(
            controller, room, ui, me, canControl, danmakuOn, membersById, photo,
            onToggleDanmaku = { danmakuOn = !danmakuOn },
            onOpenQueue = { fullscreen = false; tab = VideoTab.Queue },
            onExit = { fullscreen = false },
        )
        return
    }
    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Player(controller, room, ui, me, canControl, danmakuOn, fullscreen = true, onToggleFullscreen = { fullscreen = false }, Modifier.fillMaxSize())
            RequestBanners(controller, ui, requests, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).widthIn(max = 420.dp))
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        // 横屏视频 16:9；竖屏视频按比例变高，但最多占屏幕一半多一点，下面还要留给弹幕和待播。
        val playerHeight by animateDpAsState(
            if (portrait) minOf(maxWidth / ratio, maxHeight * 0.56f) else maxWidth * 9f / 16f,
            label = "playerHeight",
        )
        Column(Modifier.fillMaxSize()) {
            TopBar(
                room, connected, online.size, danmakuOn, showDebug, showMenu,
                onMenu = { showMenu = it }, onMinimize = onMinimize,
                onQuality = { showQuality = true }, onToggleDanmaku = { danmakuOn = !danmakuOn },
                onSyncMode = { showSyncMode = true }, onToggleDebug = { showDebug = !showDebug },
                onRefresh = controller::refreshPlayback, onLeave = onLeave,
            )

            Box(Modifier.fillMaxWidth().height(playerHeight)) {
                Player(controller, room, ui, me, canControl, danmakuOn, fullscreen = false, onToggleFullscreen = { fullscreen = true }, Modifier.fillMaxSize())
                RequestBanners(controller, ui, requests, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            AnimatedVisibility(showDebug, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                DebugPanel(controller)
            }

            InfoBar(
                controller, room, ui, me, online, membersById, photo, canControl, isAdmin,
                onLikeAnchor = { likeAnchor[0] = it }, onShowListeners = { showListeners = true }, onShowPolicy = { showPolicy = true },
            )
            Tabs(
                tab, queueCount = room.queue.size + (if (room.next != null) 1 else 0),
                chatUnread = tab != VideoTab.Chat && lastDanmakuId > seenChatId,
                onSelect = { tab = it },
            )

            Box(Modifier.weight(1f).fillMaxWidth().background(Palette.Surface)) {
                when (tab) {
                    VideoTab.Chat -> ChatPane(room.chat, me, video, membersById, photo)
                    VideoTab.Queue -> QueuePane(controller, room, ui, me, membersById, photo)
                }
                ClipSuggestionHost(
                    clipTrack, Modifier.align(Alignment.BottomCenter),
                    onAdd = { t ->
                        clipTrack = null
                        ui.run {
                            controller.addSong(t)
                            ui.toast("已加到待播：${t.title}")
                        }
                    },
                    onDismiss = { clipTrack = null },
                )
            }
            when (tab) {
                VideoTab.Chat -> DanmakuInput(controller, ui, video?.muted?.get(me), canControl = canControl, hasVideo = room.playback.item != null)
                VideoTab.Queue -> PickBar(onPick = { searching = true })
            }
        }

        LikeBurstLayer(likeFlow, anchor = { likeAnchor[0] })
        ListenersSheet(controller, room, me, visible = showListeners, onDismiss = { showListeners = false }, ui = ui)
        SyncModeSheet(controller, visible = showSyncMode, onDismiss = { showSyncMode = false })
        PolicySheet(controller, ui, room, isAdmin, visible = showPolicy, onDismiss = { showPolicy = false })
        QualitySheet(controller, visible = showQuality, onDismiss = { showQuality = false })
    }
}

@Composable
private fun TopBar(
    room: Snapshot,
    connected: Boolean,
    onlineCount: Int,
    danmakuOn: Boolean,
    showDebug: Boolean,
    showMenu: Boolean,
    onMenu: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onQuality: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onSyncMode: () -> Unit,
    onToggleDebug: () -> Unit,
    onRefresh: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(onClick = onMinimize) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", tint = Palette.Text)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("放映厅 ${room.roomCode}", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(connected)
                Text(
                    if (connected) "$onlineCount 人在看" else "正在重连…",
                    style = VType.Meta, color = Palette.Muted,
                )
            }
        }
        Box {
            RoundIcon(onClick = { onMenu(true) }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Palette.Text)
            }
            DropdownMenu(
                expanded = showMenu, onDismissRequest = { onMenu(false) },
                containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
            ) {
                val item = @Composable { text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, action: () -> Unit ->
                    DropdownMenuItem(
                        text = { Text(text, color = color) },
                        leadingIcon = { Icon(icon, contentDescription = null, tint = if (color == Palette.Text) Palette.Muted else color) },
                        onClick = { onMenu(false); action() },
                    )
                }
                item("画质", Icons.Rounded.HighQuality, Palette.Text, onQuality)
                item(if (danmakuOn) "关闭弹幕" else "打开弹幕", if (danmakuOn) Icons.Rounded.SubtitlesOff else Icons.Rounded.Subtitles, Palette.Text, onToggleDanmaku)
                item("同步档位", Icons.Rounded.Sync, Palette.Text, onSyncMode)
                item(if (showDebug) "关闭同步调试" else "同步调试", Icons.Rounded.GraphicEq, Palette.Text, onToggleDebug)
                item("刷新播放", Icons.Rounded.Refresh, Palette.Text, onRefresh)
                item("退出放映厅", Icons.AutoMirrored.Rounded.Logout, Palette.Accent, onLeave)
            }
        }
    }
}

/** 画面 + 弹幕 + 点一下出现的控制条。 */
@Composable
private fun Player(
    controller: AppController,
    room: Snapshot,
    ui: UiActions,
    me: String,
    canControl: Boolean,
    danmakuOn: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val pb = room.playback
    val duration = pb.item?.track?.durationMs ?: 0
    var showControls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    // 拖进度条时先只改本地显示，松手才发给服务器。
    var dragging by remember { mutableStateOf<Float?>(null) }
    val interrupted by controller.interrupted.collectAsState()
    val videoError by controller.videoError.collectAsState()
    // 全屏弹幕框：文字放在外面，控制条收起也不丢；输入框有焦点或有字时控制条不自己收。
    var fsText by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(pb) {
        while (true) {
            position = controller.videoPositionMs()
            delay(250)
        }
    }
    // 播放中控制条 4 秒后自己收起；暂停时一直显示。
    LaunchedEffect(showControls, pb.status, dragging, typing, fsText.isEmpty()) {
        if (showControls && pb.status == PlaybackStatus.PLAYING && dragging == null && !typing && fsText.isEmpty()) {
            delay(4_000)
            showControls = false
        }
    }

    Box(
        modifier.background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                // 正在打弹幕时点画面先收键盘，不切控制条。
                if (typing) focus.clearFocus() else showControls = !showControls
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pb.item != null) VideoSurface(Modifier.fillMaxSize())
        if (danmakuOn) DanmakuLayer(controller, Modifier.fillMaxSize())

        when {
            pb.item == null -> Text("还没有视频，到「待播」里点一个", color = Palette.Muted, style = VType.Small)
            // 本机解析失败（多半是 YouTube 连不上）：告诉这个人为什么只有自己看不了。
            videoError != null -> Text(
                videoError.orEmpty(), color = Color.White, style = VType.Small,
                modifier = Modifier.padding(24.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            pb.status == PlaybackStatus.PREPARING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text("等大家都缓冲好就开播", color = Palette.Text, style = VType.Meta, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (interrupted) {
            Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { InterruptedBar(onResume = controller::resumePlayback) }
        }

        AnimatedVisibility(
            showControls && pb.item != null && pb.status != PlaybackStatus.PREPARING,
            enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                val paused = pb.status == PlaybackStatus.PAUSED
                val press = remember { MutableInteractionSource() }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.pressScale(press, 0.9f).size(56.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .pressClick(press) { ui.run { controller.control(if (paused) ControlAction.PLAY else ControlAction.PAUSE) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (paused) "继续" else "暂停", tint = Color.White, modifier = Modifier.size(32.dp),
                        )
                    }
                    if (!canControl) {
                        Text(
                            "点了会发请求",
                            color = Color.White.copy(alpha = 0.75f), style = VType.Meta,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: position
                    Text("${formatTime(shown)} / ${formatTime(duration)}", color = Color.White, style = VType.Meta)
                    Slider(
                        value = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = dragging?.let { (it * duration).toLong() }
                            dragging = null
                            if (target != null) ui.run { controller.control(ControlAction.SEEK, target) }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White, activeTrackColor = Palette.Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { ui.run { controller.control(ControlAction.NEXT) } }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "切到下一个", tint = Color.White)
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggleFullscreen), contentAlignment = Alignment.Center) {
                        Icon(
                            if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = if (fullscreen) "退出全屏" else "全屏", tint = Color.White,
                        )
                    }
                }
            }
        }
        if (fullscreen) {
            Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).width(280.dp)) {
                AnimatedVisibility(showControls || typing || fsText.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                    FullscreenDanmakuInput(
                        controller, ui, fsText, onText = { fsText = it },
                        muted = room.video?.mutedUntil(me, controller.serverNowMs()) != null,
                        onFocus = { typing = it },
                    )
                }
            }
        }
    }
}

/** 固定在画面下面：标题和点播人一行，点赞和投票跳过在右边；下面一行是在看的人和档位。 */
@Composable
private fun InfoBar(
    controller: AppController,
    room: Snapshot,
    ui: UiActions,
    me: String,
    online: List<MemberView>,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
    canControl: Boolean,
    isAdmin: Boolean,
    onLikeAnchor: (Offset) -> Unit,
    onShowListeners: () -> Unit,
    onShowPolicy: () -> Unit,
) {
    val pb = room.playback
    val item = pb.item
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item?.track?.title ?: "还没有视频",
                    style = VType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val requester = item?.let { membersById[it.requestedBy] }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    if (requester != null) {
                        Avatar(requester.userId, requester.nickname, requester.avatar, 16.dp, photo(requester))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        when {
                            item == null -> "到「待播」里点 B 站或 YouTube 视频"
                            else -> listOfNotNull("${requester?.nickname ?: "已离开的人"} 点的", item.track.artistText.ifEmpty { null }).joinToString(" · ")
                        },
                        style = VType.Small, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item != null) {
                Spacer(Modifier.width(8.dp))
                val myLikes by controller.myLikes.collectAsState()
                val mine = myLikes.seq == pb.seq
                LikeButton(
                    controller,
                    likes = if (mine) maxOf(pb.likes, myLikes.total) else pb.likes,
                    liked = mine || me in pb.likerIds,
                    onAnchor = onLikeAnchor,
                )
                Spacer(Modifier.width(6.dp))
                SkipChip(controller, ui, me, room, online.size)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ListenersRow(online, photo, onShowListeners, verb = "看") }
            PolicyPill(room.video?.policy, room.video?.effective, room.video?.locked == true, canControl, isAdmin, onShowPolicy)
        }
    }
}

/** 投票跳过：任何档位下所有人都能投，跟听歌房间一样。 */
@Composable
private fun SkipChip(controller: AppController, ui: UiActions, me: String, room: Snapshot, onlineCount: Int) {
    val pb = room.playback
    val voted = me in pb.skipperIds
    val need = onlineCount.coerceIn(1, 2)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(press, 0.92f).clip(CircleShape)
            .background(if (voted) Palette.AccentSoft else Color.White.copy(alpha = 0.08f))
            .pressClick(press) { ui.run { controller.skip(!voted) } }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SkipNext, contentDescription = "投票切到下一个", tint = if (voted) Palette.Accent else Palette.Text, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(3.dp))
        Text("${pb.skipperIds.size}/$need", style = VType.Chip, color = if (voted) Palette.Accent else Palette.Text)
    }
}

/** 档位缩成一个小标签：平时不用看，点开才有说明。 */
@Composable
private fun PolicyPill(policy: String?, effective: String?, locked: Boolean, canControl: Boolean, isAdmin: Boolean, onClick: () -> Unit) {
    val shown = effective ?: policy ?: ControlPolicy.REQUESTER
    val label = when (shown) {
        ControlPolicy.FREE -> "自由"
        ControlPolicy.REQUESTER -> "点播人"
        ControlPolicy.ADMINS -> "管理员"
        else -> shown
    }
    val tint = if (canControl) Palette.Online else Palette.Muted
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            " $label" + if (policy != null && effective != policy) "（暂时）" else "",
            style = VType.Meta, color = Palette.Muted,
        )
        Text(
            when {
                isAdmin -> " · 管理员"
                canControl -> " · 你可控制"
                else -> " · 需请求"
            },
            style = VType.Meta, color = tint,
        )
    }
}

@Composable
private fun Tabs(selected: VideoTab, queueCount: Int, chatUnread: Boolean, onSelect: (VideoTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
        listOf(VideoTab.Chat to "聊天", VideoTab.Queue to "待播 $queueCount").forEach { (t, label) ->
            val on = t == selected
            val color by animateColorAsState(if (on) Palette.Text else Palette.Muted, label = "tab")
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(t) }.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(label, style = VType.Name, color = color, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    if (t == VideoTab.Chat && chatUnread) {
                        Box(Modifier.padding(start = 3.dp, top = 2.dp).size(6.dp).clip(CircleShape).background(Palette.Accent))
                    }
                }
                Box(
                    Modifier.padding(top = 6.dp).size(width = 18.dp, height = 3.dp).clip(CircleShape)
                        .background(if (on) Palette.Accent else Color.Transparent),
                )
            }
        }
    }
}

// ---------- 聊天 ----------

/** 放映厅里用的字号：中文不加字距，正文 15sp，辅助信息 12sp。 */
internal object VType {
    val Title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val Name = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val Meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val Small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Chip = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

/** 同一个人 5 分钟内连着发的消息并成一组，只在第一条显示头像和名字（和 Discord 一样）。 */
private const val GROUP_GAP_MS = 5 * 60_000L

/**
 * Discord 式的消息流：所有人都靠左，头像 + 名字 + 时间，正文不加气泡；
 * 新消息在底部，在底部附近时自动跟到最新，往上翻着看时不打扰。
 */
@Composable
private fun ChatPane(chat: List<ChatMessage>, me: String, video: VideoState?, membersById: Map<String, MemberView>, photo: (MemberView) -> String?) {
    if (chat.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有人说话，发条弹幕吧", color = Palette.Muted, style = VType.Small)
        }
        return
    }
    val state = rememberLazyListState()
    val last = chat.last()
    LaunchedEffect(last.id) {
        if (state.firstVisibleItemIndex <= 2 || last.userId == me) state.animateScrollToItem(0)
    }
    // reverseLayout：第 0 项在最底下，所以把列表倒过来喂；每条要看它前一条（时间上）决定要不要并组。
    val rows = chat.indices.reversed().map { i -> chat[i] to chat.getOrNull(i - 1) }
    LazyColumn(
        Modifier.fillMaxSize(), state = state, reverseLayout = true,
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
    ) {
        items(rows, key = { it.first.id }) { (msg, prev) ->
            when {
                msg.isVideoStart -> VideoDivider(msg)
                msg.isDanmaku -> {
                    val grouped = prev != null && prev.isDanmaku && prev.userId == msg.userId && msg.at - prev.at < GROUP_GAP_MS
                    MessageRow(msg, grouped, me, video?.isAdmin(msg.userId) == true, membersById, photo)
                }
                else -> SystemRow(msg)
            }
        }
    }
}

/** 换到新视频：横线中间写片名，之后的消息都属于这个视频。 */
@Composable
private fun VideoDivider(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(Palette.Outline))
            Text(
                "正在放映", style = VType.Meta, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(Palette.Outline))
        }
        Text(
            msg.text, style = VType.Name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(msg.nickname.ifEmpty { null }?.let { "$it 点的" }, wallClockText(msg.at)).joinToString(" · "),
            style = VType.Meta, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 暂停、拖进度、档位、禁言：头像那一列放个小图标，正文灰色，和 Discord 的系统消息一样不抢眼。 */
@Composable
private fun SystemRow(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(40.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            Icon(systemIcon(msg.text), contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                append(msg.text)
                withStyle(SpanStyle(color = Palette.Muted.copy(alpha = 0.7f), fontSize = 12.sp)) { append("  " + wallClockText(msg.at)) }
            },
            style = VType.Small, color = Palette.Muted, modifier = Modifier.weight(1f),
        )
    }
}

private fun systemIcon(text: String) = when {
    "禁言" in text -> Icons.Rounded.Block
    "暂停" in text -> Icons.Rounded.Pause
    "继续" in text -> Icons.Rounded.PlayArrow
    "跳到" in text -> Icons.Rounded.FastForward
    "切到" in text || "下一个" in text -> Icons.Rounded.SkipNext
    "档位" in text || "控制" in text -> Icons.Rounded.AdminPanelSettings
    else -> Icons.Rounded.Info
}

@Composable
private fun MessageRow(
    msg: ChatMessage,
    grouped: Boolean,
    me: String,
    isAdmin: Boolean,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
) {
    val m = membersById[msg.userId]
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (grouped) 1.dp else 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(40.dp)) {
            if (!grouped) Avatar(msg.userId, msg.nickname, m?.avatar.orEmpty(), 40.dp, m?.let(photo))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!grouped) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg.nickname, style = VType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (isAdmin) Palette.Accent else Palette.Text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (msg.userId == me) {
                        Text("你", style = VType.Meta, color = Palette.Muted, modifier = Modifier.padding(start = 6.dp))
                    }
                    Text(wallClockText(msg.at), style = VType.Meta, color = Palette.Muted, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(msg.text, style = VType.Body, color = Palette.Text.copy(alpha = 0.92f), modifier = Modifier.padding(top = if (grouped) 0.dp else 2.dp))
        }
    }
}

/** 聊天标签底部：左边「等等我」，右边弹幕输入框。 */
@Composable
private fun DanmakuInput(controller: AppController, ui: UiActions, mutedUntil: Long?, canControl: Boolean, hasVideo: Boolean) {
    var text by remember { mutableStateOf("") }
    // 禁言到期后自己恢复输入框，不用等服务器再发一次状态。
    var muted by remember(mutedUntil) { mutableStateOf(mutedUntil != null && mutedUntil > controller.serverNowMs()) }
    LaunchedEffect(mutedUntil) {
        while (muted && mutedUntil != null) {
            delay((mutedUntil - controller.serverNowMs()).coerceIn(1_000, 30_000))
            muted = mutedUntil > controller.serverNowMs()
        }
    }
    val sender = remember { DanmakuSender() }
    val send = { if (!muted) sender.send(controller, ui, text, current = { text }, setText = { text = it }) }
    Row(
        Modifier.fillMaxWidth().background(Palette.Background).windowInsetsPadding(WindowInsets.navigationBars).imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「等等我」就是一次暂停请求，放在输入框左边，离发送键和输入法的发送键都远；打字时收起来。
        if (hasVideo && text.isEmpty()) {
            Row(
                Modifier.padding(end = 8.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                    .clickable { ui.run { controller.control(ControlAction.PAUSE) } }.padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Pause, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
                Text(if (canControl) "暂停" else "等等我", style = VType.Chip, color = Palette.Text, modifier = Modifier.padding(start = 3.dp))
            }
        }
        Row(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        when {
                            !muted -> "发条弹幕，大家画面上都会飘过"
                            mutedUntil != null && mutedUntil < 4_102_444_800_000L -> "你被禁言了，${wallClockText(mutedUntil)} 解除"
                            else -> "你被管理员禁言了"
                        },
                        style = VType.Body, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text, onValueChange = { text = it.take(40) },
                    enabled = !muted, singleLine = true,
                    textStyle = VType.Body.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (text.isNotBlank()) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送", tint = Palette.Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ClipSuggestionHost(track: TrackRef?, modifier: Modifier, onAdd: (TrackRef) -> Unit, onDismiss: () -> Unit) {
    // 收起动画期间 track 已经是 null，用最后一次的值画完。
    var shown by remember { mutableStateOf<TrackRef?>(null) }
    track?.let { shown = it }
    AnimatedVisibility(
        track != null, modifier = modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        shown?.let { t -> ClipSuggestion(t, onAdd = { onAdd(t) }, onDismiss = onDismiss) }
    }
}

@Composable
private fun ClipSuggestion(track: TrackRef, onAdd: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp)).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track, 44.dp, corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("剪贴板里的 B 站视频", style = VType.Meta, color = Palette.Muted)
            Text(track.title, style = VType.Small, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "加到待播", style = VType.Chip, color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Accent).clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = "不用了", tint = Palette.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

// ---------- 待播 ----------

@Composable
private fun QueuePane(
    controller: AppController,
    room: Snapshot,
    ui: UiActions,
    me: String,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        room.next?.let { next ->
            item { QueueLabel("下一个 · 已锁定") }
            item(key = "next-${next.itemId}") {
                QueueRow(next, membersById, photo, me, locked = true, controller, ui, Modifier.animateItem())
            }
        }
        if (room.queue.isNotEmpty()) {
            item { QueueLabel("待播 · 置顶票多的先放") }
        }
        items(room.queue, key = { it.itemId }) { item ->
            QueueRow(item, membersById, photo, me, locked = false, controller, ui, Modifier.animateItem())
        }
        if (room.queue.isEmpty() && room.next == null) {
            item {
                Text(
                    "还没有下一个视频，点下面的按钮粘贴 B 站链接",
                    color = Palette.Muted, style = VType.Small, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun QueueLabel(text: String) {
    Text(
        text, style = VType.Meta, color = Palette.Muted,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** 待播标签底部：点视频。 */
@Composable
private fun PickBar(onPick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Palette.Surface).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val press = remember { MutableInteractionSource() }
        Row(
            Modifier.fillMaxWidth().pressScale(press, 0.97f).clip(RoundedCornerShape(16.dp)).background(Palette.AccentGradient)
                .pressClick(press, onClick = onPick).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(" 点视频", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------- 控制请求 ----------

/** 有权限的人才看得到：浮在画面顶上，不会被列表划走。 */
@Composable
internal fun RequestBanners(controller: AppController, ui: UiActions, requests: List<ControlRequest>, modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        requests.forEach { req ->
            AnimatedVisibility(true, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
                RequestBanner(controller, ui, req)
            }
        }
    }
}

@Composable
private fun RequestBanner(controller: AppController, ui: UiActions, req: ControlRequest) {
    var left by remember(req.id) { mutableLongStateOf(0L) }
    LaunchedEffect(req.id) {
        while (true) {
            left = ((req.expiresAt - controller.serverNowMs()) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${req.nickname} 想${actionText(req)}", color = Color.White, fontWeight = FontWeight.Medium,
            style = VType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("${left}s", style = VType.Meta, color = Color.White.copy(alpha = 0.6f))
        Text(
            "拒绝", color = Color.White.copy(alpha = 0.8f), style = VType.Meta,
            modifier = Modifier.clip(CircleShape).clickable { ui.run { controller.replyRequest(req.id, false) } }.padding(horizontal = 10.dp, vertical = 7.dp),
        )
        Text(
            "同意", color = Color.White, style = VType.Meta,
            modifier = Modifier.clip(CircleShape).background(Palette.Accent)
                .clickable { ui.run { controller.replyRequest(req.id, true) } }.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

private fun actionText(req: ControlRequest) = when (req.action) {
    ControlAction.PAUSE -> "暂停"
    ControlAction.PLAY -> "继续播放"
    ControlAction.SEEK -> "跳到 ${formatTime(req.positionMs)}"
    ControlAction.NEXT -> "切到下一个"
    else -> req.action
}

@Composable
internal fun FullscreenDanmakuInput(
    controller: AppController, ui: UiActions, text: String, onText: (String) -> Unit, muted: Boolean, onFocus: (Boolean) -> Unit,
) {
    val sender = remember { DanmakuSender() }
    val latest by rememberUpdatedState(text)
    val send = { if (!muted) sender.send(controller, ui, text, current = { latest }, setText = onText) }
    OutlinedTextField(
        value = text, onValueChange = { onText(it.take(40)) }, singleLine = true, enabled = !muted,
        modifier = Modifier.onFocusChanged { onFocus(it.isFocused) },
        placeholder = { Text(if (muted) "你被管理员禁言了" else "发条弹幕…", color = Color.White.copy(alpha = 0.6f)) },
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color.Black.copy(alpha = 0.4f), focusedContainerColor = Color.Black.copy(alpha = 0.6f),
            unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.Accent,
        ),
    )
}

// ---------- 弹幕 ----------

/**
 * 按下发送就清空输入框，不等服务器回执，免得回执慢时再按一次又发出同一句、或者把接着打的字清掉。
 * 发着的时候再按不重发；发失败了输入框还空着就把字放回去，再发沿用同一个编号，服务器不会收两次。
 */
private class DanmakuSender {
    private var sending = false
    private var failed: Pair<String, String>? = null

    fun send(controller: AppController, ui: UiActions, raw: String, current: () -> String, setText: (String) -> Unit) {
        val t = raw.trim()
        if (t.isEmpty() || sending) return
        val nonce = failed?.takeIf { it.first == t }?.second ?: Random.nextLong().toULong().toString(36)
        failed = null
        sending = true
        setText("")
        ui.run {
            try {
                controller.sendDanmaku(t, nonce)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    failed = t to nonce
                    if (current().isEmpty()) setText(t)
                }
                throw e
            } finally {
                sending = false
            }
        }
    }
}

private data class Flying(val msg: ChatMessage, val lane: Int, val startMs: Long) {
    var widthPx: Int = 0
}

private const val DANMAKU_LANES = 4
private const val DANMAKU_DURATION_MS = 8_000L

/** 朋友弹幕从右往左飘过画面上半部分，收到就飘，不按视频时间轴回放。 */
@Composable
internal fun DanmakuLayer(controller: AppController, modifier: Modifier) {
    val flying = remember { mutableStateListOf<Flying>() }
    var now by remember { mutableLongStateOf(0L) }
    val laneFree = remember { LongArray(DANMAKU_LANES) }

    LaunchedEffect(controller) {
        controller.danmaku.collect { msg ->
            val t = withFrameMillis { it }
            // 挑最早空出来的一条道；都占着就挑最早的那条。
            val lane = (0 until DANMAKU_LANES).minBy { laneFree[it] }
            laneFree[lane] = t + DANMAKU_DURATION_MS / 3
            flying += Flying(msg, lane, t)
        }
    }
    LaunchedEffect(flying.isNotEmpty()) {
        while (flying.isNotEmpty()) {
            now = withFrameMillis { it }
            flying.removeAll { now - it.startMs > DANMAKU_DURATION_MS }
        }
    }

    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val laneHeight = with(LocalDensity.current) { 26.dp.roundToPx() }
        val topPad = with(LocalDensity.current) { 8.dp.roundToPx() }
        flying.forEach { f ->
            val p = ((now - f.startMs).toFloat() / DANMAKU_DURATION_MS).coerceIn(0f, 1f)
            val x = (widthPx - p * (widthPx + f.widthPx)).toInt()
            Text(
                f.msg.text,
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                modifier = Modifier.offset { IntOffset(x, topPad + f.lane * laneHeight) }
                    .onSizeChanged { f.widthPx = it.width }
                    .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            )
        }
    }
}

// ---------- 面板 ----------

/** 档位说明；管理员可以改（服主锁定时不能）。 */
@Composable
private fun PolicySheet(controller: AppController, ui: UiActions, room: Snapshot, isAdmin: Boolean, visible: Boolean, onDismiss: () -> Unit) {
    val video = room.video ?: return
    BottomSheet(visible, onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("进度控制档位", style = VType.Title)
            Text(
                when {
                    video.locked -> "服主锁定了档位，所有放映厅都按这一档。"
                    isAdmin -> "你是管理员，可以改这个放映厅的档位，改了大家都会看到。"
                    else -> "档位由服主和管理员决定。没权限时点控制按钮会变成请求；不管哪一档，所有人都能投票切到下一个。"
                },
                style = VType.Meta, color = Palette.Muted,
            )
            val editable = isAdmin && !video.locked
            listOf(ControlPolicy.FREE, ControlPolicy.REQUESTER, ControlPolicy.ADMINS).forEach { p ->
                PolicyOption(p, selected = p == video.policy, enabled = editable) {
                    if (p != video.policy) ui.run { controller.setPolicy(p) }
                }
            }
            if (video.effective != video.policy) {
                Text(
                    "有权限的人现在不在线，暂时按「${ControlPolicy.title(video.effective)}」，他们回来就恢复。",
                    style = VType.Meta, color = Palette.Muted,
                )
            }
        }
    }
}

@Composable
private fun PolicyOption(policy: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, label = "border")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) Palette.Accent else Palette.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ControlPolicy.title(policy), fontWeight = FontWeight.SemiBold, color = if (enabled || selected) Palette.Text else Palette.Muted)
            Text(ControlPolicy.description(policy), style = VType.Meta, color = Palette.Muted)
        }
    }
}

/** 画质只影响自己；更高的画质要服务器上的 B 站账号有大会员。 */
@Composable
private fun QualitySheet(controller: AppController, visible: Boolean, onDismiss: () -> Unit) {
    val info by controller.videoInfo.collectAsState()
    val picked by controller.videoQn.collectAsState()
    BottomSheet(visible, onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("画质", style = VType.Title)
            Text(
                "只影响你自己。网不好时选低一点；直连失败改走服务器时会自动降到 720P 以内。",
                style = VType.Meta, color = Palette.Muted,
            )
            val options = info?.qualities.orEmpty()
            if (options.isEmpty()) {
                Text("开始放视频后才能选", color = Palette.Muted, style = VType.Small)
            }
            options.forEach { q ->
                val current = info?.qn == q.qn
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
                        .border(1.5.dp, if (current) Palette.Accent else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { controller.setVideoQn(q.qn); onDismiss() }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (current) Text("正在用", style = VType.Meta, color = Palette.Accent)
                    else if (picked == q.qn) Text("账号没有这个画质的权限", style = VType.Meta, color = Palette.Muted)
                }
            }
        }
    }
}
