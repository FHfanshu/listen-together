package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.RoomKind
import app.listentogether.model.RoomSummary
import kotlinx.coroutines.delay

@Composable
fun JoinScreen(controller: AppController, ui: UiActions) {
    var server by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hasSaved = controller.servers.collectAsState().value.isNotEmpty()

    // 复制了朋友发来的邀请（「服务器地址：… 邀请码：…」）再打开 App，自动填好地址和邀请码。
    val clip = rememberClipboardText()
    LaunchedEffect(clip) {
        val c = clip?.trim().orEmpty()
        val parsed = parseInvite(c) ?: return@LaunchedEffect
        if (!ClipMemory.takeOnce(c)) return@LaunchedEffect
        var filled = false
        parsed.server?.let { if (server.isBlank() || server != it) { server = it; filled = true } }
        parsed.invite?.let { if (invite != it) { invite = it; filled = true } }
        if (filled) ui.toast(if (parsed.server != null && parsed.invite != null) "已从剪贴板填入服务器地址和邀请码" else "已从剪贴板填入")
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xFF3A1420), 0.45f to Palette.Background),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(Palette.AccentGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("一起听", style = MaterialTheme.typography.headlineLarge)
            Text("和朋友同一时刻听同一首歌", color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(if (hasSaved) 28.dp else 40.dp))
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SavedServers(controller, ui)
                if (hasSaved) {
                    Text("用邀请码加入新服务器", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                }
                Field(server, { server = it }, "服务器地址", "https://…", KeyboardOptions(keyboardType = KeyboardType.Uri))
                Field(invite, { invite = it.uppercase() }, "邀请码", "8 位字母数字", KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Field(nickname, { nickname = it.take(32) }, "昵称", "朋友们会看到这个名字")
                Spacer(Modifier.height(6.dp))
                GradientButton(
                    text = if (busy) "正在加入…" else "加入",
                    enabled = !busy && server.isNotBlank() && invite.isNotBlank() && nickname.isNotBlank(),
                ) {
                    busy = true
                    ui.run {
                        try {
                            controller.join(server, invite, nickname)
                        } finally {
                            busy = false
                        }
                    }
                }
                Text(
                    "地址和邀请码由搭建服务器的朋友发给你，不需要音乐会员。",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.Muted.copy(alpha = 0.6f)) } },
        keyboardOptions = keyboard,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Palette.Surface,
            focusedContainerColor = Palette.Surface,
            unfocusedBorderColor = Palette.Outline,
            focusedBorderColor = Palette.Accent,
            focusedLabelColor = Palette.Accent,
        ),
    )
}

@Composable
fun GradientButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(press, 0.97f).height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Palette.AccentGradient else Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.SurfaceHigh)))
            .pressClick(press, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                it()
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) Color.White else Palette.Muted, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LobbyScreen(
    controller: AppController,
    ui: UiActions,
    onOpenProfile: () -> Unit,
    currentRoom: String? = null,
    onOpenCurrent: () -> Unit = {},
    bottomInset: Dp = 0.dp,
) {
    // 点的是自己已经在的房间就直接展开，不用重新加入。
    val enter = { code: String -> if (code == currentRoom) onOpenCurrent() else ui.run { controller.joinRoom(code) } }
    val session by controller.session.collectAsState()
    val connected by controller.connected.collectAsState()
    var rooms by remember { mutableStateOf<List<RoomSummary>>(emptyList()) }
    var code by remember { mutableStateOf("") }

    LaunchedEffect(connected) {
        while (connected) {
            runCatching { rooms = controller.rooms() }
            delay(5_000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("一起听", style = MaterialTheme.typography.headlineMedium)
                    ServerChip(controller, connected)
                }
                session?.let { s ->
                    Avatar(
                        s.userId, s.nickname, s.avatar, 44.dp, controller.avatarUrl(s.userId, s.avatar),
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenProfile),
                    )
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("开一个房间，叫朋友进来", style = MaterialTheme.typography.titleMedium)
                GradientButton(
                    "一起听歌", enabled = connected,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White) },
                ) { ui.run { controller.createRoom(RoomKind.MUSIC) } }
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.pressScale(press).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(Palette.SurfaceHigh).pressClick(press, connected) { ui.run { controller.createRoom(RoomKind.VIDEO) } },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = if (connected) Palette.Text else Palette.Muted)
                    Text(
                        "  开放映厅 · 一起看 B 站",
                        color = if (connected) Palette.Text else Palette.Muted, fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        code, { v -> code = v.filter { it.isDigit() }.take(6) }, "房间号", "6 位数字",
                        KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
                    )
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.pressScale(press).size(56.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (connected && code.length == 6) Palette.Accent else Palette.SurfaceHigh)
                            .pressClick(press, connected && code.length == 6) { enter(code) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ChevronRight, contentDescription = "加入", tint = Color.White) }
                }
            }
        }
        item {
            Text("正在一起听", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        }
        if (rooms.isEmpty()) {
            item {
                Text("现在还没有房间，创建一个吧", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(rooms, key = { it.roomCode }) { r ->
            RoomCard(r, connected, current = r.roomCode == currentRoom, Modifier.animateItem()) { enter(r.roomCode) }
        }
    }
}

@Composable
private fun RoomCard(r: RoomSummary, enabled: Boolean, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(press, 0.98f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
            .border(1.dp, if (current) Palette.Accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(18.dp))
            .pressClick(press, enabled, onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(r.nowPlaying, 60.dp, corner = 12.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            val what = if (r.kind == RoomKind.VIDEO) "放映厅" else "房间"
            Text(
                if (current) "你在这个$what" else "${r.ownerNickname} 的$what",
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) Palette.Accent else Palette.Text,
            )
            Text(
                r.nowPlaying?.let { "${it.title} · ${it.artistText}" } ?: if (r.kind == RoomKind.VIDEO) "还没开始放视频" else "还没开始放歌",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ConnectionDot(true)
                Text("${r.onlineCount} 人在${if (r.kind == RoomKind.VIDEO) "看" else "听"} · 房间号 ${r.roomCode}", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Palette.Muted)
    }
}

@Composable
fun ConnectionDot(connected: Boolean) {
    Box(
        Modifier.padding(end = 6.dp).size(7.dp).background(
            if (connected) Palette.Online else Color(0xFFF59E0B),
            CircleShape,
        ),
    )
}

/**
 * 标题下的连接状态，同时是当前服务器的入口：点开显示地址和"切换服务器"。
 * 切换会断开当前房间，所以先弹菜单，不做成一点就切。
 */
@Composable
private fun ServerChip(controller: AppController, connected: Boolean) {
    val session by controller.session.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val host = session?.let { hostOf(it.baseUrl) }
    Box {
        Row(
            Modifier.padding(top = 2.dp).clip(CircleShape).clickable { menu = true }.padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionDot(connected)
            Text(
                (if (connected) "已连接" else "正在连接") + (host?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = "服务器", tint = Palette.Muted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
        ) {
            host?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            DropdownMenuItem(
                text = { Text("切换服务器") },
                leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = Palette.Muted) },
                onClick = { menu = false; controller.switchServer() },
            )
        }
    }
}
