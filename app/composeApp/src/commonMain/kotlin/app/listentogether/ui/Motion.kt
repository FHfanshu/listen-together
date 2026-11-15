package app.listentogether.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.ripple
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 按下回弹：按下时略微缩小，松手弹回。缩放走 graphicsLayer，只重绘不重排。
 * 要放在修饰链最前面（clip/background 之前）才能整块一起缩；点击用 [pressClick] 共享同一个 source。
 */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.94f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        if (pressed) tween(90) else spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

fun Modifier.pressClick(source: MutableInteractionSource, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClick = onClick)
}

/**
 * 正在播放的小跳动条。只有 playing 时才跑动画，暂停时静止成矮条，不占帧。
 * 三根条用同一个相位驱动，各自错开，一个 Canvas 画完。
 */
@Composable
fun EqualizerBars(playing: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val phase = if (playing) {
        val t = rememberInfiniteTransition(label = "eq")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "eqPhase")
    } else {
        null
    }
    Canvas(modifier.size(size)) {
        val p = phase?.value
        val barW = this.size.width / 5f
        for (i in 0 until 3) {
            val h = if (p == null) 0.3f else 0.3f + 0.7f * (0.5f + 0.5f * sin((p + i * 0.33f) * 2 * PI.toFloat() * (1 + i * 0.35f))).toFloat()
            val barH = this.size.height * h
            drawRoundRect(
                color,
                topLeft = Offset(barW * (i * 2), this.size.height - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}
