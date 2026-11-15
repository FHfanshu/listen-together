package app.listentogether.ui

import androidx.compose.runtime.Composable

/**
 * 从相册选一张图，裁成正方形并压缩成约 256×256 的 JPEG 后回调。
 * 返回 null 表示当前平台还不支持（iOS 尚未实现）。
 */
@Composable
expect fun rememberImagePicker(onImage: (ByteArray) -> Unit): (() -> Unit)?
