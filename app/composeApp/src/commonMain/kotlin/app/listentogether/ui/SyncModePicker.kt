package app.listentogether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.sync.SyncMode

val SyncMode.title: String
    get() = when (this) {
        SyncMode.PRECISE -> "精准"
        SyncMode.BALANCED -> "均衡"
        SyncMode.RELAXED -> "宽松"
    }

/** 按使用场景说，不讲参数。 */
private val SyncMode.hint: String
    get() = when (this) {
        SyncMode.PRECISE -> "一起外放、连麦时用，误差控制在零点几秒内。偶尔会轻微变速，大家都要切到这一档才有用。"
        SyncMode.BALANCED -> "误差大时用几乎听不出的变速慢慢追，差太多才跳一下。"
        SyncMode.RELAXED -> "各戴耳机各听各的，最省心：从不变速，差 3 秒以上才跳一下。"
    }

/**
 * 播放页 ⋯ 里的"同步档位"面板：三张选项卡，点一下立即生效。
 * 设置存在本机、对所有房间生效，只影响自己这台手机。
 */
@Composable
fun SyncModeSheet(controller: AppController, visible: Boolean, onDismiss: () -> Unit) {
    val current by controller.syncMode.collectAsState()
    BottomSheet(visible, onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("同步档位", style = MaterialTheme.typography.titleMedium)
            Text(
                "只影响你自己：每个人各自对齐服务器时间，不会把别人带偏。对所有房间生效。",
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            )
            listOf(SyncMode.RELAXED, SyncMode.BALANCED, SyncMode.PRECISE).forEach { mode ->
                SyncModeOption(mode, selected = mode == current) { controller.setSyncMode(mode) }
            }
        }
    }
}

@Composable
private fun SyncModeOption(mode: SyncMode, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, label = "border")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 单选圆点：选中的实心
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) Palette.Accent else Palette.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mode.title, fontWeight = FontWeight.SemiBold)
                if (mode == SyncMode.DEFAULT) {
                    Text(
                        "默认", style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(mode.hint, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
