package app.listentogether.player

// iOS 的播放器还不支持放映厅，YouTube 解析也先不做。
actual fun youTubeResolver(): LocalVideoResolver? = null
