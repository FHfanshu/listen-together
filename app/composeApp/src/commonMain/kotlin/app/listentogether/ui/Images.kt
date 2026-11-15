package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.listentogether.model.MemberView
import app.listentogether.model.TrackRef
import coil3.compose.AsyncImage

/** 封面地址按显示尺寸缩小：网易云支持 ?param=宽y高，其他平台本身就是小图。 */
fun coverUrl(track: TrackRef?, px: Int): String? {
    val url = track?.coverUrl?.takeIf { it.isNotBlank() } ?: return null
    return if (track.source == "netease" && '?' !in url) "$url?param=${px}y$px" else url
}

@Composable
fun Cover(track: TrackRef?, size: Dp, corner: Dp = 8.dp, modifier: Modifier = Modifier) {
    val px = (size.value * 2.5f).toInt().coerceIn(96, 800)
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(placeholderBrush(track?.key ?: "")),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(size * 0.4f))
        coverUrl(track, px)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private val GradientPairs = listOf(
    Color(0xFFFF6B6B) to Color(0xFFFFA36C),
    Color(0xFF7F7FD5) to Color(0xFF86A8E7),
    Color(0xFF43C6AC) to Color(0xFF7BE0C2),
    Color(0xFFF7797D) to Color(0xFFC471ED),
    Color(0xFFFFB347) to Color(0xFFFFCC33),
    Color(0xFF4FACFE) to Color(0xFF00F2FE),
    Color(0xFFEE9CA7) to Color(0xFFFFB199),
    Color(0xFF667EEA) to Color(0xFF9F7AEA),
)

/** 内置头像：编号与服务端 PresetAvatars(24) 对应。 */
val PresetEmojis = listOf(
    "🐱", "🐶", "🦊", "🐼", "🐨", "🐯", "🦁", "🐸",
    "🐵", "🐰", "🐻", "🐧", "🦄", "🐙", "🦉", "🐳",
    "🐝", "🦋", "🌻", "🍉", "🍑", "🌙", "⭐", "🎧",
)

private fun stableHash(s: String): Int = s.fold(7) { h, c -> h * 31 + c.code }.let { if (it < 0) -it else it }

private fun gradientFor(index: Int) = GradientPairs[index % GradientPairs.size].let { Brush.linearGradient(listOf(it.first, it.second)) }

private fun placeholderBrush(seed: String) = GradientPairs[stableHash(seed) % GradientPairs.size].let {
    Brush.linearGradient(listOf(it.first.copy(alpha = 0.55f), it.second.copy(alpha = 0.55f)))
}

/**
 * 头像：上传的照片，或者内置的渐变底 + 表情；都没有时用按 userId 固定分配的渐变色 + 昵称首字。
 */
@Composable
fun Avatar(
    userId: String,
    nickname: String,
    avatar: String,
    size: Dp,
    photoUrl: String? = null,
    ring: Color? = null,
    modifier: Modifier = Modifier,
) {
    val preset = avatar.removePrefix("preset:").toIntOrNull()?.takeIf { avatar.startsWith("preset:") && it in PresetEmojis.indices }
    val base = modifier.size(size).let { m -> if (ring != null) m.border(2.dp, ring, CircleShape).padding(2.dp) else m }.clip(CircleShape)
    Box(base.background(gradientFor(preset ?: stableHash(userId))), contentAlignment = Alignment.Center) {
        when {
            photoUrl != null -> AsyncImage(model = photoUrl, contentDescription = nickname, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            preset != null -> Text(PresetEmojis[preset], style = glyphStyle((size.value * 0.5f).sp))
            else -> Text(
                nickname.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = glyphStyle((size.value * 0.42f).sp),
            )
        }
    }
}

/**
 * 圆里单个字/表情用：行高等于字号并裁掉上下留白，字形才真正居中。
 * 默认会继承主题的 24sp 行高，小头像里文字框比字高很多，居中的是框不是字，看起来往下偏。
 */
private fun glyphStyle(fontSize: TextUnit) = TextStyle(
    fontSize = fontSize,
    lineHeight = fontSize,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** 重叠排列的一排头像，超出部分显示 +N。 */
@Composable
fun AvatarStack(members: List<MemberView>, size: Dp, photoUrl: (MemberView) -> String?, max: Int = 5, ring: Color = Palette.Background) {
    // 用负间距真正重叠：offset 只挪绘制位置不缩布局宽度，头像一多会把右边的文字挤成竖排。
    Row(horizontalArrangement = Arrangement.spacedBy(-size * 0.3f)) {
        members.take(max).forEach { m ->
            Avatar(m.userId, m.nickname, m.avatar, size, photoUrl(m), ring = ring)
        }
        if (members.size > max) {
            Box(
                Modifier.size(size).clip(CircleShape).background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Text("+${members.size - max}", color = Palette.Muted, style = glyphStyle((size.value * 0.34f).sp), maxLines = 1) }
        }
    }
}
