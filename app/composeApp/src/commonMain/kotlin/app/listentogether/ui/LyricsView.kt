package app.listentogether.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.lyrics.Lyrics
import app.listentogether.model.PlaybackState
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.TrackRef
import kotlinx.coroutines.delay

/** 歌词的加载状态：还在拉、没有歌词、有歌词。 */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

/** 拉当前歌的歌词，失败隔几秒重试几次（服务器偶尔解析慢），最后放弃当没有。 */
@Composable
fun rememberLyrics(controller: AppController, track: TrackRef?): State<LyricsState> =
    produceState<LyricsState>(LyricsState.Loading, track?.key) {
        value = LyricsState.Loading
        if (track == null) { value = LyricsState.None; return@produceState }
        repeat(3) { attempt ->
            val r = runCatching { controller.lyrics(track) }
            r.onSuccess { value = it?.let(LyricsState::Ready) ?: LyricsState.None; return@produceState }
            delay(3_000L * (attempt + 1))
        }
        value = LyricsState.None
    }

/**
 * 房间当前进度（毫秒），每帧更新一次。只在 draw 阶段读它的地方会重绘，不会整块重组；
 * 没在放（等缓冲、空房间）时停在 0，不跑帧循环。
 */
@Composable
fun rememberRoomPosition(controller: AppController, pb: PlaybackState): State<Long> {
    val pos = remember { mutableLongStateOf(0L) }
    val startAt = pb.startAt
    LaunchedEffect(pb.seq, pb.status, startAt) {
        if (startAt == null || pb.status != PlaybackStatus.PLAYING) { pos.longValue = 0; return@LaunchedEffect }
        while (true) {
            withFrameMillis { }
            pos.longValue = (controller.serverNowMs() - startAt).coerceAtLeast(0)
        }
    }
    return pos
}

/**
 * 封面位置的歌词：当前句居中、放大、逐句填色，自动滚动；上下边缘渐隐。
 * 手动拖动后 3 秒内不抢滚动。点击由外面的 modifier 处理（切回封面）。
 */
@Composable
fun LyricsPanel(state: LyricsState, position: State<Long>, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is LyricsState.Ready -> LyricsList(state.lyrics, position)
            LyricsState.Loading -> Text("歌词加载中…", color = Palette.Muted)
            LyricsState.None -> Text("这首歌暂无歌词", color = Palette.Muted)
        }
    }
}

@Composable
private fun LyricsList(lyrics: Lyrics, position: State<Long>) {
    val index by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val list = rememberLazyListState()
    val padPx = with(LocalDensity.current) { LIST_PAD.roundToPx() }
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var resumeTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) userScrollAt = currentMillis() }
    }
    // 手动拖完 3 秒后自动回到当前句
    LaunchedEffect(userScrollAt) { if (userScrollAt != 0L) { delay(3_000); resumeTick = currentMillis() } }
    LaunchedEffect(index, resumeTick, lyrics) {
        if (userScrollAt != 0L && currentMillis() - userScrollAt < 3_000) return@LaunchedEffect
        // 当前句放在面板约 40% 高的位置。offset 0 时这一行贴在上内边距下沿，所以要扣掉内边距。
        val viewport = list.layoutInfo.viewportSize.height
        list.animateScrollToItem(index.coerceAtLeast(0), scrollOffset = padPx - (viewport * 0.4f).toInt())
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(vertical = LIST_PAD),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == index
            val alpha by animateFloatAsState(if (current) 1f else 0.4f, tween(300), label = "a")
            val scale by animateFloatAsState(if (current) 1.08f else 1f, tween(300), label = "s")
            Column(
                Modifier.fillMaxWidth().padding(vertical = 7.dp)
                    .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                when {
                    line.text.isEmpty() -> Text("♪", style = style, color = Palette.Muted)
                    current -> KaraokeText(line.text, progress = { lyrics.progress(i, position.value) }, style = style)
                    else -> Text(line.text, style = style, color = Palette.Text)
                }
                line.translation?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 卡拉 OK 式填色：整行先按白色画，再用 SrcIn 把已唱部分染成强调色、未唱部分染成半透明白。
 * 进度只在 draw 阶段读取，每帧只重绘这一小块文字；换行的长句按排版逐行填。
 */
@Composable
fun KaraokeText(text: String, progress: () -> Float, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, style = style, color = Color.White, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val chars = progress().coerceIn(0f, 1f) * text.length
                val cut = chars.toInt().coerceAtMost(text.length)
                // SrcIn 只改画到的矩形内、且只保留字形的形状：每行左边一段染强调色，右边染半透明。
                for (ln in 0 until l.lineCount) {
                    val start = l.getLineStart(ln)
                    val end = l.getLineEnd(ln, visibleEnd = true)
                    val x = when {
                        chars >= end -> size.width
                        chars <= start -> 0f
                        else -> {
                            // 当前字内部按小数部分插值，填色连续推进而不是一个字一个字跳
                            val a = l.getHorizontalPosition(cut, usePrimaryDirection = true)
                            val b = if (cut + 1 < end) l.getHorizontalPosition(cut + 1, usePrimaryDirection = true) else l.getLineRight(ln)
                            a + (b - a) * (chars - cut)
                        }
                    }
                    val top = l.getLineTop(ln)
                    val h = l.getLineBottom(ln) - top
                    if (x > 0f) drawRect(Palette.AccentGradient, topLeft = Offset(0f, top), size = Size(x, h), blendMode = BlendMode.SrcIn)
                    if (x < size.width) drawRect(Palette.Text.copy(alpha = 0.45f), topLeft = Offset(x, top), size = Size(size.width - x, h), blendMode = BlendMode.SrcIn)
                }
            },
    )
}

private val LIST_PAD = 120.dp

private val since = kotlin.time.TimeSource.Monotonic.markNow()

// 从 1 开始，0 留给"没拖过"
private fun currentMillis(): Long = since.elapsedNow().inWholeMilliseconds + 1
