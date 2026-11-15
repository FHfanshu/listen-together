package app.listentogether.sync

import kotlin.time.TimeSource

/** 单调时钟（毫秒）。不用系统时间，避免用户改时间或 NTP 校时造成跳变。 */
fun interface MonoClock {
    fun nowMs(): Long

    companion object {
        val System: MonoClock = run {
            val start = TimeSource.Monotonic.markNow()
            MonoClock { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

data class SyncSample(val offsetMs: Long, val rttMs: Long)

/**
 * NTP 式对时：本地发出 t0，服务器收到 t1、回复 t2，本地收到 t3。
 * offset = ((t1 - t0) + (t2 - t3)) / 2，取最近若干次中往返时间最短的一次，它受网络排队影响最小。
 */
class ClockSync(private val clock: MonoClock = MonoClock.System, private val window: Int = 16) {
    private val samples = ArrayDeque<SyncSample>()

    var best: SyncSample? = null
        private set

    val isSynced: Boolean get() = best != null

    fun localNowMs(): Long = clock.nowMs()

    fun addSample(t0: Long, t1: Long, t2: Long, t3: Long): SyncSample {
        val sample = SyncSample(
            offsetMs = ((t1 - t0) + (t2 - t3)) / 2,
            rttMs = (t3 - t0) - (t2 - t1),
        )
        samples.addLast(sample)
        while (samples.size > window) samples.removeFirst()
        best = samples.minBy { it.rttMs }
        return sample
    }

    /** 换了网络后旧样本不再可信。 */
    fun reset() {
        samples.clear()
        best = null
    }

    /** 当前服务器时间；未对时前退化为本地时钟。 */
    fun serverNowMs(): Long = clock.nowMs() + (best?.offsetMs ?: 0)
}
