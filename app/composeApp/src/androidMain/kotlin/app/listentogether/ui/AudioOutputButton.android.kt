package app.listentogether.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.android.ListenApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
actual fun AudioOutputButton(controller: AppController, modifier: Modifier) {
    val context = LocalContext.current
    val output = (context.applicationContext as ListenApp).output
    val state by output.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { output.showError(e.message ?: "连接失败，请稍后重试") }
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { open = true; output.scan() }) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(17.dp), tint = Palette.Muted)
            Text("  ${state.label}${if (state.error != null && state.device != null) " · 连接异常" else ""}", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!state.connecting) open = false },
        containerColor = Palette.SurfaceHigh,
        title = { Text("播放设备") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音箱与手机需连接同一 Wi-Fi。投放时使用宽松同步，不调整倍速。", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (state.device != null && !state.seekControlAvailable) Text("此音箱不支持进度同步，会从歌曲开头播放。", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                if (state.device != null && !state.volumeControlAvailable) Text("音量请用音箱按键调整；App 静音会暂停音箱播放。", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.connecting) {
                        act {
                            output.usePhone(); controller.refreshPlayback(); open = false
                        }
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = Palette.Muted)
                    Text("  本机 / 蓝牙", modifier = Modifier.weight(1f))
                    if (state.device == null) Icon(Icons.Rounded.Check, "当前输出", tint = Palette.Accent)
                }
                Text("DLNA 音箱", fontWeight = FontWeight.SemiBold)
                if (state.scanning || state.connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (state.connecting) "  正在连接…" else "  正在查找…", color = Palette.Muted)
                    }
                }
                state.devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !state.connecting) {
                            act { output.select(device); controller.refreshPlayback(); open = false }
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Speaker, null, tint = Palette.Muted)
                        Text("  ${device.name}", modifier = Modifier.weight(1f))
                        if (state.device?.id == device.id) Icon(Icons.Rounded.Check, "当前输出", tint = Palette.Accent)
                    }
                }
                if (!state.scanning && state.devices.isEmpty()) Text("还没找到设备，请确认音箱已开启 DLNA，且 Wi-Fi 没有隔离设备。", color = Palette.Muted)
                state.error?.let { Text(it, color = Palette.Accent, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { open = false }, enabled = !state.connecting) { Text("完成") } },
        dismissButton = { TextButton(onClick = output::scan, enabled = !state.scanning && !state.connecting) { Text("重新查找") } },
    )
}
