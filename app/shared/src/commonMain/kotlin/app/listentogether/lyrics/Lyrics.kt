package app.listentogether.lyrics

/**
 * 一行歌词。
 * @param words 逐字时间（增强 LRC 的 `<mm:ss.xx>`）：每项是 (时间, 从这个字符下标开始)。没有就按整行估算进度。
 * @param translation 同一时间戳的第二行，一般是翻译。
 * @param endMs 这句唱完的时间（行尾再带一个时间戳的写法，如 `[00:02.25]歌词[00:04.50]`），没有就估算。
 */
data class LyricLine(
    val startMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<Pair<Long, Int>> = emptyList(),
    val endMs: Long? = null,
)

/** 解析好的一首歌的歌词，行按时间排好。 */
class Lyrics(val lines: List<LyricLine>) {

    /** pos 时正在唱的行；第一行之前是 -1。 */
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * 第 i 行在 pos 时唱到了多少（0..1）。有逐字时间就按字插值；
     * 没有就按字数估一个演唱时长（不超过到下一行的间隔），这样行后面跟着长间奏时也不会填得特别慢。
     */
    fun progress(i: Int, posMs: Long): Float {
        val line = lines.getOrNull(i) ?: return 0f
        if (posMs <= line.startMs) return 0f
        val len = line.text.length.coerceAtLeast(1)
        val nextStart = lines.getOrNull(i + 1)?.startMs
        if (line.words.isNotEmpty()) {
            val w = line.words
            if (posMs < w.first().first) return 0f
            for (k in w.indices) {
                val (t, c) = w[k]
                // 最后一个字唱到行尾时间为止；没有行尾时间就到下一行开始
                val (nt, nc) = w.getOrNull(k + 1) ?: ((line.endMs ?: nextStart ?: (t + 800)).coerceAtLeast(t + 1) to len)
                if (posMs < nt) return (c + (nc - c) * (posMs - t).toFloat() / (nt - t)) / len
            }
            return 1f
        }
        line.endMs?.let { end ->
            if (end > line.startMs) return ((posMs - line.startMs).toFloat() / (end - line.startMs)).coerceIn(0f, 1f)
        }
        val estimate = 600L + len * 280L
        val span = (if (nextStart != null) minOf(nextStart - line.startMs, estimate) else estimate).coerceAtLeast(1)
        return ((posMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    companion object {
        private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        // 逐字时间：增强 LRC 用 <mm:ss.xx>，有的平台（如网易云逐字歌词转出来的）直接在行内用 [mm:ss.xx]
        private val wordStamp = Regex("""[<\[](\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?[>\]]""")
        private val endStamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]\s*$""")
        private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

        /** 解析 LRC；没有任何带时间的行时返回 null（纯文本歌词没法跟进度，不显示）。 */
        fun parse(lrc: String): Lyrics? {
            // LRC 规范里 offset 为正表示歌词提前出现。
            val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val raw = ArrayList<LyricLine>()
            for (rawLine in lrc.lineSequence()) {
                val line = rawLine.trim()
                var rest = line
                val times = ArrayList<Long>()
                while (true) {
                    val m = stamp.matchAt(rest, 0) ?: break
                    times += toMs(m) - offset
                    rest = rest.substring(m.range.last + 1)
                }
                if (times.isEmpty()) continue
                // 行尾的时间戳是这句的结束时间
                val end = endStamp.find(rest)?.let { m -> rest = rest.substring(0, m.range.first); toMs(m) - offset }
                val (text, words) = splitWords(rest.trim())
                if (text.isBlank()) {
                    // 空行常用来表示这一句结束、进入间奏，保留成空行让上一句及时熄灭。
                    times.forEach { raw += LyricLine(it.coerceAtLeast(0), "") }
                    continue
                }
                times.forEach { t ->
                    val shifted = words.map { (wt, c) -> (wt - offset).coerceAtLeast(0) to c }
                    raw += LyricLine(
                        t.coerceAtLeast(0), text,
                        // 第一个词前面通常没有时间戳（行首时间就是它的），补上，不然第一个词不会填色
                        words = if (shifted.isNotEmpty() && shifted.first().second > 0) listOf(t.coerceAtLeast(0) to 0) + shifted else shifted,
                        // 一行多个开始时间时结束时间只对第一个有意义
                        endMs = end?.takeIf { times.size == 1 && it > t },
                    )
                }
            }
            if (raw.none { it.text.isNotEmpty() }) return null
            raw.sortBy { it.startMs } // 稳定排序，同时间戳的原文和翻译保持原来的先后
            // 同一时间戳的第二行当翻译并进上一行
            val merged = ArrayList<LyricLine>()
            for (l in raw) {
                val last = merged.lastOrNull()
                if (last != null && last.startMs == l.startMs && last.text.isNotEmpty() && l.text.isNotEmpty()) {
                    // 有的歌词同一句会重复好几遍，重复的不当翻译
                    if (last.translation == null && l.text != last.text) merged[merged.size - 1] = last.copy(translation = l.text)
                    continue
                }
                if (last != null && last.startMs == l.startMs && l.text.isEmpty()) continue
                merged += l
            }
            // 开头的空行、连续空行没意义
            val cleaned = merged.filterIndexed { i, l -> l.text.isNotEmpty() || (i > 0 && merged[i - 1].text.isNotEmpty()) }
            return Lyrics(cleaned)
        }

        private fun splitWords(s: String): Pair<String, List<Pair<Long, Int>>> {
            if (!wordStamp.containsMatchIn(s)) return s to emptyList()
            val sb = StringBuilder()
            val words = ArrayList<Pair<Long, Int>>()
            var i = 0
            for (m in wordStamp.findAll(s)) {
                sb.append(s, i, m.range.first)
                words += toMs(m) to sb.length
                i = m.range.last + 1
            }
            sb.append(s, i, s.length)
            val text = sb.toString()
            // 末尾那个时间戳通常表示整行结束，下标等于长度，正好当作插值终点
            return text.trim() to words.map { (t, c) -> t to (c - (text.length - text.trimStart().length)).coerceIn(0, text.trim().length) }
        }

        private fun toMs(m: MatchResult): Long {
            val (min, sec, frac) = m.destructured
            val f = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.toLong()
            }
            return min.toLong() * 60_000 + sec.toLong() * 1_000 + f
        }
    }
}
