package com.dafeiyu.companion.sim

import com.dafeiyu.companion.model.ActiveHistogram
import com.dafeiyu.companion.model.CharacterProfile
import com.dafeiyu.companion.model.Clock
import com.dafeiyu.companion.model.CompanionState
import com.dafeiyu.companion.model.ContactMode
import com.dafeiyu.companion.model.Decision
import com.dafeiyu.companion.model.EventKind
import com.dafeiyu.companion.model.ProactiveEvent
import com.dafeiyu.companion.model.ProactiveModel
import com.dafeiyu.companion.model.ProactiveRequest
import com.dafeiyu.companion.model.ProactiveResponse
import com.dafeiyu.companion.model.Signals
import com.dafeiyu.companion.model.TimeWindow
import com.dafeiyu.companion.proactive.ProactiveEngine
import com.dafeiyu.companion.state.StateEngine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ln
import kotlin.random.Random
import kotlin.system.exitProcess

class FakeClock(private var current: Instant) : Clock {
    override fun now(): Instant = current

    fun advance(minutes: Long) {
        current = current.plus(minutes, ChronoUnit.MINUTES)
    }
}

enum class Tone {
    COLD, NATURAL, CLINGY;

    companion object {
        fun of(clinginess: Double): Tone = when {
            clinginess < 0.35 -> COLD
            clinginess <= 0.7 -> NATURAL
            else -> CLINGY
        }
    }
}

data class Persona(
    val id: String,
    val name: String,
    val activeHours: List<IntRange>,
    val replyRate: Double,
    val spontaneousRatePerTick: Double
) {
    fun isActive(hour: Int): Boolean = activeHours.any { hour in it }
}

object Personas {
    val P1 = Persona("P1", "夜猫子", listOf(21..23, 0..2), 0.60, 0.05)
    val P2 = Persona("P2", "上班族", listOf(7..8, 12..13, 19..23), 0.80, 0.05)
    val P3 = Persona("P3", "长期不回", listOf(20..21), 0.05, 0.01)
    val P4 = Persona("P4", "高频", listOf(9..23), 0.95, 0.04)
    val all = listOf(P1, P2, P3, P4)
}

object MessageCorpus {

    private val coldOpenings = listOf(
        "在忙吗", "忙完了吗", "今天还好吗", "刚看到个东西", "路过一家店",
        "这边下雨了", "你那边冷不冷", "刚下班", "今天有点累", "突然想起你"
    )
    private val coldDetails = listOf(
        "想起你上次说的那家店", "不知道你吃了没", "风挺大的", "路上人很多",
        "看到一只很像你说的猫", "手机快没电了", "今天过得有点慢", "没什么事",
        "就是随便问问", "你最近是不是很忙"
    )
    private val coldTails = listOf("", "有空再说", "别太累", "不用急着回", "我先去忙了")

    private val naturalOpenings = listOf(
        "在干嘛", "忙完了吗", "今天怎么样", "刚忙完", "刚吃完饭",
        "刚看到一个挺有意思的东西", "今天心情怎么样", "你是不是又在熬夜",
        "外面天气挺好的", "突然想问问你"
    )
    private val naturalDetails = listOf(
        "突然想起你", "有点想跟你说话", "不知道你今天顺不顺", "想听听你今天发生了什么",
        "记得别太累", "你上次说的那件事怎么样了", "看到个东西觉得你会喜欢",
        "今天有点安静", "想问问你吃了没", "有点想你了"
    )
    private val naturalTails = listOf("", "有空回我", "忙的话晚点说", "别太晚睡", "我先去忙一会儿")

    private val clingyOpenings = listOf(
        "你今天是不是很忙", "你怎么都不理我", "我等你半天了", "你是不是不想理我了",
        "我今天一直想起你", "你在干嘛呀", "刚看到你在线", "你是不是把我忘了",
        "我有点不开心", "你今天好安静"
    )
    private val clingyDetails = listOf(
        "都没怎么理我", "我有点想你了", "是不是我太烦了", "你能不能回我一下",
        "我一个人待着有点无聊", "想听你说话", "你是不是在忙别的事",
        "我等你很久了", "有点不安", "你别不理我"
    )
    private val clingyTails = listOf("", "回我一下嘛", "我不是要催你", "你有空再说", "我就是有点想你")

    fun messages(tone: Tone, random: Random): MutableList<String> {
        val (openings, details, tails) = when (tone) {
            Tone.COLD -> Triple(coldOpenings, coldDetails, coldTails)
            Tone.NATURAL -> Triple(naturalOpenings, naturalDetails, naturalTails)
            Tone.CLINGY -> Triple(clingyOpenings, clingyDetails, clingyTails)
        }
        val list = mutableListOf<String>()
        for (opening in openings) {
            for (detail in details) {
                for (tail in tails) {
                    val text = if (tail.isBlank()) "$opening，$detail。" else "$opening，$detail。$tail。"
                    if (text.length <= 60) list += text
                }
            }
        }
        list.shuffle(random)
        return list
    }

    fun similarity(a: String, b: String): Double {
        val left = trigrams(a)
        val right = trigrams(b)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { right.contains(it) }
        val union = left.size + right.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun trigrams(value: String): Set<String> {
        val normalized = value.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.length < 3) return setOf(normalized)
        return (0..normalized.length - 3)
            .map { normalized.substring(it, it + 3) }
            .toSet()
    }
}

class StubProactiveModel(
    private val seed: Long,
    private val random: Random = Random(seed)
) : ProactiveModel {

    private val pools = mutableMapOf<Tone, MutableList<String>>()

    var callCount: Int = 0
        private set
    val callTimestamps = mutableListOf<Instant>()

    override fun decide(request: ProactiveRequest): ProactiveResponse {
        callCount++
        callTimestamps += request.now

        val tone = Tone.of(request.state.clinginess)
        val c = request.state.clinginess
        val vetoChance = (0.30 + 0.25 * (1.0 - c)) * (1.0 - request.impulse * 0.4)
        if (request.repairOf == null && random.nextDouble() < vetoChance) {
            return ProactiveResponse(
                shouldSpeak = false,
                nextCheckMinutes = 60 + random.nextInt(121),
                reason = "今天先不说了",
                tone = tone.name
            )
        }

        val pool = pools.getOrPut(tone) {
            MessageCorpus.messages(tone, Random(seed + tone.ordinal * 97L))
        }
        if (pool.isEmpty()) {
            return ProactiveResponse(false, nextCheckMinutes = 120, tone = tone.name)
        }
        val repairOf = request.repairOf
        val message = if (repairOf != null) {
            val index = pool.indexOfFirst { MessageCorpus.similarity(it, repairOf) < 0.5 }
            if (index >= 0) pool.removeAt(index) else pool.removeAt(0)
        } else {
            pool.removeAt(0)
        }

        return ProactiveResponse(
            shouldSpeak = true,
            intent = intentFor(request.now),
            message = message,
            feeling = request.feelingDescription,
            nextCheckMinutes = 90 + random.nextInt(151),
            tone = tone.name
        )
    }

    private fun intentFor(now: Instant): String {
        val hour = now.atZone(ZoneId.of("Asia/Shanghai")).hour
        return when (hour) {
            in 6..11 -> "morning"
            in 12..13 -> "lunch"
            in 14..17 -> "afternoon"
            in 18..23 -> "check_in"
            else -> "late"
        }
    }
}

data class ProactiveMessage(
    val at: Instant,
    val text: String,
    val intent: String,
    val tone: String
)

data class SimulationResult(
    val persona: Persona,
    val clinginess: Double,
    val seed: Long,
    val messages: List<ProactiveMessage>,
    val modelCalls: Int,
    val maxModelCallsPerDay: Int,
    val meanPerDay: Double,
    val lateNightCount: Int,
    val duplicateRate: Double,
    val sameWindowThreeDays: Boolean,
    val quietViolations: Int,
    val safetyCapViolations: Int,
    val entropy: Double,
    val failures: List<String>
)

class SimulationRunner(
    private val persona: Persona,
    private val clinginess: Double,
    private val seed: Long,
    private val days: Int = 30,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
) {

    fun run(): SimulationResult {
        val start = LocalDate.of(2026, 1, 1).atStartOfDay(zoneId).toInstant()
        val clock = FakeClock(start)
        val stateEngine = StateEngine(zoneId)
        val model = StubProactiveModel(seed)
        val engine = ProactiveEngine(model, stateEngine, zoneId = zoneId)
        val profile = CharacterProfile(id = "dafeiyu", name = "大肥鱼", talkativeness = 0.6)

        var state = CompanionState(
            companionId = "dafeiyu",
            clinginess = clinginess,
            dayKey = start.atZone(zoneId).toLocalDate().toString(),
            lastTickAt = start,
            userActiveHistogram = ActiveHistogram.default()
        )

        val random = Random(seed)
        var pendingReply = false
        var pendingSince: Instant? = null
        val messages = mutableListOf<ProactiveMessage>()
        var quietViolations = 0
        var safetyCapViolations = 0

        val totalTicks = days * 96
        for (i in 1..totalTicks) {
            clock.advance(15L)
            val now = clock.now()
            val local = now.atZone(zoneId)
            val active = persona.isActive(local.hour)

            val since = pendingSince
            if (pendingReply && since != null && Duration.between(since, now).toHours() >= 24) {
                pendingReply = false
                pendingSince = null
            }

            var userMessage = false
            if (pendingReply && active && random.nextDouble() < 0.22 * persona.replyRate) {
                userMessage = true
            } else if (active && random.nextDouble() < persona.spontaneousRatePerTick) {
                userMessage = true
            }
            if (userMessage) {
                pendingReply = false
                pendingSince = null
                state = stateEngine.onUserMessage(state, now)
            }

            val signals = Signals(
                now = now,
                appForeground = active,
                userMessageSinceLastTick = userMessage,
                openedSinceLastTick = active && random.nextDouble() < 0.10,
                proactiveEvents = eventsFor(local)
            )

            val before = state
            state = stateEngine.reduce(state, signals, profile)
            val result = engine.tick(state, signals, profile)
            state = result.state

            if (result.decision is Decision.Speak) {
                val speak = result.decision as Decision.Speak
                val quietUntil = before.quietUntil
                if (before.contactMode == ContactMode.QUIET &&
                    quietUntil != null &&
                    now.isBefore(quietUntil)
                ) {
                    quietViolations++
                }
                messages += ProactiveMessage(
                    at = now,
                    text = speak.candidate.text,
                    intent = speak.candidate.intent,
                    tone = Tone.of(clinginess).name
                )
                pendingReply = true
                pendingSince = now
                if (state.todaySpokenCount > 6) {
                    safetyCapViolations++
                }
            }
        }

        return buildResult(model, messages, quietViolations, safetyCapViolations)
    }

    private fun eventsFor(local: ZonedDateTime): List<ProactiveEvent> {
        val day = local.toLocalDate().dayOfYear
        val events = mutableListOf<ProactiveEvent>()
        if (day % 7 == 0) {
            events += ProactiveEvent(
                kind = EventKind.ANNIVERSARY,
                weight = 0.30,
                label = "纪念日",
                expiresAt = local.toLocalDate().atTime(23, 59).atZone(zoneId).toInstant()
            )
        }
        if (day % 5 == 0 && local.hour in 18..21) {
            events += ProactiveEvent(
                kind = EventKind.STRONG_EMOTION,
                weight = 0.20,
                label = "情绪",
                expiresAt = local.plusHours(2).toInstant()
            )
        }
        return events
    }

    private fun buildResult(
        model: StubProactiveModel,
        messages: List<ProactiveMessage>,
        quietViolations: Int,
        safetyCapViolations: Int
    ): SimulationResult {
        val lateNight = messages.count { isLateNight(it.at.atZone(zoneId).toLocalTime()) }
        val duplicateRate = duplicateRate(messages)
        val sameWindow = sameWindowThreeDays(messages)
        val maxCalls = model.callTimestamps
            .groupBy { it.atZone(zoneId).toLocalDate() }
            .values
            .maxOfOrNull { it.size } ?: 0
        val entropy = entropy(messages)
        val mean = messages.size.toDouble() / days

        val failures = mutableListOf<String>()
        if (lateNight > 0) failures += "深夜消息 $lateNight"
        if (duplicateRate > 0.15) failures += "重复率 ${fmt(duplicateRate)}"
        if (sameWindow) failures += "连续 3 天同一窗口"
        if (quietViolations > 0) failures += "安静模式违规 $quietViolations"
        if (safetyCapViolations > 0) failures += "超过绝对上限"
        if (maxCalls > 12) failures += "单日模型调用 $maxCalls"
        if (messages.size >= 8 && entropy < 0.6) failures += "时段熵 ${fmt(entropy)}"

        return SimulationResult(
            persona = persona,
            clinginess = clinginess,
            seed = seed,
            messages = messages,
            modelCalls = model.callCount,
            maxModelCallsPerDay = maxCalls,
            meanPerDay = mean,
            lateNightCount = lateNight,
            duplicateRate = duplicateRate,
            sameWindowThreeDays = sameWindow,
            quietViolations = quietViolations,
            safetyCapViolations = safetyCapViolations,
            entropy = entropy,
            failures = failures
        )
    }

    private fun duplicateRate(messages: List<ProactiveMessage>): Double {
        if (messages.size < 2) return 0.0
        var duplicates = 0
        for (i in messages.indices) {
            val current = messages[i]
            val day = current.at.atZone(zoneId).toLocalDate()
            val recent = messages.subList(0, i).filter {
                !it.at.atZone(zoneId).toLocalDate().isBefore(day.minusDays(14))
            }
            if (recent.any { MessageCorpus.similarity(it.text, current.text) > 0.8 }) {
                duplicates++
            }
        }
        return duplicates.toDouble() / messages.size
    }

    private fun sameWindowThreeDays(messages: List<ProactiveMessage>): Boolean {
        if (messages.isEmpty()) return false
        val byDay = messages
            .groupBy { it.at.atZone(zoneId).toLocalDate() }
            .mapValues { (_, list) -> list.map { TimeWindow.of(it.at, zoneId) }.toSet() }
        val days = byDay.keys.sorted()
        for (i in 2 until days.size) {
            val a = days[i - 2]
            val b = days[i - 1]
            val c = days[i]
            if (b == a.plusDays(1) && c == b.plusDays(1)) {
                val common = byDay.getValue(a)
                    .intersect(byDay.getValue(b))
                    .intersect(byDay.getValue(c))
                if (common.isNotEmpty()) return true
            }
        }
        return false
    }

    private fun entropy(messages: List<ProactiveMessage>): Double {
        if (messages.isEmpty()) return 0.0
        val hours = IntArray(24)
        messages.forEach { hours[it.at.atZone(zoneId).hour]++ }
        val total = messages.size.toDouble()
        return hours.filter { it > 0 }
            .sumOf { count ->
                val p = count / total
                -p * ln(p) / ln(2.0)
            }
    }

    private fun isLateNight(time: LocalTime): Boolean {
        val minutes = time.hour * 60 + time.minute
        return minutes >= 30 && minutes < 390
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

fun main() {
    val levels = listOf(0.2, 0.5, 0.8)
    val seeds = listOf(42L, 1337L, 2026L)
    val results = mutableListOf<SimulationResult>()

    println("persona,c,seed,mean/day,late,dup,window3,quiet,cap,maxCalls,entropy,failures")
    for (persona in Personas.all) {
        for (c in levels) {
            for (seed in seeds) {
                val result = SimulationRunner(persona, c, seed).run()
                results += result
                println(
                    String.format(
                        Locale.ROOT,
                        "%s,%.1f,%d,%.2f,%d,%.3f,%s,%d,%d,%d,%.2f,%s",
                        result.persona.id,
                        result.clinginess,
                        result.seed,
                        result.meanPerDay,
                        result.lateNightCount,
                        result.duplicateRate,
                        result.sameWindowThreeDays,
                        result.quietViolations,
                        result.safetyCapViolations,
                        result.maxModelCallsPerDay,
                        result.entropy,
                        result.failures.joinToString("|")
                    )
                )
            }
        }
    }

    val groupFailures = checkMonotonicity(results)
    val failedRuns = results.filter { it.failures.isNotEmpty() }

    println()
    println("runs=${results.size}, failedRuns=${failedRuns.size}, groupFailures=${groupFailures.size}")
    failedRuns.forEach {
        println("FAIL ${it.persona.id} c=${it.clinginess} seed=${it.seed}: ${it.failures}")
    }
    groupFailures.forEach { println("GROUP FAIL: $it") }

    if (failedRuns.isNotEmpty() || groupFailures.isNotEmpty()) {
        exitProcess(1)
    }
}

private fun checkMonotonicity(results: List<SimulationResult>): List<String> {
    val failures = mutableListOf<String>()
    for (persona in Personas.all) {
        for (seed in listOf(42L, 1337L, 2026L)) {
            val group = results
                .filter { it.persona.id == persona.id && it.seed == seed }
                .sortedBy { it.clinginess }
            if (group.size != 3) continue
            val means = group.map { it.meanPerDay }
            if (!(means[0] < means[1] && means[1] < means[2])) {
                failures += "${persona.id} seed=$seed 滑块非单调: $means"
                continue
            }
            val base = means[0].coerceAtLeast(0.05)
            val growth = (means[2] - means[0]) / base
            if (growth < 0.4) {
                failures += "${persona.id} seed=$seed 冷热差不足: $means"
            }
        }
    }
    return failures
}
