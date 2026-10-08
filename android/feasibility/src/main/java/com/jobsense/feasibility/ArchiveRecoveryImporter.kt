package com.jobsense.feasibility

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONObject
import java.io.InputStream
import java.io.File

/** One-time selected-history recovery; multiple conversations may be imported in one bundle. */
object ArchiveRecoveryImporter {
    fun import(context: Context, input: InputStream): Int {
        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) { val n = input.read(chunk); if (n < 0) break; require(buffer.size() + n <= 16 * 1024 * 1024) { "Recovery bundle exceeds 16 MB; split it into selected-conversation bundles" }; buffer.write(chunk, 0, n) }
        val bytes = buffer.toByteArray()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val bundle = JSONObject(text)
        require(bundle.getInt("schemaVersion") == 1)
        ArchiveStore.initialize(context)
        val known = ArchiveStore.chats(context).associateBy { it.id }
        val conversations = bundle.getJSONArray("conversations")
        val records = mutableListOf<Pair<ArchiveChat, JSONObject>>()
        val seen = mutableSetOf<String>()
        val gaps = mutableMapOf<String, String>()
        for (i in 0 until conversations.length()) {
            val item = conversations.getJSONObject(i)
            val chat = requireNotNull(known[item.getString("chatId")]) { "Choose this conversation in JobSense first" }
            if (chat.kind == "INDIVIDUAL") require(item.getString("contactNumber") == chat.number) { "Recovery contact does not match selected chat" }
            val messages = item.getJSONArray("messages")
            gaps[chat.id] = item.optString("coverageNote", "Recovered UI evidence; oldest boundary and completeness are unverified").take(1000)
            for (j in 0 until messages.length()) {
                val record = messages.getJSONObject(j)
                val id = record.getString("id")
                require(id.isNotBlank() && id.length <= 256 && seen.add("${chat.id}:$id"))
                require(record.getString("body").length <= 1024 * 1024)
                require(record.getString("direction") in setOf("INCOMING", "OUTGOING_SENT", "UNKNOWN", "UNSENT_OR_OTHER"))
                val at = record.optLong("atMillis", 0)
                require(at >= 0 && at <= System.currentTimeMillis() + 5 * 60 * 1000)
                if (at != 0L) require(record.optBoolean("timestampVerified", false)) { "Unverified timestamps must be omitted" }
                records.add(chat to record)
            }
        }
        require(records.isNotEmpty())
        val digest = ArchiveStore.hash(bytes)
        val folder = File(context.filesDir, "archive/recovery-evidence").apply { mkdirs() }
        val file = AtomicFile(File(folder, "$digest.json")); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
        val db = ConversationLedger.archiveDatabase(context)
        db.beginTransaction()
        try {
            for ((chat, record) in records) {
                val id = ArchiveStore.putMessage(db, chat.id, "RECOVERED_UI", record.getString("id"), record.optLong("atMillis", 0),
                    record.getString("direction"), record.optString("sender", if (chat.kind == "INDIVIDUAL") chat.number else ""), record.getString("body"))
                record.optJSONArray("attachments")?.let { attachments ->
                    for (k in 0 until attachments.length()) {
                        val attachment = attachments.getJSONObject(k)
                        val aid = ArchiveStore.hash("$id:recovered:${attachment.getString("id")}")
                        val relative = attachment.optString("localPath")
                        val source = if (relative.isNotBlank()) {
                            require(relative.startsWith("archive/recovery-files/")); ArchiveStore.file(context, relative)
                        } else null
                        val saved = source?.isFile == true
                        val target = File(context.filesDir, "archive/attachments/$aid").apply { parentFile!!.mkdirs() }
                        if (saved) {
                            require(DriveHttp.digest(requireNotNull(source), "SHA-256") == attachment.getString("sha256"))
                            val atomic = AtomicFile(target); val output = atomic.startWrite()
                            try { source.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
                            catch (error: Exception) { atomic.failWrite(output); throw error }
                        }
                        db.insertWithOnConflict("archive_attachments", null, ContentValues().apply {
                            put("id", aid); put("message_id", id); put("name", attachment.getString("name").substringAfterLast('/').take(180))
                            put("mime", attachment.optString("mime", "application/octet-stream")); put("uri", "recovered:$aid")
                            put("state", if (saved) "SAVED" else "UNAVAILABLE"); put("path", if (saved) "archive/attachments/$aid" else "")
                            put("bytes", if (saved) target.length() else 0); put("sha256", if (saved) attachment.getString("sha256") else "")
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            for ((chatId, note) in gaps) db.update("archive_chats", ContentValues().apply { put("coverage", note) }, "id=?", arrayOf(chatId))
            db.insertWithOnConflict("archive_recoveries", null, ContentValues().apply { put("sha256", digest); put("imported_at", System.currentTimeMillis()); put("path", "archive/recovery-evidence/$digest.json") }, SQLiteDatabase.CONFLICT_IGNORE)
            ArchiveStore.dirty(db); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ArchiveWork.request(context); DriveArchive.requestSync(context)
        return records.size
    }
}
