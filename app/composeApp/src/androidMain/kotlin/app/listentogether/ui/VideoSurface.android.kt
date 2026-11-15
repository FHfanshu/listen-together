package app.listentogether.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import app.listentogether.android.ListenApp

@Composable
actual fun VideoSurface(modifier: Modifier) {
    val context = LocalContext.current
    val exo = remember(context) { (context.applicationContext as ListenApp).player.exo }
    var ratio by remember { mutableFloatStateOf(ratioOf(exo.videoSize)) }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                ratio = ratioOf(videoSize)
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { SurfaceView(it).also(exo::setVideoSurfaceView) },
            onRelease = { exo.clearVideoSurfaceView(it) },
            modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio > 1f).fillMaxSize(),
        )
    }
}

private fun ratioOf(size: VideoSize): Float =
    if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f

@Composable
actual fun FullscreenEffect(fullscreen: Boolean, landscape: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(fullscreen, landscape) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
