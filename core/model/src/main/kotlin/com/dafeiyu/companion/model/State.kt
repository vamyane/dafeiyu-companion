package com.dafeiyu.companion.model

import java.time.Instant

enum class Activity { SLEEPING, WORKING, COMMUTING, EATING, IDLE, PLAYING }

enum class ContactMode { NORMAL, CAUTIOUS, QUIET, DORMANT }

/**
 * 7 x 96 的活跃直方图。只在用户事件发生时复制，tick 之间共享同一份。
 */
class ActiveHistogram private constructor(private val bins: DoubleArray) {

    companion object {
        const val DAYS = 7
        const val SLOTS = 96

        fun empty(): ActiveHistogram = ActiveHistogram(DoubleArray(DAYS * SLOTS))

        fun of(bins: DoubleArray): ActiveHistogram {
            require(bins.size == DAYS * SLOTS) {
                "histogram 长度必须是 ${DAYS * SLOTS}，实际 ${bins.size}"
            }
            return ActiveHistogram(bins.copyOf())
        }

        fun default(): ActiveHistogram {
            val values = DoubleArray(DAYS * SLOTS)
            for (day in 0 until DAYS) {
                for (slot in 0 until SLOTS) {
                    val hour = slot / 4
                    values[day * SLOTS + slot] = when (hour) {
                        in 7..8 -> 0.6
                        12, 13 -> 0.7
                        in 18..22 -> 0.9
                        in 0..6 -> 0.05
                        else -> 0.15
                    }
                }
            }
            return ActiveHistogram(values)
        }
    }

    fun value(dayOfWeek: Int, slot: Int): Double = bins[index(dayOfWeek, slot)]

    fun set(dayOfWeek: Int, slot: Int, value: Double): ActiveHistogram {
        val copy = bins.copyOf()
        copy[index(dayOfWeek, slot)] = value.coerceAtLeast(0.0)
        return ActiveHistogram(copy)
    }

    fun bump(dayOfWeek: Int, slot: Int, weight: Double = 1.0, alpha: Double = 0.1): ActiveHistogram {
        val old = value(dayOfWeek, slot)
        return set(dayOfWeek, slot, old + alpha * (weight - old))
    }

    fun normalized(dayOfWeek: Int, slot: Int): Double {
        val max = max()
        if (max <= 0.0) return 0.0
        return (value(dayOfWeek, slot) / max).coerceIn(0.0, 1.0)
    }

    fun max(): Double = bins.maxOrNull() ?: 0.0

    fun copyBins(): DoubleArray = bins.copyOf()

    private fun index(dayOfWeek: Int, slot: Int): Int {
        require(dayOfWeek in 0 until DAYS) { "dayOfWeek out of range: $dayOfWeek" }
        require(slot in 0 until SLOTS) { "slot out of range: $slot" }
        return dayOfWeek * SLOTS + slot
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ActiveHistogram && bins.contentEquals(other.bins))

    override fun hashCode(): Int = bins.contentHashCode()
}

data class OpenerRecord(
    val at: Instant,
    val text: String,
    val intent: String,
    val timeWindow: TimeWindow
)

data class IntentRecord(
    val dayKey: String,
    val intent: String
)

enum class MemoryKind { RECENT, FACT, REFLECTION }

data class MemoryEntry(
    val id: String,
    val companionId: String,
    val kind: MemoryKind,
    val text: String,
    val subject: String? = null,
    val predicate: String? = null,
    val obj: String? = null,
    val importance: Int = 1,
    val confidence: Double = 0.5,
    val evidence: Double = 0.5,
    val createdAt: Instant,
    val lastAccessedAt: Instant,
    val sourceMessageIds: List<String> = emptyList(),
    val archived: Boolean = false
)

data class CompanionState(
    val companionId: String,
    val energy: Int = 70,
    val mood: Int = 10,
    val affection: Int = 0,
    val missing: Int = 0,
    val clinginess: Double = 0.5,
    val activity: Activity = Activity.IDLE,
    val contactMode: ContactMode = ContactMode.NORMAL,
    val lastUserMessageAt: Instant? = null,
    val lastCompanionMessageAt: Instant? = null,
    val lastOpenedAt: Instant? = null,
    val lastProactiveAt: Instant? = null,
    val unreadCount: Int = 0,
    val unansweredStreak: Int = 0,
    val quietUntil: Instant? = null,
    val todaySpokenCount: Int = 0,
    val todayModelCalls: Int = 0,
    val userMessageTotal: Int = 0,
    val dayKey: String = "",
    val lastTickAt: Instant,
    val nextProactiveCheckAt: Instant? = null,
    val recentOpeners: List<OpenerRecord> = emptyList(),
    val recentIntents: List<IntentRecord> = emptyList(),
    val userActiveHistogram: ActiveHistogram = ActiveHistogram.empty()
) {
    val rejectionSensitivity: Double get() = 0.5 + 0.5 * clinginess
    val missingRatePerHour: Double get() = 0.6 + 2.4 * clinginess
    val absenceToleranceHours: Double get() = 40.0 - 28.0 * clinginess
    val dormantCooldownDays: Double get() = (12.0 - 8.0 * clinginess).coerceAtLeast(3.0)
}
