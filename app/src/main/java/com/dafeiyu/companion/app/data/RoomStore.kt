package com.dafeiyu.companion.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dafeiyu.companion.room.RoomSpeaker
import com.dafeiyu.companion.room.RoomTurn

data class RoomRecord(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class RoomMemberRecord(
    val id: Long,
    val roomId: Long,
    val characterId: String,
    val displayName: String,
    val cardPath: String?,
    val avatarRef: String?,
    val talkativeness: Double
)

data class RoomMessageRecord(
    val id: Long,
    val roomId: Long,
    val characterId: String?,
    val role: String,
    val text: String,
    val at: Long,
    val replyTo: String?
) {
    fun toTurn(): RoomTurn = RoomTurn(
        role = role,
        speakerId = characterId,
        speakerName = characterId,
        text = text
    )
}

data class RoomMemberSeed(
    val characterId: String,
    val displayName: String,
    val cardPath: String?,
    val avatarRef: String?,
    val talkativeness: Double
)

/**
 * 房间数据的 SQLite 实现。房间时间线与单聊消息完全分开，避免两个模式串台。
 */
class RoomStore(private val db: CompanionDb) {

    private val readable: SQLiteDatabase get() = db.readableDatabase
    private val writable: SQLiteDatabase get() = db.writableDatabase

    fun createRoom(name: String, members: List<RoomMemberSeed>, now: Long): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("created_at", now)
            put("updated_at", now)
        }
        val roomId = writable.insert("rooms", null, values)
        members.forEach { seed ->
            writable.insert("room_members", null, ContentValues().apply {
                put("room_id", roomId)
                put("character_id", seed.characterId)
                put("display_name", seed.displayName)
                put("card_path", seed.cardPath)
                put("avatar_ref", seed.avatarRef)
                put("talkativeness", seed.talkativeness)
                put("joined_at", now)
            })
        }
        return roomId
    }

    fun rooms(): List<RoomRecord> {
        val out = mutableListOf<RoomRecord>()
        readable.query(
            "rooms", null, null, null, null, null, "updated_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    RoomRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return out
    }

    fun room(roomId: Long): RoomRecord? = rooms().firstOrNull { it.id == roomId }

    fun members(roomId: Long): List<RoomMemberRecord> {
        val out = mutableListOf<RoomMemberRecord>()
        readable.query(
            "room_members", null, "room_id = ?", arrayOf(roomId.toString()),
            null, null, "joined_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    RoomMemberRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        roomId = cursor.getLong(cursor.getColumnIndexOrThrow("room_id")),
                        characterId = cursor.getString(cursor.getColumnIndexOrThrow("character_id")),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                        cardPath = cursor.getString(cursor.getColumnIndexOrThrow("card_path")),
                        avatarRef = cursor.getString(cursor.getColumnIndexOrThrow("avatar_ref")),
                        talkativeness = cursor.getDouble(cursor.getColumnIndexOrThrow("talkativeness"))
                    )
                )
            }
        }
        return out
    }

    fun appendMessage(
        roomId: Long,
        characterId: String?,
        role: String,
        text: String,
        at: Long,
        replyTo: String? = null
    ): RoomMessageRecord {
        val values = ContentValues().apply {
            put("room_id", roomId)
            put("character_id", characterId)
            put("role", role)
            put("text", text)
            put("at", at)
            put("reply_to", replyTo)
        }
        val id = writable.insert("room_messages", null, values)
        writable.update(
            "rooms",
            ContentValues().apply { put("updated_at", at) },
            "id = ?",
            arrayOf(roomId.toString())
        )
        return RoomMessageRecord(id, roomId, characterId, role, text, at, replyTo)
    }

    fun messages(roomId: Long, limit: Int = 80): List<RoomMessageRecord> {
        val out = mutableListOf<RoomMessageRecord>()
        readable.query(
            "room_messages", null, "room_id = ?", arrayOf(roomId.toString()),
            null, null, "id DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(readMessage(cursor))
            }
        }
        return out.asReversed()
    }

    fun lastMessage(roomId: Long): RoomMessageRecord? {
        readable.query(
            "room_messages", null, "room_id = ?", arrayOf(roomId.toString()),
            null, null, "id DESC", "1"
        ).use { cursor ->
            if (cursor.moveToNext()) return readMessage(cursor)
        }
        return null
    }

    fun deleteRoom(roomId: Long) {
        writable.delete("room_messages", "room_id = ?", arrayOf(roomId.toString()))
        writable.delete("room_members", "room_id = ?", arrayOf(roomId.toString()))
        writable.delete("rooms", "id = ?", arrayOf(roomId.toString()))
    }

    fun memberCount(roomId: Long): Int {
        readable.rawQuery(
            "SELECT COUNT(*) FROM room_members WHERE room_id = ?",
            arrayOf(roomId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    private fun readMessage(cursor: Cursor): RoomMessageRecord = RoomMessageRecord(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        roomId = cursor.getLong(cursor.getColumnIndexOrThrow("room_id")),
        characterId = cursor.getString(cursor.getColumnIndexOrThrow("character_id")),
        role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
        text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
        at = cursor.getLong(cursor.getColumnIndexOrThrow("at")),
        replyTo = cursor.getString(cursor.getColumnIndexOrThrow("reply_to"))
    )

    fun speakers(members: List<RoomMemberRecord>, cardKeywords: (RoomMemberRecord) -> Set<String>): List<RoomSpeaker> =
        members.map {
            RoomSpeaker(
                id = it.characterId,
                name = it.displayName,
                talkativeness = it.talkativeness,
                keywords = cardKeywords(it)
            )
        }
}
