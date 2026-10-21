package top.izuna.foliamajor.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PureMusicDetector], mirroring the desktop suite at
 * `test/unit/lyrics/pureMusic.test.ts`.
 *
 * The failure modes this guards against are asymmetric: a false positive sends a song title to the
 * theme generator instead of its lyrics (a worse theme), while a false negative sends an empty
 * string and fails the generation outright. The desktop suite errs toward the former, and so does
 * this one.
 */
class PureMusicDetectorTest {

    @Test
    fun `recognises the notice in every common spelling`() {
        val notices = listOf(
            "纯音乐，请欣赏",
            "纯音乐请欣赏",
            "纯音乐,请欣赏",
            "纯音乐 请欣赏",
            "纯音乐，请欣赏。",
            "纯音乐，请您欣赏",
            "此歌曲为没有填词的纯音乐，请您欣赏",
            "此歌曲为没有填词的纯音乐，请您欣赏！",
            "純音樂，請欣賞",
        )

        for (notice in notices) {
            assertTrue("bare: $notice", PureMusicDetector.isPureMusicLyricText(notice))
            assertTrue("timestamped: $notice", PureMusicDetector.isPureMusicLyricText("[00:00.00]$notice"))
            assertTrue("spaced: $notice", PureMusicDetector.isPureMusicLyricText("[00:00.000] $notice\n"))
        }
    }

    @Test
    fun `recognises the notice when KRC splits it per character`() {
        val krc = "[id:\$00000000]\n[ti:某曲]\n" +
            "[0,5000]<0,500,0>纯<500,500,0>音<1000,500,0>乐<1500,500,0>，" +
            "<2000,500,0>请<2500,500,0>欣<3000,500,0>赏"

        assertTrue(PureMusicDetector.isPureMusicLyricText(krc))
    }

    @Test
    fun `recognises the notice behind credit metadata lines`() {
        val lrc = listOf(
            "[00:00.00] 作曲 : 某某",
            "[00:01.00] 作词 : 某某",
            "[00:02.00]编曲：某某",
            "[00:03.00]纯音乐，请欣赏",
        ).joinToString("\n")

        assertTrue(PureMusicDetector.isPureMusicLyricText(lrc))
    }

    @Test
    fun `recognises the notice behind credits without colons`() {
        val lrc = listOf(
            "[00:00.00]编曲 某某",
            "[00:01.00]制作人 某某",
            "[00:02.00]Music by Foo",
            "[00:03.00]纯音乐，请欣赏",
        ).joinToString("\n")

        assertTrue(PureMusicDetector.isPureMusicLyricText(lrc))
        assertTrue(PureMusicDetector.isPureMusicLyricText("[00:00.00]鼓 声音很响\n[00:03.00]纯音乐，请欣赏"))
        assertFalse(PureMusicDetector.isPureMusicLyricText("[00:00.00]我爱你 很久了\n[00:03.00]纯音乐，请欣赏"))
    }

    @Test
    fun `recognises the notice behind a title line and JSON metadata`() {
        val lrc = listOf(
            """{"t":0,"c":[{"tx":"作曲: "}]}""",
            "[00:00.00]某曲 - 某人",
            "[00:01.00]纯音乐，请欣赏",
        ).joinToString("\n")

        assertTrue(PureMusicDetector.isPureMusicLyricText(lrc))
    }

    @Test
    fun `empty or absent lyrics are not instrumental`() {
        assertFalse(PureMusicDetector.isPureMusicLyricText(null))
        assertFalse(PureMusicDetector.isPureMusicLyricText(""))
        assertFalse(PureMusicDetector.isPureMusicLyricText("[00:00.00]\n[00:05.00]"))
        assertFalse(
            PureMusicDetector.isPureMusicLyricText("[00:00.00] 作曲 : 某某\n[00:01.00] 作词 : 某某")
        )
    }

    @Test
    fun `a normal song with leading credits is not instrumental`() {
        val lrc = listOf(
            "[00:00.00] 作词 : 某某",
            "[00:01.00] 作曲 : 某某",
            "[00:10.00]第一句歌词",
            "[00:15.00]第二句歌词",
        ).joinToString("\n")

        assertFalse(PureMusicDetector.isPureMusicLyricText(lrc))
    }

    @Test
    fun `a lyric that merely mentions the phrase is not instrumental`() {
        assertFalse(PureMusicDetector.isPureMusicLyricText("[00:10.00]我只听纯音乐，请欣赏这首歌"))
        assertFalse(PureMusicDetector.isPureMusicLyricText("[00:10.00]他喜欢纯音乐"))
    }

    @Test
    fun `colon shaped prose next to a notice is tolerated like the desktop`() {
        // Verified against the desktop implementation: `isColonShaped` is intentionally loose
        // (any short line with a colon and no sentence punctuation), so 「鼓起勇气：向前走」 passes as
        // a credit line. Tightening it here would diverge from the app both clients must agree with.
        assertTrue(PureMusicDetector.isPureMusicLyricText("[00:00.00]鼓起勇气：向前走\n[00:03.00]纯音乐，请欣赏"))
        assertTrue(PureMusicDetector.isPureMusicLyricText("[00:00.00]他说：我爱你\n[00:03.00]纯音乐，请欣赏"))
        // Without the colon there is no such tolerance.
        assertFalse(PureMusicDetector.isPureMusicLyricText("[00:00.00]鼓起勇气向前走\n[00:03.00]纯音乐，请欣赏"))
    }

    @Test
    fun `parsed lines use the same rule`() {
        val notice = Line(
            words = emptyList(),
            startTime = 0.0,
            endTime = 1.0,
            fullText = "纯音乐，请欣赏",
        )
        val lyric = Line(
            words = emptyList(),
            startTime = 1.0,
            endTime = 2.0,
            fullText = "第一句歌词",
        )

        assertTrue(PureMusicDetector.isPureMusicLyricLines(listOf(notice)))
        assertFalse(PureMusicDetector.isPureMusicLyricLines(listOf(notice, lyric)))
        assertFalse(PureMusicDetector.isPureMusicLyricLines(emptyList()))
        assertFalse(PureMusicDetector.isPureMusicLyricLines(null))
    }

    @Test
    fun `clean lines drop timing tags and json`() {
        val lines = PureMusicDetector.splitCleanLyricLines(
            """
            [00:00.00]第一句
            {"t":0,"c":[]}
            [00:01.00]<00:01.00>第<00:01.50>二句
            """.trimIndent()
        )

        assertEquals(listOf("第一句", "第二句"), lines)
    }
}
