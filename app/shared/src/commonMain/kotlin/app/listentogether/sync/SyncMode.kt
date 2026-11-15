package app.listentogether.sync

/**
 * 同步档位，每个人在本机自己选，只影响自己：大家各自对齐服务器时间，不互相比较。
 * 档位只是 [DriftController] 的一组参数；倍速设成 1.0 就等于不变速，只在误差够大时跳转。
 */
enum class SyncMode(
    val key: String,
    val deadZoneMs: Long,
    val settleMs: Long,
    val seekThresholdMs: Long,
    val slowRate: Float,
    val fastRate: Float,
) {
    /** 一起外放、连麦：误差 60ms 就开始用 ±3% 倍速追，超过 400ms 直接跳。 */
    PRECISE("precise", deadZoneMs = 60, settleMs = 20, seekThresholdMs = 400, slowRate = 0.97f, fastRate = 1.03f),

    /** 日常：150ms 内不管，±1% 倍速慢慢追，几乎听不出，超过 1 秒才跳。 */
    BALANCED("balanced", deadZoneMs = 150, settleMs = 40, seekThresholdMs = 1_000, slowRate = 0.99f, fastRate = 1.01f),

    /** 各听各的（参考 QQ 音乐一起听）：从不变速，差 3 秒以上才跳一次。 */
    RELAXED("relaxed", deadZoneMs = 3_000, settleMs = 0, seekThresholdMs = 3_000, slowRate = 1f, fastRate = 1f);

    fun controller() = DriftController(deadZoneMs, settleMs, seekThresholdMs, slowRate, fastRate)

    companion object {
        val DEFAULT = RELAXED

        fun of(key: String?): SyncMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
