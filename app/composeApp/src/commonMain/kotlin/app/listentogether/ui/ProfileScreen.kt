package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.listentogether.AppController

@Composable
fun ProfileScreen(controller: AppController, ui: UiActions, onClose: () -> Unit) {
    val session by controller.session.collectAsState()
    val s = session ?: return
    var nickname by remember { mutableStateOf(s.nickname) }
    // 选中但还没保存的内置头像；照片选好后直接上传，不经过这里。
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shown = pending ?: s.avatar
    val pickImage = rememberImagePicker { bytes ->
        busy = true
        ui.run {
            try {
                controller.uploadAvatar(bytes)
                pending = null
                ui.toast("头像已更新")
            } finally {
                busy = false
            }
        }
    }
    PlatformBackHandler(onBack = onClose)
    val dirty = nickname.trim() != s.nickname || (pending != null && pending != s.avatar)

    Column(
        Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars).imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Palette.Text) }
            Text("个人资料", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        }

        Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(
                s.userId, nickname.ifBlank { s.nickname }, shown, 104.dp,
                photoUrl = if (pending == null) controller.avatarUrl(s.userId, s.avatar) else null,
                ring = Palette.Accent,
            )
            Spacer(Modifier.height(14.dp))
            if (pickImage != null) {
                Row(
                    Modifier.clip(CircleShape).background(Palette.Surface).clickable(enabled = !busy) { pickImage() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "正在上传…" else "用相册里的照片", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "照片会裁成小方图，只有几十 KB",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Field(nickname, { nickname = it.take(32) }, "昵称")

            Text("或者选一个头像", style = MaterialTheme.typography.titleMedium)
            val options = listOf("") + PresetEmojis.indices.map { "preset:$it" }
            options.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { opt ->
                        val selected = shown == opt
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                                .border(2.dp, if (selected) Palette.Accent else Color.Transparent, CircleShape)
                                .clickable { pending = opt }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Avatar(s.userId, nickname.ifBlank { s.nickname }, opt, 52.dp)
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                "第一个是按你的名字自动生成的默认头像。",
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            )

            GradientButton("保存", enabled = dirty && !busy && nickname.isNotBlank()) {
                busy = true
                ui.run {
                    try {
                        controller.updateProfile(
                            nickname = nickname.trim().takeIf { it != s.nickname },
                            avatar = pending?.takeIf { it != s.avatar },
                        )
                        pending = null
                        ui.toast("已保存")
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
