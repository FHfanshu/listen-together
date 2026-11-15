package app.listentogether.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 放映厅的画面：把播放器的视频输出接到这块区域，按视频比例居中显示。 */
@Composable
expect fun VideoSurface(modifier: Modifier = Modifier)

/** 全屏时隐藏系统栏，退出全屏恢复。landscape 为 true 时横过来；竖屏短视频沉浸看时保持竖屏。 */
@Composable
expect fun FullscreenEffect(fullscreen: Boolean, landscape: Boolean = true)
