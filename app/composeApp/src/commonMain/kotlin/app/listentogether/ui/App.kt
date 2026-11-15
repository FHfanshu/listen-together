package app.listentogether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.listentogether.AppController
import app.listentogether.net.ApiException
import app.listentogether.net.ServerProofException
import app.listentogether.net.RoomError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun App(controller: AppController) {
    AppTheme {
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val ui = remember(scope, snackbar) { UiActions(scope, snackbar) }
        LaunchedEffect(controller) {
            controller.messages.collect { snackbar.showSnackbar(it) }
        }

        val session by controller.session.collectAsState()
        val room by controller.room.collectAsState()
        var showProfile by remember { mutableStateOf(false) }
        // 房间界面是否展开；收起时房间照常播放，只在底部显示迷你播放栏。
        var roomOpen by remember { mutableStateOf(true) }
        var confirmLeave by remember { mutableStateOf(false) }
        // 进了新房间（创建、加入、启动时恢复）就展开。
        LaunchedEffect(room?.roomCode) { if (room != null) roomOpen = true }
        // 切换服务器后从大厅开始，不停在上次的资料页。
        LaunchedEffect(session == null) { if (session == null) showProfile = false }
        // 退出动画期间 room 已经是 null，用最后一份快照把房间界面画完。
        var lastRoom by remember { mutableStateOf(room) }
        if (room != null) lastRoom = room

        val screen = when {
            session == null -> Screen.Join
            room != null && roomOpen -> Screen.Room
            showProfile -> Screen.Profile
            else -> Screen.Lobby
        }
        val leave = { ui.run { controller.leaveRoom() } }

        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(it, containerColor = Palette.SurfaceHigh, contentColor = Palette.Text)
                }
            },
            contentWindowInsets = WindowInsets(0),
            containerColor = Palette.Background,
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    screen,
                    transitionSpec = {
                        when {
                            // 房间从底部升起 / 收起时沉下去，和迷你播放栏呼应。
                            targetState == Screen.Room ->
                                (slideInVertically(tween(320)) { it / 6 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                            initialState == Screen.Room ->
                                fadeIn(tween(220)) togetherWith (slideOutVertically(tween(280)) { it / 6 } + fadeOut(tween(200)))
                            else -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                        }
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Join -> JoinScreen(controller, ui)
                        Screen.Profile -> ProfileScreen(controller, ui, onClose = { showProfile = false })
                        Screen.Lobby -> LobbyScreen(
                            controller, ui,
                            onOpenProfile = { showProfile = true },
                            currentRoom = room?.roomCode,
                            onOpenCurrent = { roomOpen = true },
                            bottomInset = if (room != null) 88.dp else 0.dp,
                        )
                        Screen.Room -> lastRoom?.let {
                            if (it.isVideo) {
                                VideoRoomScreen(controller, it, ui, onMinimize = { roomOpen = false }, onLeave = { confirmLeave = true })
                            } else {
                                RoomScreen(controller, it, ui, onMinimize = { roomOpen = false }, onLeave = { confirmLeave = true })
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = room != null && screen != Screen.Room && screen != Screen.Join,
                    enter = slideInVertically(tween(300)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    lastRoom?.let { MiniPlayer(controller, it, onOpen = { roomOpen = true }, onLeave = { confirmLeave = true }) }
                }
            }
            if (confirmLeave && room != null) {
                LeaveRoomDialog(
                    room!!.roomCode,
                    onConfirm = {
                        confirmLeave = false
                        leave()
                    },
                    onDismiss = { confirmLeave = false },
                )
            }
        }
    }
}

private enum class Screen { Join, Lobby, Profile, Room }

/** 统一执行一个操作并把错误翻译成中文提示。 */
class UiActions(private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(describeError(e))
            }
        }
    }

    fun toast(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
}

fun describeError(e: Throwable): String = when (e) {
    is RoomError -> when (e.code) {
        "UNPLAYABLE" -> "这首歌暂时放不了（可能下架或需要会员）"
        "USER_LIMIT" -> "你点的歌太多了（服务器还是旧版本）"
        "QUEUE_FULL" -> "歌单已满"
        "ITEM_LOCKED" -> "下一首已经锁定，不能再改"
        "ROOM_FULL" -> "房间满了"
        "ROOM_NOT_FOUND" -> "没有这个房间"
        "NOT_FOUND" -> if (e.message?.contains("搜索") == true) e.message!! else "歌曲不存在"
        "FORBIDDEN" -> e.message?.takeIf { it != e.code } ?: "没有权限"
        "BAD_REQUEST" -> e.message?.takeIf { it != e.code } ?: "请求不对"
        "OFFLINE" -> "还没连上服务器，稍等一下"
        "RATE_LIMITED" -> e.message?.takeIf { it != e.code } ?: "操作太快了"
        else -> e.message ?: e.code
    }
    is ApiException -> when (e.code) {
        "INVITE_INVALID" -> "邀请码无效或已过期"
        "BAD_NICKNAME" -> "昵称不能为空，最多 32 个字"
        "BAD_AVATAR" -> "头像格式不支持"
        "AVATAR_TOO_LARGE" -> "图片太大了"
        "UNAUTHORIZED" -> "登录已失效"
        else -> e.message ?: e.code
    }
    is ServerProofException -> e.message ?: "服务器身份核对没通过"
    else -> "网络错误：" + (e.message ?: e::class.simpleName)
}
