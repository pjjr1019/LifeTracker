package com.jobsense.feasibility

import android.database.sqlite.SQLiteDatabase

internal object ArchiveSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_chats (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, " +
            "number TEXT NOT NULL DEFAULT '', raw_number TEXT NOT NULL DEFAULT '', country TEXT NOT NULL DEFAULT '', " +
            "thread_id INTEGER NOT NULL DEFAULT -1, participants TEXT NOT NULL DEFAULT '[]', saving INTEGER NOT NULL DEFAULT 1, " +
            "sharing INTEGER NOT NULL DEFAULT 0, coverage TEXT NOT NULL DEFAULT 'Older RCS history has not been verified', last_check INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_messages (id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, source TEXT NOT NULL, " +
            "source_id TEXT NOT NULL, message_at INTEGER NOT NULL, observed_at INTEGER NOT NULL, direction TEXT NOT NULL, " +
            "sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL DEFAULT 0, " +
            "UNIQUE(chat_id, source, source_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS archive_messages_order ON archive_messages(chat_id, message_at, id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_unverified_messages AS SELECT * FROM archive_messages WHERE 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_receipt_links (receipt_id TEXT PRIMARY KEY, provider_id TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_attachments (id TEXT PRIMARY KEY, message_id TEXT NOT NULL, " +
            "name TEXT NOT NULL, mime TEXT NOT NULL, uri TEXT NOT NULL, path TEXT NOT NULL DEFAULT '', bytes INTEGER NOT NULL DEFAULT 0, " +
            "sha256 TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'PENDING', drive_id TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_unverified_attachments AS SELECT * FROM archive_attachments WHERE 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL DEFAULT 0, " +
            "uploaded_revision INTEGER NOT NULL DEFAULT -1, uploaded_at INTEGER NOT NULL DEFAULT 0, seeded INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT OR IGNORE INTO archive_state(id) VALUES(1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_recoveries (sha256 TEXT PRIMARY KEY, imported_at INTEGER NOT NULL, path TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_uploads (logical_key TEXT PRIMARY KEY, drive_id TEXT NOT NULL DEFAULT '', " +
            "session TEXT NOT NULL DEFAULT '', sha256 TEXT NOT NULL DEFAULT '', bytes INTEGER NOT NULL DEFAULT 0)")
    }
}
