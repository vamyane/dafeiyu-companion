package com.dafeiyu.companion.room

/**
 * 群聊里一个可发言的 AI 成员。
 *
 * talkativeness 对应角色卡的说话密度；keywords 用来估算它和“上一条消息”的相关度，
 * 没有关键词时退化为人名匹配与固定基线，保证任何角色卡都能参与调度。
 */
data class RoomSpeaker(
    val id: String,
    val name: String,
    val talkativeness: Double = 0.5,
    val keywords: Set<String> = emptySet()
)

/** 房间时间线上的一条消息。role 只有 user / assistant。 */
data class RoomTurn(
    val role: String,
    val speakerId: String?,
    val speakerName: String?,
    val text: String
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * 发言调度：只负责“下一句该谁说”，不负责生成内容，因此可以在 JVM 上确定性测试。
 *
 * 规则来自 SPEC 第 10 节：
 * 1. 被 @ 或回复某角色 -> 该角色发言；
 * 2. 否则候选 = 冷却已过的成员；
 * 3. 权重 = talkativeness * 与上一条消息的相关度；
 * 4. 排除连续发言已达 2 次的角色；
 * 5. 加权随机；无候选则沉默；
 * 6. 真人消息默认至少触发一次回应（由调用方用 [ensureHumanGetsReply] 保证）。
 */
class RoomScheduler(
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    private val maxConsecutiveTurns: Int = 2,
    private val cooldownTurns: Int = 2
) {

    fun pickNext(
        members: List<RoomSpeaker>,
        turns: List<RoomTurn>,
        mentions: List<String> = emptyList(),
        replyTo: String? = null
    ): RoomSpeaker? {
        if (members.isEmpty()) return null

        val eligible = members.filter { consecutiveCount(turns, it.id) < maxConsecutiveTurns }
        if (eligible.isEmpty()) return null

        // 1. 明确被回复 / 被 @ 的角色优先；同一优先级内仍按权重随机，避免永远只轮到第一个。
        val targeted = eligible.filter { member ->
            member.id == replyTo || member.name == replyTo ||
                mentions.any { it == member.id || it == member.name }
        }
        if (targeted.isNotEmpty()) {
            return weightedPick(targeted, turns)
        }

        // 2. 冷却已过的成员；若所有人都在冷却，放宽冷却而不是让群突然死掉。
        val cooled = eligible.filter { turns.size - lastSpokeIndex(turns, it.id) > cooldownTurns }
        val candidates = cooled.ifEmpty { eligible }
        return weightedPick(candidates, turns)
    }

    /**
     * 真人消息默认至少触发一次回应：第一句允许忽略冷却，但仍不允许同一角色连续第 3 条。
     */
    fun ensureHumanGetsReply(
        members: List<RoomSpeaker>,
        turns: List<RoomTurn>,
        mentions: List<String> = emptyList(),
        replyTo: String? = null
    ): RoomSpeaker? {
        val normal = pickNext(members, turns, mentions, replyTo)
        if (normal != null) return normal
        val eligible = members.filter { consecutiveCount(turns, it.id) < maxConsecutiveTurns }
        return weightedPick(eligible, turns)
    }

    fun consecutiveCount(turns: List<RoomTurn>, speakerId: String): Int {
        var count = 0
        for (turn in turns.asReversed()) {
            if (turn.isUser) break
            if (turn.speakerId == speakerId) count++ else break
        }
        return count
    }

    private fun lastSpokeIndex(turns: List<RoomTurn>, speakerId: String): Int {
        for (index in turns.indices.reversed()) {
            val turn = turns[index]
            if (!turn.isUser && turn.speakerId == speakerId) return index
        }
        return -1
    }

    private fun weightedPick(candidates: List<RoomSpeaker>, turns: List<RoomTurn>): RoomSpeaker? {
        if (candidates.isEmpty()) return null
        val lastText = turns.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
        val weights = candidates.map { member ->
            val talk = member.talkativeness.coerceIn(0.05, 1.0)
            val related = relatedness(lastText, member)
            val weight = talk * (0.35 + 0.65 * related)
            weight.coerceAtLeast(0.01)
        }
        val total = weights.sum()
        var needle = random.nextDouble() * total
        candidates.forEachIndexed { index, member ->
            needle -= weights[index]
            if (needle <= 0.0) return member
        }
        return candidates.last()
    }

    /**
     * 相关度：比较中文二元组与英文词的重合比例；角色没有关键词时给固定基线 0.35。
     */
    fun relatedness(text: String, speaker: RoomSpeaker): Double {
        if (text.isBlank()) return 0.35
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return 0.35
        val haystack = speaker.keywords.ifEmpty {
            tokenize(speaker.name)
        }
        if (haystack.isEmpty()) return 0.35
        val hit = tokens.count { it in haystack }
        return (hit.toDouble() / tokens.size).coerceIn(0.0, 1.0)
    }

    companion object {
        private val CJK = Regex("[\\u4e00-\\u9fff]")
        private val WORD = Regex("[A-Za-z0-9_]{2,}")

        fun tokenize(text: String): Set<String> {
            val out = HashSet<String>()
            val cjk = StringBuilder()
            text.forEach { ch ->
                if (CJK.matches(ch.toString())) {
                    cjk.append(ch)
                } else {
                    if (cjk.length >= 2) addBigrams(cjk.toString(), out)
                    cjk.setLength(0)
                }
            }
            if (cjk.length >= 2) addBigrams(cjk.toString(), out)
            WORD.findAll(text.lowercase()).forEach { out.add(it.value) }
            return out
        }

        private fun addBigrams(text: String, out: MutableSet<String>) {
            if (text.length <= 1) return
            for (index in 0 until text.length - 1) {
                out.add(text.substring(index, index + 2))
            }
        }

        /** 从角色设定里抽取关键词，供相关度估算使用。 */
        fun keywordsOf(vararg texts: String): Set<String> {
            val out = HashSet<String>()
            texts.filter { it.isNotBlank() }.forEach { out.addAll(tokenize(it)) }
            return out
        }
    }
}
