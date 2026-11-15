package app.listentogether.sync

/**
 * 大幅跳转的节奏控制，避免"越跳越慢"的死循环。
 *
 * 跳到缓冲区之外时播放器要重新联网，等它恢复出声，房间进度已经又走了这段时间，
 * 落后超过阈值就会再跳一次。所以这里：
 * - 跳完稳定下来后看落点差了多少，学出"跳转要提前多少"，下次一落地就对齐；
 * - 缓冲区内的跳转几乎是瞬间的，联网跳转（无损 FLAC 没有索引时要多次请求）可能要好几秒，
 *   两种分开学，否则缓冲区内跳转套用联网的提前量会跳过头，再往回跳，来回振荡；
 * - 短时间里跳了好几次，说明网络确实跟不上，拉长两次跳转的间隔，不再反复打断播放。
 */
class SeekPlanner(
    private val maxLeadMs: Long = 5_000,
    private val windowMs: Long = 20_000,
    private val freeSeeks: Int = 2,
    private val baseGapMs: Long = 2_000,
    private val maxGapMs: Long = 30_000,
) {
    /** 联网跳转预估的耗时，跳转目标往前提这么多。 */
    var leadMs: Long = 0
        private set

    /** 缓冲区内跳转的提前量，通常接近 0。 */
    var bufferedLeadMs: Long = 0
        private set

    /** 上一次跳转用的提前量和是否在缓冲区内，落地后用来校正。 */
    private var pending: Pair<Long, Boolean>? = null
    private val recent = ArrayDeque<Long>()

    fun reset() {
        pending = null
        recent.clear()
        // 提前量反映的是这台手机到 CDN 的网络状况，换歌也沿用。
    }

    /** 跳转目标。[inBuffer] 为 true 表示目标已经下载好，不用重新联网。 */
    fun target(expectedMs: Long, inBuffer: Boolean = false): Long = expectedMs + lead(inBuffer)

    private fun lead(inBuffer: Boolean) = if (inBuffer) bufferedLeadMs else leadMs

    /** 现在能不能再跳：最近跳得越多，要求隔得越久。 */
    fun canSeek(nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() > windowMs) recent.removeFirst()
        if (recent.size < freeSeeks) return true
        val extra = recent.size - freeSeeks
        val gap = (baseGapMs shl extra.coerceAtMost(4)).coerceAtMost(maxGapMs)
        return nowMs - recent.last() >= gap
    }

    fun onSeek(nowMs: Long, inBuffer: Boolean = false) {
        recent.addLast(nowMs)
        pending = lead(inBuffer) to inBuffer
    }

    /**
     * 跳转后播放稳定下来的第一次测量（drift = 实际 - 期望）。
     * 这次用的提前量减去落地误差，就是这次跳转真正需要的提前量。
     */
    fun onLanded(driftMs: Long) {
        val (used, inBuffer) = pending ?: return
        pending = null
        val needed = used - driftMs
        // 差得离谱的多半不是这次跳转造成的（比如中途切了后台、卡了缓冲），不计入。
        if (needed !in -maxLeadMs..maxLeadMs) return
        val sample = needed.coerceIn(0, maxLeadMs)
        // 平滑一下，偶尔一次特别慢不至于把之后都跳过头。
        if (inBuffer) bufferedLeadMs = (bufferedLeadMs + sample) / 2
        else leadMs = if (leadMs == 0L) sample else (leadMs * 2 + sample) / 3
    }
}
