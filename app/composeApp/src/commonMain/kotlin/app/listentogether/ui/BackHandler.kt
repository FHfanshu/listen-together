package app.listentogether.ui

import androidx.compose.runtime.Composable

/** 系统返回键/手势（Android）；iOS 没有系统返回键，实现为空。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
