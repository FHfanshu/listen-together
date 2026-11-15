package app.listentogether.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.listentogether.ui.App
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as ListenApp
    private val prefs by lazy { getSharedPreferences("listen_together", MODE_PRIVATE) }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBatteryOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 界面固定深色，系统栏图标用浅色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { App(app.controller) }
        // 第一次进房间时要通知权限（常驻通知 = 前台服务，后台切歌才不会断），再请求忽略电池优化。
        lifecycleScope.launch {
            app.controller.room.map { it != null }.distinctUntilChanged().collect { inRoom ->
                if (inRoom) askKeepAlive()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 之前在后台时没能启动前台服务的话，回到前台补上。
        if (app.controller.room.value != null) RoomService.start(this)
    }

    private fun askKeepAlive() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_ASKED_NOTIFY, false)
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFY, true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOnce()
        }
    }

    /** 部分手机后台杀得很凶，问一次能不能不做电池优化；拒绝了就不再打扰。 */
    @SuppressLint("BatteryLife")
    private fun askBatteryOnce() {
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private companion object {
        const val KEY_ASKED_NOTIFY = "asked_notify"
        const val KEY_ASKED_BATTERY = "asked_battery"
    }
}
