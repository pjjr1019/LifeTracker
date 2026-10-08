package com.jobsense.feasibility

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import android.util.JsonWriter
import org.json.JSONObject
import java.io.OutputStream

/** Durable diagnostic evidence, separate from the bounded device-event timeline. */
object ConversationLedger {
    internal class Database(context: Context, name: String = "conversation_evidence.db") : SQLiteOpenHelper(context, name, null, 3) {
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS archive_attachments_message ON archive_attachments(message_id)")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE evidence (scope TEXT NOT NULL, source TEXT NOT NULL, evidence_key TEXT NOT NULL, " +
                "message_at INTEGER NOT NULL, observed_at INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "PRIMARY KEY(scope, source, evidence_key))")
            ArchiveSchema.create(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1..2 && newVersion == 3)
            ArchiveSchema.create(db)
            if (oldVersion == 2) {
                // Retain the first development import as unverified evidence; rebuild
                // its projection only after participant and thread IDs are checked.
                db.execSQL("INSERT INTO archive_unverified_messages SELECT * FROM archive_messages WHERE source='ANDROID_MMS_STORE'")
                db.execSQL("INSERT INTO archive_unverified_attachments SELECT a.* FROM archive_attachments a JOIN archive_messages m ON a.message_id=m.id WHERE m.source='ANDROID_MMS_STORE'")
                db.execSQL("DELETE FROM archive_attachments WHERE message_id IN (SELECT id FROM archive_messages WHERE source='ANDROID_MMS_STORE')")
                db.execSQL("DELETE FROM archive_messages WHERE source='ANDROID_MMS_STORE'")
                db.execSQL("UPDATE archive_state SET revision=revision+1,uploaded_revision=-1,uploaded_at=0 WHERE id=1")
                db.execSQL("UPDATE archive_chats SET last_check=0")
            }
        }
    }

    private var helper: Database? = null
    @Synchronized private fun database(context: Context): SQLiteDatabase {
        if (helper == null) helper = Database(context.applicationContext)
        return requireNotNull(helper).writableDatabase
    }

    internal fun archiveDatabase(context: Context) = database(context)

    internal fun insert(db: SQLiteDatabase, scope: HistoryScope, source: String, key: String, at: Long, payload: JSONObject): Boolean {
        val values = ContentValues().apply {
            put("scope", scope.key); put("source", source); put("evidence_key", key)
            put("message_at", at); put("observed_at", System.currentTimeMillis()); put("payload", payload.toString())
        }
        return db.insertWithOnConflict("evidence", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun saveNotification(context: Context, scope: HistoryScope, key: String, at: Long, payload: JSONObject) {
        if (insert(database(context), scope, "GOOGLE_MESSAGES_NOTIFICATION", key, at, payload)) {
            refreshSummary(context, scope)
            DiagnosticStore.record(context, "CONVERSATION_NOTIFICATION_MESSAGE_SAVED")
            WirelessSharing.requestSync(context)
            AutoChatDocument.requestUpdate(context)
        }
    }

    fun syncSms(context: Context, scope: HistoryScope, automatic: Boolean): Int {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("SMS access is not granted")
        }
        val db = database(context)
        var added = 0
        db.beginTransaction()
        try {
            val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.STATUS)
            val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection,
                scope.querySelection, scope.selectionArgs, "date ASC, _id ASC")
                ?: throw java.io.IOException("SMS provider returned no cursor")
            cursor.use {
                while (it.moveToNext()) {
                    if (Thread.currentThread().isInterrupted || (automatic && !ConversationSaving.isEnabled(context, scope))) {
                        throw java.io.IOException("Saving stopped")
                    }
                    val address = it.getString(2).orEmpty()
                    require(scope.matchesAddress(address))
                    val id = it.getLong(0)
                    val body = it.getString(3).orEmpty()
                    val date = it.getLong(4)
                    val type = it.getInt(6)
                    val payload = JSONObject().put("providerId", id).put("threadId", it.getLong(1))
                        .put("address", address).put("body", body).put("dateMillis", date)
                        .put("dateSentMillis", it.getLong(5)).put("providerType", type)
                        .put("deliveryStatusAtObservation", it.getInt(7))
                        .put("direction", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else
                            if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "OUTGOING_SENT" else "UNSENT_OR_OTHER")
                    val key = MessageEvidenceKey.from("ANDROID_SMS_STORE", id.toString(), date.toString(), body, type.toString())
                    if (insert(db, scope, "ANDROID_SMS_STORE", key, date, payload)) added++
                }
            }
            if (automatic && !ConversationSaving.isEnabled(context, scope)) throw java.io.IOException("Saving stopped")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refreshSummary(context, scope)
        DiagnosticStore.preferences(context).edit().putLong("ledgerLastSync:${scope.key}", System.currentTimeMillis()).apply()
        if (added > 0) DiagnosticStore.record(context, "CONVERSATION_SMS_MESSAGES_SAVED", JSONObject().put("newEvidenceCount", added))
        if (automatic) DiagnosticStore.record(context, "CONVERSATION_SMS_CHECKED", JSONObject().put("newEvidenceCount", added))
        return added
    }

    private fun refreshSummary(context: Context, scope: HistoryScope) {
        var sms = 0
        var notifications = 0
        database(context).rawQuery("SELECT source, COUNT(*) FROM evidence WHERE scope = ? GROUP BY source", arrayOf(scope.key)).use {
            while (it.moveToNext()) {
                if (it.getString(0) == "ANDROID_SMS_STORE") sms = it.getInt(1) else notifications += it.getInt(1)
            }
        }
        DiagnosticStore.preferences(context).edit().putInt("ledgerSms:${scope.key}", sms)
            .putInt("ledgerNotifications:${scope.key}", notifications).apply()
    }

    fun summary(context: Context, scope: HistoryScope): JSONObject {
        val preferences = DiagnosticStore.preferences(context)
        return JSONObject().put("automaticSaving", ConversationSaving.isEnabled(context, scope))
            .put("smsEvidenceCount", preferences.getInt("ledgerSms:${scope.key}", 0))
            .put("notificationEvidenceCount", preferences.getInt("ledgerNotifications:${scope.key}", 0))
            .put("lastSmsCheckMillis", preferences.getLong("ledgerLastSync:${scope.key}", 0))
    }

    fun export(context: Context, scope: HistoryScope, output: OutputStream) {
        writeEvidence(database(context), scope, output)
    }

    fun writeTranscript(context: Context, scope: HistoryScope, writer: java.io.Writer) {
        writeTranscriptEvidence(database(context), scope, writer)
    }

    internal fun writeTranscriptEvidence(db: SQLiteDatabase, scope: HistoryScope, writer: java.io.Writer) {
        var count = 0
        db.rawQuery("SELECT source, evidence_key, message_at, observed_at, payload FROM evidence " +
            "WHERE scope = ? ORDER BY message_at, source, evidence_key", arrayOf(scope.key)).use { cursor ->
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted) throw java.io.IOException("Export cancelled")
                val payload = JSONObject(cursor.getString(4))
                writer.write("Record " + (++count) + " | " + java.time.Instant.ofEpochMilli(cursor.getLong(2)).toString() +
                    " | " + payload.optString("direction", "UNKNOWN") + " | " + cursor.getString(0) + "\n")
                // JSON quoting preserves exact line breaks and separates message data from headings.
                writer.write("Message text: " + JSONObject.quote(payload.optString("body", "")) + "\n")
                writer.write("Original evidence: " + cursor.getString(4) + "\n")
                writer.write("Evidence ID: " + cursor.getString(1) + " | Observed: " +
                    java.time.Instant.ofEpochMilli(cursor.getLong(3)).toString() + "\n\n")
            }
        }
        writer.write("Saved evidence records in this conversation: " + count + "\n")
    }

    internal fun writeEvidence(db: SQLiteDatabase, scope: HistoryScope, output: OutputStream) {
        val writer = JsonWriter(output.writer(Charsets.UTF_8))
        writer.beginObject().name("scope").value("USER_SELECTED_CONTACT").name("contactNumber").value(scope.number)
            .name("coverage").value("Saved SMS plus partial notification evidence; RCS/MMS completeness is unverified")
            .name("evidence").beginArray()
        db.rawQuery("SELECT source, evidence_key, message_at, observed_at, payload FROM evidence " +
            "WHERE scope = ? ORDER BY message_at, source, evidence_key", arrayOf(scope.key)).use { cursor ->
            while (cursor.moveToNext()) {
                writer.beginObject().name("source").value(cursor.getString(0)).name("evidenceKey").value(cursor.getString(1))
                    .name("messageAtMillis").value(cursor.getLong(2)).name("observedAtMillis").value(cursor.getLong(3))
                    // Store the original structured evidence as JSON text without reinterpretation.
                    .name("payloadJson").value(cursor.getString(4)).endObject()
            }
        }
        writer.endArray().endObject(); writer.flush()
    }
}
