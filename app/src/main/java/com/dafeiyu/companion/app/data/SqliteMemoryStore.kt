package com.dafeiyu.companion.app.data

import android.content.ContentValues
import android.database.Cursor
import com.dafeiyu.companion.memory.Bm25
import com.dafeiyu.companion.memory.MemoryStore
import com.dafeiyu.companion.memory.ScoredMemory
import com.dafeiyu.companion.memory.Tokenizer
import com.dafeiyu.companion.model.ChatMessage
import com.dafeiyu.companion.model.MemoryEntry
import com.dafeiyu.companion.model.MemoryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

class SqliteMemoryStore(private val helper: CompanionDb) : MemoryStore {

    override fun appendRecent(companionId: String, message: ChatMessage) {
        val db = helper.writableDatabase
        db.insert(
            "messages",
            null,
            ContentValues().apply {
                put("companion_id", companionId)
                put("role", message.role)
                put("text", message.text)
                put("at", message.at.toEpochMilli())
                put("image_path", message.imagePath)
                put("voice_path", message.voicePath)
                put("is_voice", if (message.voice || message.voicePath != null) 1 else 0)
                put("voice_seconds", message.voiceSeconds)
                put("sticker", message.sticker)
            }
        )
        db.execSQL(
            """
            DELETE FROM messages
            WHERE companion_id = ?
              AND id NOT IN (
                SELECT id FROM messages WHERE companion_id = ? ORDER BY id DESC LIMIT 80
              )
            """.trimIndent(),
            arrayOf(companionId, companionId)
        )
    }

    override fun recent(companionId: String, limit: Int): List<ChatMessage> {
        val db = helper.readableDatabase
        val messages = mutableListOf<ChatMessage>()
        db.query(
            "messages",
            arrayOf(
                "role", "text", "at", "image_path", "voice_path",
                "is_voice", "voice_seconds", "sticker"
            ),
            "companion_id = ?",
            arrayOf(companionId),
            null,
            null,
            "id DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    role = cursor.getString(0),
                    text = cursor.getString(1),
                    at = Instant.ofEpochMilli(cursor.getLong(2)),
                    imagePath = cursor.getString(3),
                    voicePath = cursor.getString(4),
                    voice = cursor.getInt(5) != 0,
                    voiceSeconds = cursor.getInt(6),
                    sticker = cursor.getString(7)
                )
            }
        }
        return messages.asReversed()
    }

    override fun upsert(entry: MemoryEntry) {
        val db = helper.writableDatabase
        if (entry.kind == MemoryKind.FACT &&
            entry.subject != null &&
            entry.predicate != null &&
            entry.obj != null
        ) {
            db.query(
                "memories",
                arrayOf("id", "evidence", "obj"),
                "companion_id = ? AND kind = ? AND archived = 0 AND subject = ? AND predicate = ? AND obj != ?",
                arrayOf(
                    entry.companionId,
                    MemoryKind.FACT.name,
                    entry.subject,
                    entry.predicate,
                    entry.obj
                ),
                null,
                null,
                "created_at DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getString(0)
                    val evidence = (cursor.getDouble(1) - 0.2).coerceAtLeast(0.0)
                    db.update(
                        "memories",
                        ContentValues().apply { put("evidence", evidence) },
                        "id = ?",
                        arrayOf(id)
                    )
                }
            }
        }
        db.insertWithOnConflict("memories", null, entryValues(entry), 4)
    }

    override fun get(companionId: String, id: String): MemoryEntry? {
        helper.readableDatabase.query(
            "memories",
            null,
            "companion_id = ? AND id = ?",
            arrayOf(companionId, id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    override fun recall(
        companionId: String,
        query: String,
        limit: Int,
        now: Instant
    ): List<ScoredMemory> {
        val candidates = all(companionId)
            .filter { !it.archived && it.kind != MemoryKind.RECENT }
        if (candidates.isEmpty()) return emptyList()

        val docs = candidates.map { Tokenizer.tokenize(it.text) }
        val bm25 = Bm25(docs)
        val queryTokens = Tokenizer.tokenize(query)
        val rawScores = candidates.indices.map { bm25.score(queryTokens, it) }
        val maxRaw = rawScores.maxOrNull() ?: 0.0

        return candidates.mapIndexed { index, entry ->
            val relevance = if (maxRaw > 0.0) rawScores[index] / maxRaw else 0.0
            val days = Duration.between(entry.createdAt, now).toMinutes()
                .coerceAtLeast(0) / 1440.0
            val recency = exp(-days / 30.0)
            val importance = entry.importance.coerceIn(0, 5) / 5.0
            val evidence = entry.evidence.coerceIn(0.0, 1.0)
            ScoredMemory(
                entry = entry,
                score = 0.45 * relevance + 0.25 * recency + 0.20 * importance + 0.10 * evidence,
                relevance = relevance,
                recency = recency,
                importance = importance,
                evidence = evidence
            )
        }.sortedByDescending { it.score }.take(limit).also { result ->
            // 被想起的记忆会重新变得清晰，这是遗忘曲线里“复习”的那一部分。
            helper.writableDatabase.let { db ->
                result.forEach { scored ->
                    if (Duration.between(scored.entry.lastAccessedAt, now).toHours() >= 1) {
                        db.execSQL(
                            "UPDATE memories SET last_accessed_at = ? WHERE companion_id = ? AND id = ?",
                            arrayOf(now.toEpochMilli(), companionId, scored.entry.id)
                        )
                    }
                }
            }
        }
    }

    override fun all(companionId: String): List<MemoryEntry> {
        val entries = mutableListOf<MemoryEntry>()
        helper.readableDatabase.query(
            "memories",
            null,
            "companion_id = ?",
            arrayOf(companionId),
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) entries += cursor.toEntry()
        }
        return entries
    }

    override fun delete(companionId: String, id: String): Boolean =
        helper.writableDatabase.delete(
            "memories",
            "companion_id = ? AND id = ?",
            arrayOf(companionId, id)
        ) > 0

    override fun purge(companionId: String) {
        val db = helper.writableDatabase
        db.delete("messages", "companion_id = ?", arrayOf(companionId))
        db.delete("memories", "companion_id = ?", arrayOf(companionId))
    }

    override fun exportJson(companionId: String): String = buildJsonObject {
        put("companionId", companionId)
        put("entries", buildJsonArray {
            for (entry in all(companionId)) {
                add(buildJsonObject {
                    put("id", entry.id)
                    put("kind", entry.kind.name)
                    put("text", entry.text)
                    put("subject", entry.subject)
                    put("predicate", entry.predicate)
                    put("object", entry.obj)
                    put("importance", entry.importance)
                    put("confidence", entry.confidence)
                    put("evidence", entry.evidence)
                    put("createdAt", entry.createdAt.toString())
                    put("lastAccessedAt", entry.lastAccessedAt.toString())
                    put("archived", entry.archived)
                })
            }
        })
    }.toString()

    override fun archiveStale(
        companionId: String,
        now: Instant,
        staleDays: Long,
        minImportance: Int
    ): Int {
        val threshold = now.minus(Duration.ofDays(staleDays)).toEpochMilli()
        return helper.writableDatabase.update(
            "memories",
            ContentValues().apply { put("archived", 1) },
            "companion_id = ? AND archived = 0 AND last_accessed_at < ? AND importance <= ?",
            arrayOf(companionId, threshold.toString(), minImportance.toString())
        )
    }

    private fun entryValues(entry: MemoryEntry): ContentValues = ContentValues().apply {
        put("id", entry.id)
        put("companion_id", entry.companionId)
        put("kind", entry.kind.name)
        put("text", entry.text)
        put("subject", entry.subject)
        put("predicate", entry.predicate)
        put("obj", entry.obj)
        put("importance", entry.importance)
        put("confidence", entry.confidence)
        put("evidence", entry.evidence)
        put("created_at", entry.createdAt.toEpochMilli())
        put("last_accessed_at", entry.lastAccessedAt.toEpochMilli())
        put("source_ids", buildJsonArray {
            entry.sourceMessageIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }.toString())
        put("archived", if (entry.archived) 1 else 0)
    }

    private fun Cursor.toEntry(): MemoryEntry = MemoryEntry(
        id = getString(getColumnIndexOrThrow("id")),
        companionId = getString(getColumnIndexOrThrow("companion_id")),
        kind = runCatching {
            MemoryKind.valueOf(getString(getColumnIndexOrThrow("kind")))
        }.getOrDefault(MemoryKind.FACT),
        text = getString(getColumnIndexOrThrow("text")),
        subject = getString(getColumnIndexOrThrow("subject")),
        predicate = getString(getColumnIndexOrThrow("predicate")),
        obj = getString(getColumnIndexOrThrow("obj")),
        importance = getInt(getColumnIndexOrThrow("importance")),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        evidence = getDouble(getColumnIndexOrThrow("evidence")),
        createdAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("created_at"))),
        lastAccessedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("last_accessed_at"))),
        sourceMessageIds = runCatching {
            Json.parseToJsonElement(getString(getColumnIndexOrThrow("source_ids")))
                .jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList()),
        archived = getInt(getColumnIndexOrThrow("archived")) != 0
    )
}
