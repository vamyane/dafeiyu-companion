package com.dafeiyu.companion.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CompanionDb(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "companion.db",
    null,
    VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                companion_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                at INTEGER NOT NULL,
                image_path TEXT,
                voice_path TEXT,
                is_voice INTEGER NOT NULL DEFAULT 0,
                voice_seconds INTEGER NOT NULL DEFAULT 0,
                sticker TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_companion ON messages(companion_id, id)")

        db.execSQL(
            """
            CREATE TABLE memories (
                id TEXT PRIMARY KEY,
                companion_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                subject TEXT,
                predicate TEXT,
                obj TEXT,
                importance INTEGER NOT NULL,
                confidence REAL NOT NULL,
                evidence REAL NOT NULL,
                created_at INTEGER NOT NULL,
                last_accessed_at INTEGER NOT NULL,
                source_ids TEXT NOT NULL,
                archived INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_companion ON memories(companion_id, archived, kind)")

        db.execSQL(
            """
            CREATE TABLE reminders (
                id TEXT PRIMARY KEY,
                companion_id TEXT NOT NULL,
                text TEXT NOT NULL,
                due_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                repeat_rule TEXT NOT NULL,
                source_text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_reminders_companion ON reminders(companion_id, status, due_at)")

        createRoomTables(db)
    }

    private fun createRoomTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE rooms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE room_members (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_id INTEGER NOT NULL,
                character_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                card_path TEXT,
                avatar_ref TEXT,
                talkativeness REAL NOT NULL DEFAULT 0.5,
                joined_at INTEGER NOT NULL,
                UNIQUE(room_id, character_id),
                FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_room_members_room ON room_members(room_id)")
        db.execSQL(
            """
            CREATE TABLE room_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_id INTEGER NOT NULL,
                character_id TEXT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                at INTEGER NOT NULL,
                reply_to TEXT,
                FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_room_messages_room ON room_messages(room_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN image_path TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN voice_path TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN is_voice INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN voice_seconds INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sticker TEXT")
        }
        if (oldVersion < 6) {
            createRoomTables(db)
        }
    }

    private companion object {
        const val VERSION = 6
    }
}
