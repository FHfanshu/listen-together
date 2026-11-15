package app.listentogether.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.Session

/**
 * 加入页顶部"我的服务器"：之前加入过的服务器，点一下连回去，不用再输邀请码。
 * 头像只画预设/首字母，不拉照片——照片地址带 token，没核对身份前不能发给那个地址。
 */
@Composable
fun SavedServers(controller: AppController, ui: UiActions) {
    val servers by controller.servers.collectAsState()
    if (servers.isEmpty()) return
    var busy by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Session?>(null) }
    var forgetting by remember { mutableStateOf<Session?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("我的服务器", style = MaterialTheme.typography.titleMedium)
        servers.forEach { s ->
            ServerRow(
                s, busy = busy == s.token,
                onOpen = {
                    if (busy == null) {
                        busy = s.token
                        ui.run { try { controller.openServer(s) } finally { busy = null } }
                    }
                },
                onEdit = { editing = s },
                onForget = { forgetting = s },
            )
        }
    }

    editing?.let { s ->
        EditAddressDialog(
            s,
            onConfirm = { url ->
                editing = null
                busy = s.token
                ui.run { try { controller.openServer(s, url) } finally { busy = null } }
            },
            onDismiss = { editing = null },
        )
    }
    forgetting?.let { s ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            containerColor = Palette.SurfaceHigh,
            title = { Text("忘记这台服务器？") },
            text = {
                Text(
                    "本机会删掉你在 ${hostOf(s.baseUrl)} 上的身份「${s.nickname}」。以后要回去，得找朋友要新的邀请码。",
                    color = Palette.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { controller.forgetServer(s); forgetting = null }) {
                    Text("忘记", color = Palette.Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消", color = Palette.Text) } },
        )
    }
}

@Composable
private fun ServerRow(s: Session, busy: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)
            .clickable(enabled = !busy, onClick = onOpen).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s.userId, s.nickname, s.avatar, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.nickname, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                hostOf(s.baseUrl), style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Palette.Muted)
                }
            }
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("修改地址") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = Palette.Muted) },
                    onClick = { menu = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("忘记这台服务器", color = Palette.Accent) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Palette.Accent) },
                    onClick = { menu = false; onForget() },
                )
            }
        }
    }
}

@Composable
private fun EditAddressDialog(s: Session, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(s.baseUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("修改服务器地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "服务器换了地址时填新地址。会先核对新地址上是不是同一台服务器，确认后才用你的身份登录。",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Field(url, { url = it.trim() }, "新地址", "https://…", KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text("核对并连接", color = Palette.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.Text) } },
    )
}

internal fun hostOf(url: String) = url.substringAfter("://").trimEnd('/')
