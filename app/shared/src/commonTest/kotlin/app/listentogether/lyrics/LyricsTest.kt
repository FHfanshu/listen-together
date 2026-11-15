package app.listentogether.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun parsesTagsMultiStampsAndTranslation() {
        val lrc = """
            [ti:歌名]
            [ar:歌手]
            [00:01.00]第一句
            [00:01.00]First line
            [00:05.5][00:20.00]副歌
            [00:10.123]第三句
        """.trimIndent()
        val l = Lyrics.parse(lrc)!!
        assertEquals(listOf(1_000L, 5_500L, 10_123L, 20_000L), l.lines.map { it.startMs })
        assertEquals("第一句", l.lines[0].text)
        assertEquals("First line", l.lines[0].translation)
        assertEquals("副歌", l.lines[3].text)
    }

    @Test
    fun indexAndLineProgress() {
        val l = Lyrics.parse("[00:01.00]一二三四\n[00:10.00]下一句")!!
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(1_000))
        assertEquals(0, l.indexAt(9_999))
        assertEquals(1, l.indexAt(10_000))
        // 4 个字估 600+4*280=1720ms 唱完，不会拖到下一行的 9 秒
        assertEquals(0.5f, l.progress(0, 1_000 + 860), 0.01f)
        assertEquals(1f, l.progress(0, 5_000))
    }

    @Test
    fun wordTimestamps() {
        val l = Lyrics.parse("[00:01.00]<00:01.00>ab<00:02.00>cd<00:03.00>")!!
        assertEquals("abcd", l.lines[0].text)
        assertEquals(0.25f, l.progress(0, 1_500), 0.01f)
        assertEquals(0.75f, l.progress(0, 2_500), 0.01f)
        assertEquals(1f, l.progress(0, 3_100), 0.01f)
    }

    @Test
    fun trailingEndStampAndDuplicates() {
        val l = Lyrics.parse("[00:00.00]作词 : 某人[00:00.00]\n[00:00.00]作词 : 某人[00:02.25]\n[00:02.25]词：周杰伦[00:04.50]")!!
        assertEquals(listOf("作词 : 某人", "词：周杰伦"), l.lines.map { it.text })
        assertNull(l.lines[0].translation)
        assertEquals(4_500, l.lines[1].endMs)
        assertEquals(0.5f, l.progress(1, 3_375), 0.01f)
    }

    @Test
    fun inlineSquareBracketWordStamps() {
        // Airplanes 的逐词歌词：每个词前面是 [mm:ss.xx]，行尾是结束时间
        val l = Lyrics.parse("[00:14.50]I [00:14.84]could [00:15.08]really [00:15.35]use [00:15.62]a [00:15.71]wish [00:16.04]right [00:16.31]now [00:16.97]([00:16.97]wish [00:17.33]right [00:17.60]now[00:18.29])[00:18.29]")!!
        val line = l.lines.single()
        assertEquals("I could really use a wish right now (wish right now)", line.text)
        assertEquals(18_290, line.endMs)
        assertEquals(0f, l.progress(0, 14_500), 0.001f)
        // "I " 在 14.50~14.84 之间填，过半时还没到 could
        val p = l.progress(0, 14_670)
        assertTrue(p > 0f && p < "I ".length.toFloat() / line.text.length)
        // 唱到 could 开头时，前面的 "I " 已经填满
        assertEquals(2f / line.text.length, l.progress(0, 14_840), 0.001f)
        assertEquals(1f, l.progress(0, 18_300), 0.001f)
    }

    @Test
    fun offsetAndPlainText() {
        assertNull(Lyrics.parse("纯文本歌词\n没有时间"))
        val l = Lyrics.parse("[offset:500]\n[00:02.00]早半秒")!!
        assertEquals(1_500, l.lines[0].startMs)
        assertTrue(l.lines.size == 1)
    }
}
