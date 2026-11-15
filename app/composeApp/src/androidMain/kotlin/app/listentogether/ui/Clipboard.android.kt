package app.listentogether.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

// 上次读过的剪贴板时间戳，进程内共享：不同页面不用各读一遍。
private var lastStamp = -1L
private var lastText: String? = null

@Composable
actual fun rememberClipboardText(): String? {
    val view = LocalView.current
    var text by remember { mutableStateOf(lastText) }
    DisposableEffect(view) {
        val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        fun read() {
            // Android 10 起只有拿到焦点的窗口能读剪贴板。
            if (!view.hasWindowFocus()) return
            val desc = cm.primaryClipDescription ?: return
            if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return
            // 时间戳没变说明还是上次那段，不再读，免得系统反复提示「已粘贴」。
            if (desc.timestamp == lastStamp) {
                text = lastText
                return
            }
            val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(view.context)?.toString() }.getOrNull()
            lastStamp = desc.timestamp
            lastText = clip?.take(2_000)
            text = lastText
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { if (it) read() }
        val changed = ClipboardManager.OnPrimaryClipChangedListener { read() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        cm.addPrimaryClipChangedListener(changed)
        view.post { read() }
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            cm.removePrimaryClipChangedListener(changed)
        }
    }
    return text
}
