package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class BrainNodeKind { CENTER, FACT, REFLECTION, RECENT }

enum class BrainEdgeKind { HUB, REFLECTION_FACT, SUBJECT }

data class BrainNode(
    val id: String,
    val label: String,
    val kind: BrainNodeKind,
    val nx: Float,
    val ny: Float,
    val radius: Float,
    val detail: String,
    val meta: String
)

data class BrainEdge(
    val from: String,
    val to: String,
    val kind: BrainEdgeKind
)

data class BrainGraph(
    val companionName: String,
    val nodes: List<BrainNode>,
    val edges: List<BrainEdge>,
    val facts: Int,
    val reflections: Int,
    val recentMessages: Int,
    val generatedAt: Instant
)

data class BrainUiState(
    val loading: Boolean = true,
    val updating: Boolean = false,
    val graph: BrainGraph? = null,
    val error: String? = null
)

object BrainGraphBuilder {

    fun build(
        companionName: String,
        entries: List<MemoryEntry>,
        recent: List<ChatMessage>,
        now: Instant
    ): BrainGraph {
        val facts = entries
            .filter { it.kind == MemoryKind.FACT && !it.archived }
            .sortedByDescending { it.importance }
            .take(60)
        val reflections = entries
            .filter { it.kind == MemoryKind.REFLECTION && !it.archived }
            .sortedByDescending { it.createdAt }
            .take(20)
        val messages = recent.takeLast(14)

        val centerId = "center"
        val nodes = mutableListOf<BrainNode>()
        val edges = mutableListOf<BrainEdge>()

        nodes += BrainNode(
            id = centerId,
            label = companionName,
            kind = BrainNodeKind.CENTER,
            nx = 0.5f,
            ny = 0.5f,
            radius = 15f,
            detail = "这里是它的记忆中心。事实、日记和最近的对话会围绕它排布。",
            meta = "记忆中心"
        )

        fun position(index: Int, total: Int, ring: Float, phase: Float): Pair<Float, Float> {
            val golden = 2.399963f
            val angle = index * golden + phase
            val r = ring + 0.20f * sqrt((index + 1f) / (total + 1f))
            return (0.5f + cos(angle) * r).coerceIn(0.10f, 0.90f) to
                (0.5f + sin(angle) * r * 0.82f).coerceIn(0.12f, 0.88f)
        }

        facts.forEachIndexed { index, entry ->
            val (x, y) = position(index, facts.size, 0.10f, 0.4f)
            val id = "fact-${entry.id}"
            nodes += BrainNode(
                id = id,
                label = entry.text.take(12),
                kind = BrainNodeKind.FACT,
                nx = x,
                ny = y,
                radius = 5f + entry.importance * 0.6f,
                detail = entry.text,
                meta = "事实 · 重要度 ${entry.importance} · ${formatTime(entry.createdAt)}"
            )
            edges += BrainEdge(centerId, id, BrainEdgeKind.HUB)
        }

        reflections.forEachIndexed { index, entry ->
            val (x, y) = position(index, reflections.size, 0.08f, 2.2f)
            val id = "reflection-${entry.id}"
            nodes += BrainNode(
                id = id,
                label = "日记 · ${entry.text.take(10)}",
                kind = BrainNodeKind.REFLECTION,
                nx = x,
                ny = y,
                radius = 7f + entry.importance * 0.5f,
                detail = entry.text,
                meta = "日记 · 重要度 ${entry.importance} · ${formatTime(entry.createdAt)}"
            )
            edges += BrainEdge(centerId, id, BrainEdgeKind.HUB)
            entry.sourceMessageIds.forEach { source ->
                val target = "fact-$source"
                if (nodes.any { it.id == target }) {
                    edges += BrainEdge(id, target, BrainEdgeKind.REFLECTION_FACT)
                }
            }
        }

        messages.forEachIndexed { index, message ->
            val count = messages.size.coerceAtLeast(1)
            val angle = (2 * PI * index / count).toFloat()
            val id = "recent-$index"
            nodes += BrainNode(
                id = id,
                label = message.text.take(8),
                kind = BrainNodeKind.RECENT,
                nx = (0.5f + cos(angle) * 0.40f).coerceIn(0.08f, 0.92f),
                ny = (0.5f + sin(angle) * 0.36f).coerceIn(0.10f, 0.90f),
                radius = 2.8f,
                detail = message.text,
                meta = if (message.role == "user") {
                    "你说 · ${formatTime(message.at)}"
                } else {
                    "它说 · ${formatTime(message.at)}"
                }
            )
            edges += BrainEdge(centerId, id, BrainEdgeKind.HUB)
        }

        val factsBySubject = facts
            .filter { !it.subject.isNullOrBlank() }
            .groupBy { it.subject }
        factsBySubject.values.forEach { group ->
            val first = group.firstOrNull() ?: return@forEach
            group.drop(1).take(4).forEach { other ->
                edges += BrainEdge(
                    "fact-${first.id}",
                    "fact-${other.id}",
                    BrainEdgeKind.SUBJECT
                )
            }
        }

        return BrainGraph(
            companionName = companionName,
            nodes = nodes,
            edges = edges.distinctBy {
                listOf(it.from, it.to).sorted().joinToString("|") + "|" + it.kind
            },
            facts = facts.size,
            reflections = reflections.size,
            recentMessages = messages.size,
            generatedAt = now
        )
    }

    private fun formatTime(instant: Instant): String =
        instant.atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
}
