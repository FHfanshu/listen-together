package app.listentogether.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 点赞特效：每个赞从点赞按钮处飘出一小簇大拇指，边飘边左右摇摆、先弹大再淡出。
 * 整层只有一个 Canvas，粒子是普通对象不进 Compose 状态；没有粒子时不跑帧循环，平时零开销。
 * 图标按固定尺寸画（矢量缓存成位图只做一次），大小差异靠 scale，染色靠 ColorFilter。
 *
 * @param likes 每次点赞的发起人 userId（自己的和别人的都有），颜色按人区分。
 * @param anchor 大拇指的出发点（根坐标系，像素），通常是点赞按钮中心。
 */
@Composable
fun LikeBurstLayer(likes: Flow<String>, anchor: () -> Offset?, modifier: Modifier = Modifier) {
    val particles = remember { ArrayList<Thumb>() }
    var frame by remember { mutableLongStateOf(0L) }
    val dp = LocalDensity.current.density
    val icon = rememberVectorPainter(Icons.Rounded.ThumbUp)
    val base = BASE_DP * dp
    val scope = rememberCoroutineScope()
    // 帧循环：有粒子时每帧推进一次并触发重绘，粒子全部消失后结束。
    // 不用 LaunchedEffect(running) 这种"开关当 key"的写法：上一轮刚结束、开关还没来得及重组时
    // 新的赞把开关又置回 true，key 看起来没变，循环就不会重启，粒子会冻在屏幕上。
    val loop = remember { arrayOfNulls<Job>(1) }

    LaunchedEffect(likes) {
        likes.collect { userId ->
            val from = anchor() ?: return@collect
            val tint = ThumbColors[abs(userId.hashCode()) % ThumbColors.size]
            val now = withFrameNanos { it }
            // 一个赞 3 个；屏幕上最多留 90 个，连点再快也不会越积越多。
            repeat(3) { if (particles.size < MAX_THUMBS) particles += Thumb.spawn(from, tint, dp, now) }
            if (loop[0]?.isActive != true) {
                loop[0] = scope.launch {
                    while (particles.isNotEmpty()) {
                        frame = withFrameNanos { it }
                        particles.removeAll { it.age(frame) > it.lifeNs }
                    }
                }
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val now = frame
        for (h in particles) {
            val t = (h.age(now).toFloat() / h.lifeNs).coerceIn(0f, 1f)
            val secs = h.age(now) / 1e9f
            // 上飘略带减速，左右正弦摆动
            val x = h.origin.x + h.vx * secs + sin(secs * h.wobbleHz * 2 * PI.toFloat() + h.phase) * h.wobblePx
            val y = h.origin.y - h.rise * (1 - (1 - t) * (1 - t))
            // 出生时 0.4→1.15→1 弹一下，最后 35% 淡出
            val pop = if (t < 0.15f) 0.4f + t / 0.15f * 0.75f else if (t < 0.25f) 1.15f - (t - 0.15f) / 0.1f * 0.15f else 1f
            val alpha = if (t > 0.65f) (1 - t) / 0.35f else 1f
            translate(x, y) {
                rotate(h.tilt * (1 - t), pivot = Offset.Zero) {
                    scale(h.size / base * pop, pivot = Offset.Zero) {
                        translate(-base / 2, -base / 2) {
                            with(icon) { draw(Size(base, base), alpha, ColorFilter.tint(h.color)) }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_THUMBS = 90
private const val BASE_DP = 28f

private val ThumbColors = listOf(
    Color(0xFFFF4D6D), Color(0xFFFF8FA3), Color(0xFFFFB347), Color(0xFFB57BFF),
    Color(0xFF5CC8FF), Color(0xFF4ADE80), Color(0xFFFF6B9A),
)

private class Thumb(
    val origin: Offset,
    val color: Color,
    val born: Long,
    val lifeNs: Long,
    val vx: Float,
    val rise: Float,
    val wobblePx: Float,
    val wobbleHz: Float,
    val phase: Float,
    val size: Float,
    val tilt: Float,
) {
    fun age(now: Long) = (now - born).coerceAtLeast(0)

    companion object {
        fun spawn(from: Offset, base: Color, dp: Float, now: Long): Thumb {
            val r = Random
            // 同一簇里颜色稍有深浅，看起来不那么呆板
            val shade = 0.85f + r.nextFloat() * 0.3f
            return Thumb(
                origin = from + Offset((r.nextFloat() - 0.5f) * 24 * dp, 0f),
                color = Color(
                    (base.red * shade).coerceAtMost(1f), (base.green * shade).coerceAtMost(1f), (base.blue * shade).coerceAtMost(1f),
                ),
                born = now,
                lifeNs = (1_300 + r.nextInt(700)) * 1_000_000L,
                vx = (r.nextFloat() - 0.5f) * 60 * dp,
                rise = (160 + r.nextFloat() * 140) * dp,
                wobblePx = (6 + r.nextFloat() * 10) * dp,
                wobbleHz = 0.8f + r.nextFloat() * 0.9f,
                phase = r.nextFloat() * 2 * PI.toFloat(),
                size = (18 + r.nextFloat() * 12) * dp,
                tilt = (r.nextFloat() - 0.5f) * 50,
            )
        }
    }
}
