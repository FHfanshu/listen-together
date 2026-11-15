package app.listentogether.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.listentogether.AppController
import app.listentogether.model.ControlAction
import app.listentogether.model.MemberView
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 视频宽高比小于这个就按竖屏短视频处理。 */
internal const val PORTRAIT_RATIO = 0.9f

/**
 * 竖屏短视频的沉浸页，手势和短视频 App 一样：
 * 单击暂停/继续，双击点赞，上滑切下一个，下滑退出。没有控制权时暂停和切换都会变成请求，跟控制条一致。
 * 右侧是点播人、点赞、弹幕开关、待播和下一个；左下是标题和进度；最下面打弹幕。
 */
@Composable
internal fun ShortVideoImmersive(
    controller: AppController,
    room: Snapshot,
    ui: UiActions,
    me: String,
    canControl: Boolean,
    danmakuOn: Boolean,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
    onToggleDanmaku: () -> Unit,
    onOpenQueue: () -> Unit,
    onExit: () -> Unit,
) {
    val pb = room.playback
    val item = pb.item
    val duration = item?.track?.durationMs ?: 0
    val paused = pb.status == PlaybackStatus.PAUSED
    val videoError by controller.videoError.collectAsState()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeNeed = with(density) { 110.dp.toPx() }

    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var text by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    // 上下拖动时画面跟着走一点，松手弹回。
    val pull = remember { Animatable(0f) }
    val hearts = remember { mutableStateListOf<Heart>() }
    var likeAnchor by remember { mutableStateOf<Offset?>(null) }
    val likeFlow = remember(controller) { controller.likes.map { it.userId } }
    var heartSeq by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pb) {
        while (true) {
            position = controller.videoPositionMs()
            delay(250)
        }
    }

    val toggle = { ui.run { controller.control(if (paused) ControlAction.PLAY else ControlAction.PAUSE) } }
    val like = { at: Offset ->
        hearts += Heart(at, ++heartSeq)
        if (hearts.size > 12) hearts.removeAt(0)
        controller.like()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(paused, typing) {
                detectTapGestures(
                    onTap = { if (typing) focus.clearFocus() else toggle() },
                    onDoubleTap = { like(it) },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val d = pull.value
                        scope.launch { pull.animateTo(0f, spring()) }
                        when {
                            d < -swipeNeed -> ui.run { controller.control(ControlAction.NEXT) }
                            d > swipeNeed -> onExit()
                        }
                    },
                    onDragCancel = { scope.launch { pull.animateTo(0f, spring()) } },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { pull.snapTo(pull.value + dy) }
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull.value * 0.35f }) {
            if (item != null) VideoSurface(Modifier.fillMaxSize())
        }
        // 弹幕只占上半部分，别挡住下面的标题和右侧按钮。
        if (danmakuOn) {
            DanmakuLayer(controller, Modifier.fillMaxWidth().fillMaxHeight(0.45f).windowInsetsPadding(WindowInsets.statusBars).padding(top = 48.dp))
        }

        // 上滑/下滑到位时提示松手会怎样。
        val hint = when {
            pull.value < -swipeNeed -> if (canControl) "松手切到下一个" else "松手请求切到下一个"
            pull.value > swipeNeed -> "松手退出沉浸"
            else -> null
        }
        if (hint != null) {
            Text(
                hint, color = Color.White, style = VType.Small,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        when {
            item == null -> Text("还没有视频，到「待播」里点一个", color = Palette.Muted, style = VType.Small, modifier = Modifier.align(Alignment.Center))
            videoError != null -> Text(
                videoError.orEmpty(), color = Color.White, style = VType.Small,
                modifier = Modifier.align(Alignment.Center).padding(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            pb.status == PlaybackStatus.PREPARING -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text("等大家都缓冲好就开播", color = Color.White, style = VType.Meta, modifier = Modifier.padding(top = 10.dp))
            }
            paused && hint == null -> Icon(
                Icons.Rounded.PlayArrow, contentDescription = "继续", tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center).size(72.dp),
            )
        }

        HeartLayer(hearts)

        // 顶部：退出、在看人数。
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures { onExit() } }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出沉浸", tint = Color.White)
            }
            Text(
                "放映厅 · ${room.members.count { it.online }} 人在看" + if (canControl) "" else " · 操作会发请求",
                color = Color.White.copy(alpha = 0.85f), style = VType.Meta,
            )
        }
        RequestBanners(
            controller, ui, if (canControl) room.video?.requests.orEmpty() else emptyList(),
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 52.dp).widthIn(max = 420.dp),
        )

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            Row(verticalAlignment = Alignment.Bottom) {
                // 左下：点播人和标题。
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, bottom = 10.dp)) {
                    val requester = item?.let { membersById[it.requestedBy] }
                    if (item != null) {
                        Text(
                            "@${requester?.nickname ?: "已离开的人"} 点的",
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        )
                        Text(
                            item.track.title, color = Color.White.copy(alpha = 0.92f), style = VType.Small,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                        )
                        if (item.track.artistText.isNotEmpty()) {
                            Text(item.track.artistText, color = Color.White.copy(alpha = 0.6f), style = VType.Meta, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                // 右侧操作栏。
                Column(
                    Modifier.padding(end = 10.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item?.let { membersById[it.requestedBy] }?.let { m ->
                        Avatar(m.userId, m.nickname, m.avatar, 44.dp, photo(m))
                    }
                    val myLikes by controller.myLikes.collectAsState()
                    val mine = myLikes.seq == pb.seq
                    RailButton(
                        Icons.Rounded.ThumbUp, "${if (mine) maxOf(pb.likes, myLikes.total) else pb.likes}",
                        tint = if (mine || me in pb.likerIds) Palette.Accent else Color.White,
                        modifier = Modifier.onGloballyPositioned { c ->
                            likeAnchor = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 3f)
                        },
                    ) { likeAnchor?.let { like(it) } ?: controller.like() }
                    RailButton(if (danmakuOn) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff, if (danmakuOn) "弹幕开" else "弹幕关", onClick = onToggleDanmaku)
                    val waiting = room.queue.size + (if (room.next != null) 1 else 0)
                    RailButton(Icons.AutoMirrored.Rounded.QueueMusic, "待播 $waiting", onClick = onOpenQueue)
                    RailButton(Icons.Rounded.SkipNext, "下一个") { ui.run { controller.control(ControlAction.NEXT) } }
                }
            }

            // 进度：平时一条细线，暂停或拖动时变成能拖的进度条。
            val shown = dragging?.let { (it * duration).toLong() } ?: position
            val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
            if (paused || dragging != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatTime(shown)} / ${formatTime(duration)}", color = Color.White, style = VType.Meta)
                    Slider(
                        value = fraction,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = dragging?.let { (it * duration).toLong() }
                            dragging = null
                            if (target != null) ui.run { controller.control(ControlAction.SEEK, target) }
                        },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Color.White.copy(alpha = 0.85f)))
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                FullscreenDanmakuInput(
                    controller, ui, text, onText = { text = it },
                    muted = room.video?.mutedUntil(me, controller.serverNowMs()) != null,
                    onFocus = { typing = it },
                )
            }
        }

        LikeBurstLayer(likeFlow, anchor = { likeAnchor })
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(CircleShape).pointerInput(onClick) { detectTapGestures { onClick() } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

internal data class Heart(val at: Offset, val id: Long)

/** 双击的位置冒出一个放大后淡出的赞。 */
@Composable
private fun HeartLayer(hearts: List<Heart>) {
    val density = LocalDensity.current
    val half = with(density) { 36.dp.toPx() }
    hearts.forEach { h ->
        androidx.compose.runtime.key(h.id) {
            val scale = remember { Animatable(0.4f) }
            val alpha = remember { Animatable(1f) }
            LaunchedEffect(Unit) {
                launch { scale.animateTo(1.3f, spring(dampingRatio = 0.45f)) }
                delay(350)
                alpha.animateTo(0f, tween(450))
            }
            Icon(
                Icons.Rounded.ThumbUp, contentDescription = null, tint = Palette.Accent,
                modifier = Modifier.offset { IntOffset((h.at.x - half).roundToInt(), (h.at.y - half).roundToInt()) }
                    .size(72.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value; rotationZ = -12f },
            )
        }
    }
}
