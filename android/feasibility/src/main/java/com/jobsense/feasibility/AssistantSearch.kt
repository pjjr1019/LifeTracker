package com.jobsense.feasibility

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

/** Queries can only see explicit chosen chats; message text cannot become SQL or tool calls. */
object AssistantSearch {
    fun source(context: Context,id: String,chosen: Set<String>): AssistantEvidence? = synchronized(ArchiveStore) {
        val allowed=ArchiveStore.chats(context).map { it.id }.toSet().intersect(chosen)
        if(allowed.isEmpty()) return@synchronized null
        ConversationLedger.archiveDatabase(context).rawQuery(
            "SELECT m.id,m.chat_id,c.name,m.message_at,m.direction,m.body,m.source FROM archive_messages m JOIN archive_chats c ON c.id=m.chat_id WHERE m.id=? AND m.chat_id IN (${allowed.joinToString(",") { "?" }})",
            (listOf(id)+allowed).toTypedArray()).use { c ->
            if(c.moveToFirst()) AssistantEvidence(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5),c.getString(6)) else null
        }
    }
    private var indexReady = false
    @Synchronized private fun index(db: SQLiteDatabase): Boolean {
        if (indexReady) return true
        return try {
            db.beginTransaction()
            try {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS assistant_message_search USING fts4(message_id,body,tokenize=unicode61)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS assistant_message_insert AFTER INSERT ON archive_messages BEGIN INSERT INTO assistant_message_search(message_id,body) VALUES(new.id,new.body); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS assistant_message_update AFTER UPDATE OF body ON archive_messages BEGIN DELETE FROM assistant_message_search WHERE message_id=old.id; INSERT INTO assistant_message_search(message_id,body) VALUES(new.id,new.body); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS assistant_message_delete AFTER DELETE ON archive_messages BEGIN DELETE FROM assistant_message_search WHERE message_id=old.id; END")
                db.execSQL("INSERT INTO assistant_message_search(message_id,body) SELECT id,body FROM archive_messages WHERE id NOT IN (SELECT message_id FROM assistant_message_search)")
                db.setTransactionSuccessful(); indexReady = true
            } finally { db.endTransaction() }
            true
        } catch (_: SQLiteException) { false }
    }
    fun search(context: Context, question: String, chosen: Set<String>): List<AssistantEvidence> = synchronized(ArchiveStore) {
        require(question.length in 1..1000)
        val chats=ArchiveStore.chats(context).filter { it.id in chosen }
        val questionTerms=WorkAssistantCore.terms(question)
        val named=chats.filter { chat -> WorkAssistantCore.terms(chat.name).any { it.length>=3 && it in questionTerms } }
        val allowed = (named.ifEmpty { chats }).map { it.id }.toSet()
        if (allowed.isEmpty()) return@synchronized emptyList()
        val zone=java.time.ZoneId.of("America/New_York")
        val historyDate=WorkAssistantCore.messageHistoryDate(question,java.time.LocalDate.now(zone))
        val terms = questionTerms.filter { term -> named.none { term in WorkAssistantCore.terms(it.name) } }
        if (terms.isEmpty() && named.isEmpty() && historyDate==null) return@synchronized emptyList()
        val db = ConversationLedger.archiveDatabase(context)
        val fts = index(db)
        val args = allowed.toMutableList()
        val clause = if(historyDate!=null || terms.isEmpty()) "1=1" else if (fts) {
            args.add(terms.joinToString(" OR ") { "\"$it\"" })
            "m.id IN (SELECT message_id FROM assistant_message_search WHERE body MATCH ?)"
        } else {
            args.addAll(terms.map { "%$it%" }); "(${terms.joinToString(" OR ") { "m.body LIKE ?" }})"
        }
        val dateClause=if(historyDate!=null) {
            args.add(historyDate.atStartOfDay(zone).toInstant().toEpochMilli().toString())
            args.add(historyDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString())
            "AND m.message_at>=? AND m.message_at<? "
        } else ""
        db.rawQuery("SELECT m.id,m.chat_id,c.name,m.message_at,m.direction,m.body,m.source FROM archive_messages m " +
            "JOIN archive_chats c ON c.id=m.chat_id WHERE m.chat_id IN (${allowed.joinToString(",") { "?" }}) AND $clause " +
            dateClause+"AND NOT EXISTS(SELECT 1 FROM archive_receipt_links WHERE receipt_id=m.id) ORDER BY m.message_at DESC LIMIT 120", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(AssistantEvidence(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getString(5),c.getString(6))) }
                .sortedWith(compareByDescending<AssistantEvidence> { e -> terms.count { e.body.contains(it, true) } }.thenByDescending { it.at }).take(6)
        }
    }
}
