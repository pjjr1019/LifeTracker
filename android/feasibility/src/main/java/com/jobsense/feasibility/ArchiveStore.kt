package com.jobsense.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ArchiveChat(val id: String, val name: String, val kind: String, val number: String,
    val rawNumber: String, val country: String, val threadId: Long, val participants: List<String>,
    val saving: Boolean, val sharing: Boolean, val coverage: String, val lastCheck: Long,
    val latestMessage: Long = 0, val messageCount: Int = 0) {
    fun individualScope() = HistoryScope(name, number, rawNumber, country)
}

data class ArchiveAttachment(val id: String, val messageId: String, val name: String, val mime: String,
    val uri: String, val path: String, val bytes: Long, val sha256: String, val state: String, val driveId: String)
data class ArchiveMessage(val id: String, val chatId: String, val source: String, val sourceId: String,
    val at: Long, val observedAt: Long, val direction: String, val sender: String, val body: String,
    val attachments: List<ArchiveAttachment> = emptyList())
data class ArchiveStatus(val revision: Long, val uploadedRevision: Long, val uploadedAt: Long,
    val latestMessage: Long, val pendingAttachments: Int, val attachmentsWaitingForUpload: Int = 0,
    val attachmentsWaitingForDownload: Int = 0, val unavailableAttachments: Int = 0)

/** Selected conversations only. Original diagnostic evidence is never deleted or rewritten. */
object ArchiveStore {
    internal fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    internal fun hash(value: String) = hash(value.toByteArray(Charsets.UTF_8))
    private fun db(context: Context) = ConversationLedger.archiveDatabase(context)
    internal fun dirty(db: SQLiteDatabase) = db.execSQL("UPDATE archive_state SET revision=revision+1 WHERE id=1")

    @Synchronized fun initialize(context: Context) {
        val db = db(context)
        if (db.rawQuery("SELECT seeded FROM archive_state WHERE id=1", null).use { it.moveToFirst(); it.getInt(0) == 1 }) return
        val prefs = DiagnosticStore.preferences(context)
        val shared = JSONArray(prefs.getString("autoDocumentScopes", "[]")).let { entries ->
            (0 until entries.length()).map { entries.getJSONObject(it).getString("number") }.toSet()
        }
        val scopes = (ConversationSaving.scopes(context) + ChatHistoryStore.importedScopes(context)).distinctBy { it.key }
        db.beginTransaction()
        try {
            scopes.forEach { scope -> insertChat(db, scope, ConversationSaving.isEnabled(context, scope), scope.number in shared) }
            db.rawQuery("SELECT scope,source,evidence_key,message_at,observed_at,payload FROM evidence", null).use { c ->
                while (c.moveToNext()) {
                    val chat = scopes.firstOrNull { it.key == c.getString(0) } ?: continue
                    val payload = JSONObject(c.getString(5))
                    val sms = c.getString(1) == "ANDROID_SMS_STORE"
                    putMessage(db, chat.key, c.getString(1), if (sms) payload.optLong("providerId").toString() else c.getString(2),
                        c.getLong(3), payload.optString("direction", "UNKNOWN"), payload.optString("address", chat.number),
                        payload.optString("body"), payload.optLong("dateSentMillis"), c.getLong(4))
                }
            }
            db.execSQL("UPDATE archive_state SET seeded=1 WHERE id=1")
            dirty(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun insertChat(db: SQLiteDatabase, scope: HistoryScope, saving: Boolean, sharing: Boolean) {
        db.insertWithOnConflict("archive_chats", null, ContentValues().apply {
            put("id", scope.key); put("name", scope.name); put("kind", "INDIVIDUAL"); put("number", scope.number)
            put("raw_number", scope.rawNumber); put("country", scope.country); put("saving", if (saving) 1 else 0)
            put("sharing", if (sharing) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun addContact(context: Context, scope: HistoryScope, sharing: Boolean = false) {
        initialize(context)
        val db = db(context)
        db.beginTransaction()
        try {
            insertChat(db, scope, true, sharing)
            db.update("archive_chats", ContentValues().apply { put("name", scope.name); put("saving", 1) }, "id=?", arrayOf(scope.key))
            dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ArchiveWork.schedule(context)
        if (sharing) DriveArchive.requestSync(context)
    }

    @Synchronized fun addContacts(context: Context, scopes: List<HistoryScope>, sharing: Boolean) {
        require(scopes.isNotEmpty())
        initialize(context); val db = db(context); db.beginTransaction()
        try {
            scopes.distinctBy { it.key }.forEach { scope ->
                insertChat(db, scope, true, sharing)
                db.update("archive_chats", ContentValues().apply { put("name", scope.name); put("saving", 1); if (sharing) put("sharing", 1) }, "id=?", arrayOf(scope.key))
            }
            dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ArchiveWork.schedule(context)
        if (sharing) DriveArchive.requestSync(context)
    }

    @Synchronized internal fun registerLegacyContact(context: Context, scope: HistoryScope) {
        initialize(context); insertChat(db(context), scope, true, false); dirty(db(context))
    }

    @Synchronized fun addGroup(context: Context, thread: ArchiveThread, name: String, sharing: Boolean = false) {
        require(thread.participants.size > 1)
        initialize(context)
        val db = db(context)
        db.beginTransaction()
        try {
            db.insertWithOnConflict("archive_chats", null, ContentValues().apply {
                put("id", "group:${thread.id}"); put("name", name.ifBlank { "Work group" }); put("kind", "GROUP")
                put("thread_id", thread.id); put("participants", JSONArray(thread.participants).toString())
                put("sharing", if (sharing) 1 else 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ArchiveWork.schedule(context)
        if (sharing) DriveArchive.requestSync(context)
    }

    @Synchronized fun configure(context: Context, id: String, saving: Boolean? = null, sharing: Boolean? = null, name: String? = null) {
        initialize(context)
        require(chats(context).any { it.id == id })
        val values = ContentValues().apply {
            saving?.let { put("saving", if (it) 1 else 0) }
            sharing?.let { put("sharing", if (it) 1 else 0) }
            name?.let { require(it.isNotBlank()); put("name", it.trim()) }
        }
        if (values.size() == 0) return
        val db = db(context)
        db.beginTransaction()
        try { db.update("archive_chats", values, "id=?", arrayOf(id)); dirty(db); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        ArchiveWork.schedule(context)
        DriveArchive.requestSync(context)
    }

    @Synchronized fun chats(context: Context): List<ArchiveChat> {
        initialize(context)
        return db(context).rawQuery("SELECT c.*, (SELECT COALESCE(MAX(message_at),0) FROM archive_messages WHERE chat_id=c.id) AS latest, " +
            "(SELECT COUNT(*) FROM archive_messages m WHERE chat_id=c.id AND NOT EXISTS(SELECT 1 FROM archive_receipt_links WHERE receipt_id=m.id)) AS total FROM archive_chats c ORDER BY name COLLATE NOCASE,id", null).use { c ->
            buildList { while (c.moveToNext()) add(chat(c)) }
        }
    }

    private fun chat(c: Cursor) = ArchiveChat(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
        c.getString(5), c.getLong(6), JSONArray(c.getString(7)).let { a -> (0 until a.length()).map { a.getString(it) } },
        c.getInt(8) == 1, c.getInt(9) == 1, c.getString(10), c.getLong(11), c.getLong(12), c.getInt(13))

    @Synchronized fun status(context: Context): ArchiveStatus {
        initialize(context)
        val db = db(context)
        val state = db.rawQuery("SELECT revision,uploaded_revision,uploaded_at FROM archive_state WHERE id=1", null).use {
            it.moveToFirst(); Triple(it.getLong(0), it.getLong(1), it.getLong(2))
        }
        val latest = db.rawQuery("SELECT COALESCE(MAX(message_at),0) FROM archive_messages", null).use { it.moveToFirst(); it.getLong(0) }
        val counts = db.rawQuery("SELECT a.state,COUNT(*) FROM archive_attachments a JOIN archive_messages m ON m.id=a.message_id " +
            "JOIN archive_chats c ON c.id=m.chat_id WHERE c.sharing=1 AND a.state!='UPLOADED' GROUP BY a.state", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
        return ArchiveStatus(state.first, state.second, state.third, latest, counts.values.sum(),
            counts["SAVED"] ?: 0, counts["PENDING"] ?: 0, counts["UNAVAILABLE"] ?: 0)
    }

    @Synchronized internal fun acknowledge(context: Context, revision: Long) {
        initialize(context)
        acknowledge(db(context), revision, System.currentTimeMillis())
    }

    internal fun acknowledge(db: SQLiteDatabase, revision: Long, confirmedAt: Long) {
        val current = db.rawQuery("SELECT revision FROM archive_state WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
        require(revision in 0..current)
        db.execSQL("UPDATE archive_state SET uploaded_revision=?,uploaded_at=? WHERE id=1 AND uploaded_revision<=?",
            arrayOf(revision, confirmedAt, revision))
    }

    @Synchronized internal fun resetCloudAccount(context: Context) {
        initialize(context)
        val db = db(context)
        db.beginTransaction()
        try {
            db.delete("archive_uploads", null, null)
            db.execSQL("UPDATE archive_attachments SET state=CASE WHEN path!='' THEN 'SAVED' ELSE state END,drive_id=''")
            db.execSQL("UPDATE archive_state SET uploaded_revision=-1,uploaded_at=0 WHERE id=1")
            dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun visitMessages(context: Context, chatId: String, from: Long? = null, until: Long? = null, visitor: (ArchiveMessage) -> Unit) {
        initialize(context)
        require(chats(context).any { it.id == chatId })
        val range = if (from != null && until != null) " AND message_at>=? AND message_at<?" else ""
        val args = if (range.isNotEmpty()) arrayOf(chatId, from.toString(), until.toString()) else arrayOf(chatId)
        // Load attachment metadata once for this streamed range. Reopening a cursor
        // for every message held up capture while generating large old histories.
        val attachmentRange = if (range.isNotEmpty()) " AND m.message_at>=? AND m.message_at<?" else ""
        val attachmentMap = db(context).rawQuery("SELECT a.id,a.message_id,a.name,a.mime,a.uri,a.path,a.bytes,a.sha256,a.state,a.drive_id " +
            "FROM archive_attachments a JOIN archive_messages m ON m.id=a.message_id WHERE m.chat_id=?$attachmentRange", args).use { c ->
            val result = mutableMapOf<String, MutableList<ArchiveAttachment>>()
            while (c.moveToNext()) {
                val attachment = ArchiveAttachment(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getLong(6), c.getString(7), c.getString(8), c.getString(9))
                result.getOrPut(attachment.messageId) { mutableListOf() }.add(attachment)
            }
            result
        }
        db(context).rawQuery("SELECT id,chat_id,source,source_id,message_at,observed_at,direction,sender,body FROM archive_messages " +
            "WHERE chat_id=?$range AND NOT EXISTS(SELECT 1 FROM archive_receipt_links WHERE receipt_id=archive_messages.id) ORDER BY message_at,id", args).use { c ->
            while (c.moveToNext()) visitor(ArchiveMessage(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getLong(5), c.getString(6), c.getString(7), c.getString(8), attachmentMap[c.getString(0)].orEmpty()))
        }
    }

    @Synchronized internal fun messageMonths(context: Context, chatId: String): List<String> = db(context).rawQuery(
        "SELECT DISTINCT CASE WHEN message_at>0 THEN strftime('%Y-%m',message_at/1000,'unixepoch') ELSE 'undated' END " +
            "FROM archive_messages WHERE chat_id=? ORDER BY 1", arrayOf(chatId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Synchronized fun messages(context: Context, chatId: String, search: String = "", limit: Int = 100, offset: Int = 0): List<ArchiveMessage> {
        initialize(context)
        require(chats(context).any { it.id == chatId }); require(limit in 1..500 && offset >= 0)
        val result = mutableListOf<ArchiveMessage>()
        val pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        db(context).rawQuery("SELECT id,chat_id,source,source_id,message_at,observed_at,direction,sender,body FROM archive_messages " +
            "WHERE chat_id=? AND body LIKE ? ESCAPE '\\' AND NOT EXISTS(SELECT 1 FROM archive_receipt_links WHERE receipt_id=archive_messages.id) ORDER BY message_at DESC,id DESC LIMIT ? OFFSET ?", arrayOf(chatId, pattern, limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) result.add(ArchiveMessage(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getLong(5), c.getString(6), c.getString(7), c.getString(8), attachments(context, c.getString(0))))
        }
        return result
    }

    @Synchronized fun attachments(context: Context, messageId: String? = null): List<ArchiveAttachment> {
        initialize(context)
        val selection = if (messageId == null) "JOIN archive_messages m ON m.id=a.message_id JOIN archive_chats c ON c.id=m.chat_id WHERE c.sharing=1" else "WHERE message_id=?"
        val order = if (messageId == null) "ORDER BY m.message_at DESC,a.id" else "ORDER BY a.id"
        return db(context).rawQuery("SELECT a.id,message_id,a.name,mime,uri,path,bytes,sha256,state,drive_id FROM archive_attachments a $selection $order",
            messageId?.let { arrayOf(it) }).use { c -> buildList { while (c.moveToNext()) add(ArchiveAttachment(c.getString(0), c.getString(1),
                c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6), c.getString(7), c.getString(8), c.getString(9))) } }
    }

    internal fun putMessage(db: SQLiteDatabase, chatId: String, source: String, sourceId: String, at: Long, direction: String,
        sender: String, body: String, sentAt: Long = 0, observedAt: Long = System.currentTimeMillis()): String {
        val id = hash("$chatId\u0000$source\u0000$sourceId")
        val values = ContentValues().apply {
            put("id", id); put("chat_id", chatId); put("source", source); put("source_id", sourceId); put("message_at", at)
            put("observed_at", observedAt); put("direction", direction); put("sender", sender); put("body", body); put("sent_at", sentAt)
        }
        if (db.insertWithOnConflict("archive_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) dirty(db)
        else {
            val changed = db.rawQuery("SELECT body,direction,message_at FROM archive_messages WHERE id=?", arrayOf(id)).use {
                it.moveToFirst(); it.getString(0) != body || it.getString(1) != direction || it.getLong(2) != at
            }
            if (changed) { values.remove("observed_at"); db.update("archive_messages", values, "id=?", arrayOf(id)); dirty(db) }
        }
        return id
    }

    @Synchronized fun receiveSms(context: Context, sender: String, body: String, sentAt: Long, receiptKey: String) {
        initialize(context)
        val matched = chats(context).filter { it.kind == "INDIVIDUAL" && it.saving && it.individualScope().matchesAddress(sender) }
        val db = db(context)
        db.beginTransaction()
        try {
            matched.forEach { putMessage(db, it.id, "SMS_RECEIPT", receiptKey, System.currentTimeMillis(), "INCOMING", sender, body, sentAt) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (matched.isNotEmpty()) {
            ArchiveWork.request(context)
            if (matched.any { it.sharing }) DriveArchive.requestSync(context)
        }
    }

    fun reconcile(context: Context) {
        initialize(context)
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("SMS permission required")
        val threads = ArchiveThreads.read(context)
        var failure: Exception? = null
        for (chat in chats(context).filter { it.saving }) {
            try {
                syncSms(context, chat)
                val selectedThreads = if (chat.kind == "GROUP") threads.filter { it.id == chat.threadId && it.participants.size > 1 } else
                    threads.filter { it.participants.size == 1 && chat.individualScope().matchesAddress(it.participants.single()) }
                for (thread in selectedThreads) syncMms(context, chat, thread.id)
                db(context).update("archive_chats", ContentValues().apply { put("last_check", System.currentTimeMillis()) }, "id=?", arrayOf(chat.id))
            } catch (error: Exception) { failure = error }
        }
        copyPendingAttachments(context)
        failure?.let { throw it }
    }

    private fun syncSms(context: Context, chat: ArchiveChat) {
        val scope = if (chat.kind == "INDIVIDUAL") chat.individualScope() else null
        val selection = scope?.querySelection ?: "thread_id=?"
        val args = scope?.selectionArgs ?: arrayOf(chat.threadId.toString())
        val db = db(context)
        val revisionBefore = revision(db)
        db.beginTransaction()
        try {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "date_sent", "type"),
                selection, args, "date ASC, _id ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw java.io.IOException("Cancelled")
                    if (scope != null) require(scope.matchesAddress(c.getString(1).orEmpty()))
                    val body = c.getString(2).orEmpty(); val at = c.getLong(3); val sentAt = c.getLong(4); val type = c.getInt(5)
                    val id = putMessage(db, chat.id, "ANDROID_SMS_STORE", c.getLong(0).toString(), at,
                        if (type == 1) "INCOMING" else if (type == 2) "OUTGOING_SENT" else "UNSENT_OR_OTHER", c.getString(1).orEmpty(), body, sentAt)
                    if (type == 1) {
                        // A receipt and a provider row are two observations, not two messages. Match one receipt only.
                        val receipt = db.rawQuery("SELECT id FROM archive_messages WHERE chat_id=? AND source='SMS_RECEIPT' " +
                            "AND body=? AND NOT EXISTS(SELECT 1 FROM archive_receipt_links WHERE receipt_id=archive_messages.id) " +
                            "AND (ABS(sent_at-?)<=1000 OR ABS(message_at-?)<=5000) ORDER BY ABS(message_at-?) LIMIT 1",
                            arrayOf(chat.id, body, sentAt.toString(), at.toString(), at.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
                        if (receipt != null && receipt != id) {
                            if (db.insertWithOnConflict("archive_receipt_links", null, ContentValues().apply { put("receipt_id", receipt); put("provider_id", id) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L) dirty(db)
                        }
                    }
                }
            } ?: throw java.io.IOException("SMS store unavailable")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (chat.sharing && revision(db) != revisionBefore) DriveArchive.requestSync(context)
    }

    private fun revision(db: SQLiteDatabase): Long = db.rawQuery("SELECT revision FROM archive_state WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }

    private data class MmsRow(val id: Long, val at: Long, val direction: String, val subject: String)

    private fun syncMms(context: Context, chat: ArchiveChat, threadId: Long) {
        val resolver = context.contentResolver
        val db = db(context)
        val existing: Map<Long, Pair<Long, String>> = db.rawQuery("SELECT source_id,message_at,direction FROM archive_messages m WHERE chat_id=? " +
            "AND source='ANDROID_MMS_STORE' AND (body!='' OR EXISTS(SELECT 1 FROM archive_attachments WHERE message_id=m.id))",
            arrayOf(chat.id)).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1) to c.getString(2)) } }
        val recent = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        val rows: List<MmsRow> = resolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box", "sub", "thread_id"),
            "thread_id=? AND m_type IN (128,132)", arrayOf(threadId.toString()), "date DESC, _id DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow("_id"); val date = c.getColumnIndexOrThrow("date")
            val box = c.getColumnIndexOrThrow("msg_box"); val subject = c.getColumnIndexOrThrow("sub")
            val thread = c.getColumnIndexOrThrow("thread_id")
            buildList<MmsRow> {
                while (c.moveToNext()) {
                    require(c.getLong(thread) == threadId) { "Message store returned an unrelated conversation" }
                    val at = c.getLong(date) * 1000
                    val direction = when (c.getInt(box)) { 1 -> "INCOMING"; 2 -> "OUTGOING_SENT"; else -> "UNSENT_OR_OTHER" }
                    val providerId = c.getLong(id)
                    if (at >= recent || existing[providerId] != (at to direction))
                        add(MmsRow(providerId, at, direction, c.getString(subject).orEmpty()))
                }
            }
        } ?: throw java.io.IOException("MMS store unavailable")
        // Read only selected message parts, in bounded batches. New messages are saved
        // first; old history no longer requires a separate provider round trip per text.
        for (batch in rows.chunked(100)) {
            if (Thread.currentThread().isInterrupted) throw java.io.IOException("Cancelled")
            val selectedIds = batch.map { it.id }.toSet()
            val text = batch.associate { it.id to mutableListOf<String>().apply { if (it.subject.isNotBlank()) add(it.subject) } }
            val parts = batch.associate { it.id to mutableListOf<ContentValues>() }
            resolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "name", "cl", "mid"),
                "mid IN (${batch.joinToString(",") { "?" }})", batch.map { it.id.toString() }.toTypedArray(), "mid,_id ASC")?.use { p ->
                val mid = p.getColumnIndexOrThrow("mid"); val id = p.getColumnIndexOrThrow("_id")
                val contentType = p.getColumnIndexOrThrow("ct"); val bodyColumn = p.getColumnIndexOrThrow("text")
                val name = p.getColumnIndexOrThrow("name"); val location = p.getColumnIndexOrThrow("cl")
                while (p.moveToNext()) {
                    val providerId = p.getLong(mid)
                    require(providerId in selectedIds) { "MMS content belongs to a different message" }
                    val partId = p.getLong(id); val mime = p.getString(contentType).orEmpty()
                    val uri = "content://mms/part/$partId"
                    if (mime == "text/plain") {
                        val body = p.getString(bodyColumn) ?: resolver.openInputStream(Uri.parse(uri))?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        if (body.isNotEmpty()) text.getValue(providerId).add(body)
                    } else if (mime != "application/smil") parts.getValue(providerId).add(ContentValues().apply {
                        put("id", hash("${chat.id}:mms:$providerId:part:$partId"))
                        put("name", (p.getString(name) ?: p.getString(location) ?: "attachment-$partId").substringAfterLast('/').take(180))
                        put("mime", mime.ifBlank { "application/octet-stream" }); put("uri", uri)
                    })
                }
            } ?: throw java.io.IOException("MMS parts unavailable")
            val senders = batch.associate { row -> row.id to if (row.direction == "OUTGOING_SENT") "Self" else if (chat.kind == "INDIVIDUAL") chat.number else
                resolver.query(Uri.parse("content://mms/${row.id}/addr"), arrayOf("address"), "type=137", null, null)?.use {
                    if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("address")).orEmpty() else ""
                }.orEmpty() }
            val revisionBefore = revision(db)
            db.beginTransaction()
            try {
                for (row in batch) {
                    val id = putMessage(db, chat.id, "ANDROID_MMS_STORE", row.id.toString(), row.at, row.direction,
                        senders.getValue(row.id), text.getValue(row.id).joinToString("\n"))
                    for (values in parts.getValue(row.id)) {
                        values.put("message_id", id)
                        if (db.insertWithOnConflict("archive_attachments", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) dirty(db)
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (chat.sharing && revision(db) != revisionBefore) DriveArchive.requestSync(context)
        }
    }

    private fun copyPendingAttachments(context: Context) {
        val db = db(context)
        val ids = db.rawQuery("SELECT a.id,a.uri,c.sharing FROM archive_attachments a JOIN archive_messages m ON m.id=a.message_id " +
            "JOIN archive_chats c ON c.id=m.chat_id WHERE a.state IN ('PENDING','UNAVAILABLE') ORDER BY m.message_at DESC,a.id", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2) == 1)) }
        }
        var sharedAttachmentChanged = false
        val folder = File(context.filesDir, "archive/attachments").apply { mkdirs() }
        for ((id, uri, sharing) in ids) {
            val target = File(folder, id); val atomic = AtomicFile(target)
            try {
                val stream = atomic.startWrite()
                try {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input -> input.copyTo(stream) }
                        ?: throw java.io.IOException("Attachment not downloaded")
                    atomic.finishWrite(stream)
                } catch (e: Exception) { atomic.failWrite(stream); throw e }
                val digest = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                val changed = updateAttachmentState(db, id, "SAVED", "archive/attachments/$id", target.length(),
                    digest.digest().joinToString("") { "%02x".format(it) })
                if (changed && sharing) sharedAttachmentChanged = true
            } catch (error: java.io.IOException) {
                if (updateAttachmentState(db, id, "UNAVAILABLE") && sharing) sharedAttachmentChanged = true
            }
        }
        // A delayed MMS download can change only the attachment, not its message.
        // Queue that durable revision too, without restarting uploads on unchanged retries.
        if (sharedAttachmentChanged) DriveArchive.requestSync(context)
    }

    internal fun updateAttachmentState(db: SQLiteDatabase, id: String, state: String, path: String? = null,
        bytes: Long? = null, sha256: String? = null): Boolean {
        db.beginTransaction()
        try {
            val before = db.rawQuery("SELECT state,path,bytes,sha256 FROM archive_attachments WHERE id=?", arrayOf(id)).use { c ->
                require(c.moveToFirst()) { "Unknown archive attachment" }
                listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getString(3))
            }
            val changed = state != before[0] || (path != null && path != before[1]) ||
                (bytes != null && bytes.toString() != before[2]) || (sha256 != null && sha256 != before[3])
            if (changed) {
                db.update("archive_attachments", ContentValues().apply {
                    put("state", state); path?.let { put("path", it) }; bytes?.let { put("bytes", it) }; sha256?.let { put("sha256", it) }
                }, "id=?", arrayOf(id))
                dirty(db)
            }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    @Synchronized internal fun uploadedAttachment(context: Context, id: String, driveId: String) {
        val db = db(context); db.beginTransaction()
        try {
            db.update("archive_attachments", ContentValues().apply { put("state", "UPLOADED"); put("drive_id", driveId) }, "id=?", arrayOf(id))
            dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun file(context: Context, relative: String): File {
        val root = context.filesDir.canonicalFile; val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && relative.startsWith("archive/")); return file
    }
}
