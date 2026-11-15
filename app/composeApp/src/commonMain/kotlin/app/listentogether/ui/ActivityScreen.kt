package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.Activity
import app.listentogether.model.MemberView
import app.listentogether.model.Snapshot

/** 房间记录：谁推荐了什么歌、谁点了赞、哪些歌因切歌结束播放。新的在上面。 */
@Composable
fun ActivityScreen(controller: AppController, room: Snapshot, onClose: () -> Unit) {
    PlatformBackHandler(onBack = onClose)
    val membersById = room.members.associateBy { it.userId }
    val now = controller.serverNowMs()

    Column(Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Palette.Text) }
            Text("房间记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        }
        if (room.activity.isEmpty()) {
            Text(
                "还没有记录。点歌、点赞、切歌都会记在这里。",
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(room.activity, key = { it.id }) { a -> ActivityRow(a, membersById[a.userId], controller, now) }
        }
    }
}

@Composable
private fun ActivityRow(a: Activity, member: MemberView?, controller: AppController, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            if (a.kind == Activity.SKIP) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
                }
            } else {
                // 成员离开后用记录里的昵称生成默认头像。
                val avatar = member?.avatar ?: ""
                Avatar(a.userId, member?.nickname ?: a.nickname, avatar, 38.dp, member?.let { controller.avatarUrl(it.userId, it.avatar) })
            }
            if (a.kind == Activity.LIKE) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(Palette.Background),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ThumbUp, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(10.dp)) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(describe(a), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(a.track.artistText, timeAgo(now - a.at)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Cover(a.track, 44.dp, corner = 8.dp)
    }
}

private fun describe(a: Activity) = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)
    val title = "《${a.track.title}》"
    when (a.kind) {
        Activity.ADD -> {
            withStyle(bold) { append(a.nickname) }
            append(" 推荐了 ")
            withStyle(bold) { append(title) }
        }
        Activity.LIKE -> {
            withStyle(bold) { append(a.nickname) }
            append(" 赞了 ")
            withStyle(bold) { append(title) }
        }
        Activity.SKIP -> {
            withStyle(bold) { append(title) }
            append(" 因切歌结束播放")
            if (a.names.isNotEmpty()) append("（${a.names.joinToString("、")}）")
        }
        else -> withStyle(bold) { append(title) }
    }
}

private fun timeAgo(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "$min 分钟前"
        min < 24 * 60 -> "${min / 60} 小时前"
        else -> "${min / 1440} 天前"
    }
}
