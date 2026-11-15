package app.listentogether.sync

import kotlin.math.abs

sealed interface DriftAction {
    data object None : DriftAction
    data class SetRate(val rate: Float) : DriftAction
    data class Seek(val positionMs: Long) : DriftAction
}

/**
 * 纠偏策略（与规格一致）：
 * - 误差 < 60ms：不动；
 * - 60–400ms：用 0.97 / 1.03 倍速慢慢追，误差回到 20ms 内恢复 1.0；
 * - > 400ms：直接跳到期望位置。
 * drift = 实际位置 - 期望位置，正数表示播快了。
 */
class DriftController(
    private val deadZoneMs: Long = 60,
    private val settleMs: Long = 20,
    private val seekThresholdMs: Long = 400,
    private val slowRate: Float = 0.97f,
    private val fastRate: Float = 1.03f,
) {
    var currentRate: Float = 1f
        private set

    fun reset() {
        currentRate = 1f
    }

    fun decide(expectedMs: Long, actualMs: Long): DriftAction {
        val drift = actualMs - expectedMs
        val mag = abs(drift)
        if (mag > seekThresholdMs) {
            return setRate(1f).let { DriftAction.Seek(expectedMs) }
        }
        if (currentRate != 1f) {
            // 正在调速：追上了就恢复，方向反了就换向。
            return when {
                mag <= settleMs -> setRate(1f)
                drift > 0 && currentRate > 1f -> setRate(slowRate)
                drift < 0 && currentRate < 1f -> setRate(fastRate)
                else -> DriftAction.None
            }
        }
        if (mag < deadZoneMs) return DriftAction.None
        return setRate(if (drift > 0) slowRate else fastRate)
    }

    private fun setRate(rate: Float): DriftAction {
        if (rate == currentRate) return DriftAction.None
        currentRate = rate
        return DriftAction.SetRate(rate)
    }
}
