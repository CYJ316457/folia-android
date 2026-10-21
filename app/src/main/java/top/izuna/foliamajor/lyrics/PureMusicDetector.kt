package top.izuna.foliamajor.lyrics

/**
 * Detects lyrics that are really a "this track is instrumental" notice.
 *
 * Ported from `src/utils/lyrics/pureMusic.ts` and the parts of `staffCredits.ts` it depends on.
 *
 * The rule is deliberately conservative: at least one line must be *entirely* that notice, and every
 * other line must look like a credit, a `field: value` pair, a title line or a filler separator. A
 * song whose chorus happens to contain 「纯音乐」 must not be classified as instrumental, and neither
 * must a track with no lyrics at all — the distinction matters because it decides whether the theme
 * generator is sent the title instead of an empty string.
 */
object PureMusicDetector {

    private val LRC_TAG_PATTERN = Regex("\\[[^\\]]*]")

    /**
     * Per-character timing tags.
     *
     * Stripped before matching because KuGou splits 「纯音乐」 into 「纯 音 乐」 with a tag between
     * each character; without this the notice line does not join back into one phrase.
     */
    private val WORD_TIMING_TAG_PATTERN = Regex("<[\\d:.,\\s]*>|\\(\\d+,\\d+,\\d+\\)")

    /**
     * A whole line that is JSON metadata.
     *
     * The closing brace must be escaped: Android's ICU regex engine rejects a bare `}` (the JVM's
     * engine tolerates it), so the desktop pattern `^\s*\{.*}\s*$` only compiles on the device after
     * this change. It fails at class-initialisation time, which means an unescaped brace here takes
     * the whole app down on the first theme prompt.
     */
    private val JSON_LINE_PATTERN = Regex("^\\s*\\{.*\\}\\s*$")

    private val NOTICE_IGNORED_CHARS_PATTERN = Regex("[\\s\\p{P}\\p{S}]+")

    /**
     * The notice line, with every common prefix: 「纯音乐，请欣赏」「此歌曲为没有填词的纯音乐，请您欣赏」.
     *
     * Matched against a line with whitespace and punctuation removed and anchored at both ends, so a
     * lyric that merely mentions the phrase cannot match.
     */
    private val PURE_MUSIC_NOTICE_LINE = Regex(
        "^(?:[此本](?:首)?(?:歌曲|曲目|作品|乐曲|曲)?[为是]?)?(?:(?:没有|无)(?:填词|歌词)的)?[纯純]音[乐樂]敬?[请請][您你]?欣[赏賞]$"
    )

    /** 「歌名 - 歌手」 title lines, which commonly sit above the notice. */
    private val HEADER_LINE_PATTERN = Regex("\\s[-–—]\\s")

    /**
     * Staff role keywords, copied from the desktop list.
     *
     * Only the entries that matter for the companion-line test are kept: this is not the full
     * staff-credit matcher (that belongs to lyric filtering), just enough to recognise the credits
     * block that ships with an instrumental's notice.
     */
    private val STAFF_ROLE_KEYWORDS = listOf(
        "作词", "作曲", "词", "曲", "编曲", "制作人", "演唱", "吉他", "贝斯", "混音", "母带", "录音",
        "监制", "出品", "策划", "统筹", "设计", "宣传", "运营", "总监", "鸣谢", "工作室", "版权", "发行",
        "艺人统筹", "制作总监", "总顾问", "出品人", "总监制", "战略", "视觉", "资产管理", "总策划",
        "音乐总监", "制作统筹", "音乐版权", "创意顾问", "平面设计", "宣传物料", "黑胶设计", "项目总统筹",
        "艺人协力", "项目协力", "策略行销", "视觉统筹", "媒介", "宣传营销", "联合出品", "协办单位",
        "宣发合作伙伴", "独家短视频平台", "主题歌音乐专辑工作团队", "工作室负责人", "协作", "平面摄影师",
        "海报设计团队", "企划", "和声编写", "画师", "场景画师", "PV底图", "美工", "题字", "视频",
        "人声后期", "PV", "封面", "单曲封面", "单曲封面及标准字设计", "单曲封面摄影", "歌词字幕", "歌手", "原唱",
        "和声", "弦乐", "键盘", "鼓", "制作",
        "Producer", "Composer", "Lyricist", "Arranger", "Vocal", "Guitar", "Bass",
        "Mixing", "Mastering", "Recording", "Arrangement", "Lyrics", "Music",
    )

    private fun escape(value: String): String {
        val special = ".*+?^\$()|[]{}\\"
        return value.map { char -> if (char in special) "\\$char" else "$char" }.joinToString("")
    }

    private val KEYWORD_GROUP = "(?:" + STAFF_ROLE_KEYWORDS.joinToString("|") { escape(it) } + ")"

    /** An optional latin alias after a Chinese role name ("歌词字幕Subtitles"). */
    private const val LATIN_ALIAS = "(?:\\s*[A-Za-z][A-Za-z0-9 .&'/-]*)?"

    /**
     * A credit line: a role keyword anchored at the start, optional further keywords, then a colon.
     *
     * Anchoring matters: 「鼓起勇气：向前走」 contains 「鼓」 but continues with prose, so it fails the
     * keyword-at-start requirement.
     */
    private val BUILT_IN_STAFF_PATTERN = Regex(
        "^\\s*[\\[（(【]?\\s*$KEYWORD_GROUP(?:\\s*[/、&+,，·・和]?\\s*$KEYWORD_GROUP)*" +
            "$LATIN_ALIAS\\s*[\\]）)】]?\\s*[:：]\\s*\\S",
        RegexOption.IGNORE_CASE,
    )

    /** Credit lines without a colon: 「编曲 某某」, 「Music by Foo」. */
    private val SPACED_STAFF_PATTERN = Regex(
        "^\\s*$KEYWORD_GROUP(?:\\s*[/、&+,，·・和]\\s*$KEYWORD_GROUP)*\\s+\\S",
        RegexOption.IGNORE_CASE,
    )

    private val STAFF_BY_PREFIX =
        Regex("^\\s*(?:produced|written|composed|arranged|mixed|mastered|recorded|performed)\\s+by\\s+\\S", RegexOption.IGNORE_CASE)

    private val COLON_SHAPED = Regex("^[^:：，。！？；…!?]{1,60}[:：]\\s*\\S")

    private val FILLER = Regex("^[^\\p{L}\\p{N}]+$")

    private fun isFiller(line: String): Boolean {
        val text = line.trim()
        return text.isEmpty() || (text.length <= 8 && FILLER.matches(text))
    }

    private fun isStaffCredit(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        return BUILT_IN_STAFF_PATTERN.containsMatchIn(text) || STAFF_BY_PREFIX.containsMatchIn(text)
    }

    private fun isNoticeCompanion(line: String): Boolean =
        isStaffCredit(line) ||
            COLON_SHAPED.containsMatchIn(line.trim()) ||
            SPACED_STAFF_PATTERN.containsMatchIn(line.trim()) ||
            isFiller(line) ||
            HEADER_LINE_PATTERN.containsMatchIn(line)

    /**
     * Splits into non-empty lines with all timing tags removed, dropping JSON metadata lines.
     */
    internal fun splitCleanLyricLines(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return text
            .replace("\uFEFF", "")
            .split(Regex("\\r?\\n"))
            .map { line ->
                line.replace(LRC_TAG_PATTERN, "")
                    .replace(WORD_TIMING_TAG_PATTERN, "")
                    .trim()
            }
            .filter { it.isNotEmpty() && !JSON_LINE_PATTERN.matches(it) }
    }

    internal fun isPureMusicNoticeLine(line: String): Boolean =
        PURE_MUSIC_NOTICE_LINE.matches(line.replace(NOTICE_IGNORED_CHARS_PATTERN, ""))

    /**
     * True when [text] says the track is instrumental.
     *
     * Empty text is **not** instrumental: a song with no lyric file still has a title worth reading,
     * but calling it "pure music" would send the wrong prompt mode.
     */
    fun isPureMusicLyricText(text: String?): Boolean {
        var hasNotice = false
        for (line in splitCleanLyricLines(text)) {
            if (isPureMusicNoticeLine(line)) {
                hasNotice = true
                continue
            }
            if (!isNoticeCompanion(line)) return false
        }
        return hasNotice
    }

    /** The same rule applied to already-parsed lines, for sources without raw lyric text. */
    fun isPureMusicLyricLines(lines: List<Line>?): Boolean =
        !lines.isNullOrEmpty() && isPureMusicLyricText(lines.joinToString("\n") { it.fullText })
}
