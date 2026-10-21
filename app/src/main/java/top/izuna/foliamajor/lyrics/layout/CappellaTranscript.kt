package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import kotlin.math.floor

/**
 * The `cappella` chat transcript: lyrics staged as a conversation.
 *
 * Ported from `src/components/visualizer/cappella/cappellaMessages.ts` and
 * `cappellaIntensity.ts`. Each lyric line becomes a chat bubble from a sender on the left or the
 * right, with a stable avatar; TTML duets map their agents to fixed senders so the same voice always
 * appears on the same side.
 *
 * Everything is seeded deterministically from the line's timestamps and text, so a song produces the
 * same conversation every time it is played. That matters more than it sounds: a chat transcript that
 * reshuffles on every replay reads as broken rather than as decoration.
 */
enum class ChatSide { LEFT, RIGHT }

/** Which sender a message comes from: one of nine avatar cells plus a side. */
data class ChatSender(val side: ChatSide, val avatarIndex: Int)

sealed class ChatMessage {
    abstract val id: String
    abstract val sender: ChatSender

    /** The song title, pinned as the first message from the right. */
    data class Title(
        override val id: String,
        override val sender: ChatSender,
        val text: String,
    ) : ChatMessage()

    /** One lyric line as a bubble. */
    data class Lyric(
        override val id: String,
        override val sender: ChatSender,
        val line: Line,
        val lineIndex: Int,
    ) : ChatMessage()
}

/** Tuning that varies the pacing of the conversation by theme intensity. */
data class CappellaSequencing(
    val forceRightEveryLines: Int,
    val shortLineCarryChance: Float,
    val sideSequence: List<ChatSide>,
    val sideFlipChance: Float,
)

/** The motion parameters for one intensity level. */
data class CappellaMotion(
    val rowEnterY: Float,
    val rowEnterScale: Float,
    val rowEnterDuration: Float,
    val rowExitY: Float,
    val rowExitScale: Float,
    val rowExitDuration: Float,
    val activeScale: Float,
    val passedScale: Float,
    val passedOpacity: Float,
    val activeFontMultiplier: Float,
    val inactiveFontMultiplier: Float,
    val activePaddingX: Float,
    val activePaddingY: Float,
    val inactivePaddingX: Float,
    val inactivePaddingY: Float,
    val activeMinHeight: Float,
    val inactiveMinHeight: Float,
    val glowOpacity: Float,
    val glowDuration: Float,
    val activeShadowAlpha: Float,
)

data class CappellaIntensity(
    val sequencing: CappellaSequencing,
    val motion: CappellaMotion,
) {
    companion object {
        /**
         * The three presets, copied from the desktop implementation.
         *
         * Kept as whole presets rather than interpolated parameters: the desktop app's three levels
         * are hand-tuned to read as three different moods, and blending between them produces a
         * fourth that neither client was designed around.
         */
        fun forIntensity(intensity: String): CappellaIntensity = when (intensity.lowercase()) {
            "calm" -> calm()
            "chaotic" -> chaotic()
            else -> normal()
        }

        fun calm() = CappellaIntensity(
            sequencing = CappellaSequencing(
                forceRightEveryLines = 7,
                shortLineCarryChance = 0.92f,
                sideSequence = listOf(ChatSide.LEFT, ChatSide.LEFT, ChatSide.RIGHT, ChatSide.LEFT, ChatSide.RIGHT),
                sideFlipChance = 0.08f,
            ),
            motion = CappellaMotion(
                rowEnterY = 14f, rowEnterScale = 0.992f, rowEnterDuration = 0.28f,
                rowExitY = -10f, rowExitScale = 0.985f, rowExitDuration = 0.22f,
                activeScale = 1.07f, passedScale = 0.96f, passedOpacity = 0.88f,
                activeFontMultiplier = 1.22f, inactiveFontMultiplier = 0.96f,
                activePaddingX = 18f, activePaddingY = 14f,
                inactivePaddingX = 16f, inactivePaddingY = 12f,
                activeMinHeight = 58f, inactiveMinHeight = 44f,
                glowOpacity = 0.26f, glowDuration = 2.2f, activeShadowAlpha = 0.24f,
            ),
        )

        fun normal() = CappellaIntensity(
            sequencing = CappellaSequencing(
                forceRightEveryLines = 5,
                shortLineCarryChance = 0.68f,
                sideSequence = listOf(ChatSide.LEFT, ChatSide.RIGHT, ChatSide.LEFT, ChatSide.RIGHT, ChatSide.RIGHT),
                sideFlipChance = 0.18f,
            ),
            motion = CappellaMotion(
                rowEnterY = 22f, rowEnterScale = 0.98f, rowEnterDuration = 0.32f,
                rowExitY = -18f, rowExitScale = 0.965f, rowExitDuration = 0.24f,
                activeScale = 1.12f, passedScale = 0.92f, passedOpacity = 0.82f,
                activeFontMultiplier = 1.34f, inactiveFontMultiplier = 0.94f,
                activePaddingX = 20f, activePaddingY = 16f,
                inactivePaddingX = 16f, inactivePaddingY = 12f,
                activeMinHeight = 64f, inactiveMinHeight = 44f,
                glowOpacity = 0.4f, glowDuration = 1.8f, activeShadowAlpha = 0.34f,
            ),
        )

        fun chaotic() = CappellaIntensity(
            sequencing = CappellaSequencing(
                forceRightEveryLines = 3,
                shortLineCarryChance = 0.36f,
                sideSequence = listOf(
                    ChatSide.LEFT, ChatSide.RIGHT, ChatSide.RIGHT,
                    ChatSide.LEFT, ChatSide.RIGHT, ChatSide.LEFT,
                ),
                sideFlipChance = 0.42f,
            ),
            motion = CappellaMotion(
                rowEnterY = 30f, rowEnterScale = 0.968f, rowEnterDuration = 0.38f,
                rowExitY = -26f, rowExitScale = 0.94f, rowExitDuration = 0.28f,
                activeScale = 1.18f, passedScale = 0.88f, passedOpacity = 0.76f,
                activeFontMultiplier = 1.4f, inactiveFontMultiplier = 0.92f,
                activePaddingX = 22f, activePaddingY = 17f,
                inactivePaddingX = 15f, inactivePaddingY = 11f,
                activeMinHeight = 68f, inactiveMinHeight = 42f,
                glowOpacity = 0.52f, glowDuration = 1.35f, activeShadowAlpha = 0.42f,
            ),
        )
    }
}

object CappellaTranscript {

    /** A line this short may reuse the previous sender, which makes the chat read as a reply. */
    const val SHORT_LINE_CHAR_LIMIT = 12

    const val AVATAR_GRID_SIZE = 3

    /** The avatar cell the right-hand speaker always uses (bottom-right of the grid). */
    const val RIGHT_AVATAR_INDEX = AVATAR_GRID_SIZE * AVATAR_GRID_SIZE - 1

    /** Left-hand avatars in the order they are handed out. */
    val LEFT_AVATAR_INDICES = listOf(0, 3, 6, 1, 4)

    /**
     * Builds the transcript.
     *
     * Side and avatar assignment happens here rather than in the renderer so it is stable: the
     * renderer only decides what a side looks like, never which side a line is on.
     */
    fun build(
        lines: List<Line>,
        titleText: String,
        intensity: CappellaIntensity,
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages.add(
            ChatMessage.Title(
                id = "title",
                sender = ChatSender(ChatSide.RIGHT, RIGHT_AVATAR_INDEX),
                text = titleText,
            )
        )
        if (lines.isEmpty()) return messages

        val agentSenders = agentSenders(lines)

        var sideCursor = 0
        var nextLeftAvatar = 0
        var lastLyricSender: ChatSender? = null

        lines.forEachIndexed { lineIndex, line ->
            val sender = resolveSender(
                line = line,
                lineIndex = lineIndex,
                intensity = intensity,
                agentSenders = agentSenders,
                sideCursor = sideCursor,
                nextLeftAvatar = nextLeftAvatar,
                lastLyricSender = lastLyricSender,
            )

            messages.add(
                ChatMessage.Lyric(
                    id = "line-${line.startTime}-$lineIndex",
                    sender = sender.sender,
                    line = line,
                    lineIndex = lineIndex,
                )
            )

            when {
                // A duet's agent owns its side; the sequence cursor does not advance, so the
                // non-duet parts keep the rhythm they would have had.
                sender.fromAgent -> lastLyricSender = sender.sender
                sender.forcedRight -> {
                    sideCursor = 0
                    lastLyricSender = null
                }
                sender.carried -> lastLyricSender = sender.sender
                else -> {
                    if (sender.sender.side == ChatSide.LEFT) nextLeftAvatar += 1
                    sideCursor += 1
                    lastLyricSender = sender.sender
                }
            }
        }

        return messages
    }

    private data class SenderResolution(
        val sender: ChatSender,
        val fromAgent: Boolean,
        val forcedRight: Boolean,
        val carried: Boolean,
    )

    private fun resolveSender(
        line: Line,
        lineIndex: Int,
        intensity: CappellaIntensity,
        agentSenders: Map<String, ChatSender>?,
        sideCursor: Int,
        nextLeftAvatar: Int,
        lastLyricSender: ChatSender?,
    ): SenderResolution {
        val agentSender = line.agentId?.trim()?.takeIf { it.isNotEmpty() }?.let { agentSenders?.get(it) }

        val sequencing = intensity.sequencing
        // Every Nth line is forced to the right without advancing the cursor: that is what keeps a
        // long left-heavy stretch from drifting entirely to one side.
        val forcedRight = agentSender == null &&
            sequencing.forceRightEveryLines > 0 &&
            (lineIndex + 1) % sequencing.forceRightEveryLines == 0

        val compactChars = line.fullText.filterNot { it.isWhitespace() }.length
        val shortLine = compactChars <= SHORT_LINE_CHAR_LIMIT
        val carried = agentSender == null &&
            shortLine &&
            lastLyricSender != null &&
            seededUnit("carry", line.startTime, lineIndex.toDouble()) <= sequencing.shortLineCarryChance

        val baseSide = sequencing.sideSequence[sideCursor % sequencing.sideSequence.size]
        val flip = !forcedRight &&
            seededUnit("flip", line.startTime, lineIndex.toDouble()) < sequencing.sideFlipChance
        val resolvedSide = if (flip) {
            if (baseSide == ChatSide.LEFT) ChatSide.RIGHT else ChatSide.LEFT
        } else {
            baseSide
        }

        val sender = when {
            agentSender != null -> agentSender
            forcedRight -> ChatSender(ChatSide.RIGHT, RIGHT_AVATAR_INDEX)
            carried && lastLyricSender != null -> lastLyricSender
            resolvedSide == ChatSide.LEFT -> ChatSender(
                ChatSide.LEFT,
                LEFT_AVATAR_INDICES[nextLeftAvatar % LEFT_AVATAR_INDICES.size],
            )
            else -> ChatSender(ChatSide.RIGHT, RIGHT_AVATAR_INDEX)
        }

        return SenderResolution(
            sender = sender,
            fromAgent = agentSender != null,
            forcedRight = forcedRight,
            carried = carried,
        )
    }

    /**
     * Maps TTML agents to senders, or null when there is no duet.
     *
     * The first agent to appear takes the right-hand side (the "lead"), and the rest fill left
     * avatars in order. Fewer than two agents means the file is not a duet and the seeded sequence
     * should decide instead.
     */
    private fun agentSenders(lines: List<Line>): Map<String, ChatSender>? {
        val ids = lines.mapNotNull { it.agentId?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        if (ids.size < 2) return null

        val rightAgent = ids.first()
        return ids.mapIndexed { index, id ->
            id to if (id == rightAgent) {
                ChatSender(ChatSide.RIGHT, RIGHT_AVATAR_INDEX)
            } else {
                ChatSender(ChatSide.LEFT, LEFT_AVATAR_INDICES[(index - 1) % LEFT_AVATAR_INDICES.size])
            }
        }.toMap()
    }

    /** Two decimals of the line's identity as a 0..1 value, stable across runs. */
    private fun seededUnit(tag: String, vararg values: Double): Float {
        val key = (listOf(tag) + values.map { it.toString() }).joinToString("|")
        val hash = FumeMath.hashString(key).toLong() and 0xFFFFFFFFL
        return hash.toFloat() / 4294967295f
    }

    /**
     * The visible transcript window.
     *
     * [heightBudget] is the room the chat has; messages are added from the newest backwards so the
     * bottom of the conversation is always complete and older context falls off the top. Estimating
     * heights is the caller's job (it needs a text measurer), so this takes the estimates.
     *
     * At least two messages are kept regardless of the budget: a chat with one bubble reads as an
     * error, not as a conversation with no history.
     */
    fun visibleMessages(
        messages: List<ChatMessage>,
        currentLineIndex: Int,
        heightBudget: Float,
        estimatedHeight: (ChatMessage) -> Float,
        maxVisible: Int = 20,
    ): List<ChatMessage> {
        val visible = messages.filter { message ->
            when (message) {
                is ChatMessage.Title -> true
                is ChatMessage.Lyric -> message.lineIndex <= currentLineIndex
            }
        }

        val result = ArrayDeque<ChatMessage>()
        var accumulated = 0f

        for (index in visible.indices.reversed()) {
            val message = visible[index]
            val height = estimatedHeight(message)

            if (accumulated + height > heightBudget && result.size >= 2) break

            accumulated += height
            result.addFirst(message)

            if (result.size >= maxVisible) break
        }

        return result.toList()
    }

    /** The index of the line being sung, or -1 before the first one. */
    fun visibleLineIndexAt(lines: List<Line>, currentTime: Double): Int {
        for (index in lines.indices.reversed()) {
            if (currentTime >= lines[index].startTime) return index
        }
        return -1
    }

    /** Chat-style timestamp: whole minutes plus zero-padded seconds. */
    fun formatTimestamp(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0:00"
        val total = floor(seconds).toInt().coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /**
     * How long a line stays on screen.
     *
     * Capped by the next line's start: two active bubbles at once would both claim to be current.
     */
    fun effectiveRenderEnd(line: Line, nextLine: Line?): Double =
        minOf(getLineRenderEndTime(line), nextLine?.startTime ?: Double.POSITIVE_INFINITY)
}
