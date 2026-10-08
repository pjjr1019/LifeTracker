package com.jobsense.feasibility

import android.accounts.Account
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class DriveNeedsConsent : IOException("Google account permission needs attention")
class DriveHttpError(val code: Int) : IOException("Drive request failed ($code)")

/** Tokens are held in memory; Google Play services handles the account grant and token renewal. */
object DriveArchive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val WORK = "archive-drive-upload"
    private const val PERIODIC = "archive-drive-periodic"
    fun enabled(context: Context) = DiagnosticStore.preferences(context).getBoolean("driveArchiveEnabled", false)
    fun account(context: Context) = DiagnosticStore.preferences(context).getString("driveArchiveAccount", "").orEmpty()
    fun authorization(context: Context, selectAccount: Boolean = false): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (selectAccount) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else account(context).takeIf { it.isNotBlank() }?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }
    fun acceptToken(context: Context, token: String) {
        val email = DriveHttp(token).json("GET", "/about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress")
        val prefs = DiagnosticStore.preferences(context)
        synchronized(this) {
            if (account(context).isNotBlank() && account(context) != email) {
                ArchiveStore.resetCloudAccount(context)
                prefs.edit().remove("driveArchiveFolder").putBoolean("driveMigrationConfirmed", false)
                    .putBoolean("driveArchiveCloudVerified", false).commit()
            }
            prefs.edit().putString("driveArchiveAccount", email).putBoolean("driveArchiveEnabled", true)
                .putString("driveArchiveGeneration", UUID.randomUUID().toString()).remove("driveArchiveError").commit()
        }
        schedule(context)
    }
    private fun token(context: Context): String {
        if (account(context).isBlank()) throw DriveNeedsConsent()
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(authorization(context)), 30, TimeUnit.SECONDS)
        if (result.hasResolution() || result.accessToken.isNullOrBlank() || SCOPE !in result.grantedScopes) throw DriveNeedsConsent()
        return requireNotNull(result.accessToken)
    }
    private fun constraints(context: Context) = Constraints.Builder().setRequiredNetworkType(
        if (DiagnosticStore.preferences(context).getBoolean("archiveMobileData", true)) NetworkType.CONNECTED else NetworkType.UNMETERED).build()
    fun schedule(context: Context) {
        if (!enabled(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DriveArchiveWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(context)).build())
        requestSync(context)
    }
    fun requestSync(context: Context, continuePending: Boolean = false) {
        if (!enabled(context) || HistoryConnection.mode(context) != HistoryConnection.DRIVE) return
        // A newly detected change must not wait behind an older request's retry backoff.
        // Worker continuations append after the current pass; external changes replace it.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK,
            if (continuePending) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveArchiveWorker>().setConstraints(constraints(context))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
    fun pause(context: Context) {
        DiagnosticStore.preferences(context).edit().putBoolean("driveArchiveEnabled", false)
            .putString("driveArchiveGeneration", UUID.randomUUID().toString()).commit()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
    fun resume(context: Context) {
        if (account(context).isBlank()) return
        DiagnosticStore.preferences(context).edit().putBoolean("driveArchiveEnabled", true).commit()
        schedule(context)
    }
    internal fun fileId(context: Context, key: String): String = ConversationLedger.archiveDatabase(context)
        .rawQuery("SELECT drive_id FROM archive_uploads WHERE logical_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else "" }
    fun fileLink(context: Context, key: String) = fileId(context, key).let { if (it.isBlank()) "" else "https://drive.google.com/file/d/$it/view" }
    private fun saveUpload(context: Context, key: String, values: ContentValues) {
        val db = ConversationLedger.archiveDatabase(context)
        db.insertWithOnConflict("archive_uploads", null, ContentValues().apply { put("logical_key", key) }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        db.update("archive_uploads", values, "logical_key=?", arrayOf(key))
    }
    private fun reserve(context: Context, http: DriveHttp, key: String): String {
        fileId(context, key).takeIf { it.isNotBlank() }?.let { return it }
        val generated = http.json("GET", "/files/generateIds?count=1&space=drive&type=files").getJSONArray("ids").getString(0)
        saveUpload(context, key, ContentValues().apply { put("drive_id", generated) })
        return generated
    }
    @Synchronized fun sync(context: Context) {
        if (!enabled(context) || HistoryConnection.mode(context) != HistoryConnection.DRIVE) return
        val prefs = DiagnosticStore.preferences(context)
        prefs.edit().putLong("driveArchiveAttemptAt", System.currentTimeMillis()).putString("driveArchivePhase", "authorization").apply()
        val generation = prefs.getString("driveArchiveGeneration", "")
        fun checkActive() {
            if (!enabled(context) || HistoryConnection.mode(context) != HistoryConnection.DRIVE || generation != prefs.getString("driveArchiveGeneration", "") || Thread.currentThread().isInterrupted)
                throw IOException("Upload paused")
        }
        val http = DriveHttp(token(context), ::checkActive)
        val folderId = reserve(context, http, "folder")
        try { http.json("GET", "/files/$folderId?fields=id") }
        catch (error: DriveHttpError) {
            if (error.code != 404) throw error
            http.json("POST", "/files?fields=id", JSONObject().put("id", folderId).put("name", "JobSense")
                .put("mimeType", "application/vnd.google-apps.folder"))
        }
        prefs.edit().putString("driveArchiveFolder", folderId).apply()
        var uploadedRevision = -1L
        fun publish(): Long {
            val docs = ArchiveDocuments.snapshot(context)
            try {
                upload(context, http, folderId, "master", docs.master, "text/plain")
                for ((key, file) in docs.segments) upload(context, http, folderId, key, file, "text/plain")
                ArchiveDocuments.refreshSnapshotIndex(context, docs)
                upload(context, http, folderId, "index", docs.index, "text/plain")
                checkActive()
                ArchiveStore.acknowledge(context, docs.revision)
                return docs.revision
            } finally { ArchiveDocuments.releaseSnapshot(context, docs) }
        }
        // Publish text first so a large photo cannot hold up new work instructions.
        uploadedRevision = publish()
        val attachmentStarted = android.os.SystemClock.elapsedRealtime()
        // Bound each attachment pass so newly arriving messages get another text
        // publish instead of waiting behind an entire historical photo library.
        for (attachment in ArchiveStore.attachments(context).filter { it.state == "SAVED" }.take(3)) {
            checkActive()
            val file = ArchiveStore.file(context, attachment.path)
            val id = upload(context, http, folderId, "attachment:${attachment.id}", file, attachment.mime, attachment.name)
            ArchiveStore.uploadedAttachment(context, attachment.id, id)
            if (android.os.SystemClock.elapsedRealtime() - attachmentStarted >= 30_000) break
        }
        checkActive()
        uploadedRevision = publish()
        checkActive()
        prefs.edit().remove("driveArchiveError").remove("driveArchiveFailureCode")
            .putString("driveArchivePhase", "confirmed").putBoolean("driveArchiveCloudVerified", true).apply()
        if (ArchiveStore.status(context).revision > uploadedRevision || ArchiveStore.attachments(context).any { it.state == "SAVED" }) requestSync(context, continuePending = true)
    }

    private fun upload(context: Context, http: DriveHttp, folder: String, key: String, file: File, mime: String, name: String = file.name): String {
        DiagnosticStore.preferences(context).edit().putString("driveArchivePhase", key.substringBefore(':')).apply()
        val id = reserve(context, http, key)
        val digest = DriveHttp.digest(file, "SHA-256")
        val db = ConversationLedger.archiveDatabase(context)
        val previous = db.rawQuery("SELECT session,sha256,bytes FROM archive_uploads WHERE logical_key=?", arrayOf(key)).use {
            it.moveToFirst(); Triple(it.getString(0), it.getString(1), it.getLong(2))
        }
        if (previous.second == digest && previous.third == file.length() && previous.first.isBlank()) {
            try { http.verify(id, file); return id }
            catch (error: DriveHttpError) { if (error.code != 404) throw error }
        }
        var exists = true
        try { http.json("GET", "/files/$id?fields=id") } catch (error: DriveHttpError) { if (error.code != 404) throw error; exists = false }
        val metadata = JSONObject().put("name", name).put("mimeType", mime)
        if (!exists) metadata.put("id", id).put("parents", JSONArray().put(folder))
        var session = if (previous.second == digest && previous.third == file.length()) previous.first else ""
        if (session.isBlank()) {
            session = http.startUpload(id, exists, metadata, mime, file.length())
            saveUpload(context, key, ContentValues().apply { put("session", session); put("sha256", digest); put("bytes", file.length()) })
        }
        try { http.resumeUpload(session, file, mime) }
        catch (error: DriveHttpError) {
            if (error.code !in listOf(404, 410)) throw error
            saveUpload(context, key, ContentValues().apply { put("session", "") })
            throw IOException("Upload session expired; retry queued")
        }
        http.verify(id, file)
        saveUpload(context, key, ContentValues().apply { put("session", ""); put("sha256", digest); put("bytes", file.length()) })
        return id
    }

    /** Called only after the replacement source has passed an actual ChatGPT retrieval check. */
    fun confirmMigration(context: Context) {
        require(DiagnosticStore.preferences(context).getBoolean("driveArchiveCloudVerified", false))
        val prefs = DiagnosticStore.preferences(context)
        val oldUri = prefs.getString("autoDocumentUri", null)
        if (oldUri != null) {
            val docs = ArchiveDocuments.create(context)
            context.contentResolver.openOutputStream(Uri.parse(oldUri), "wt")?.use { stream ->
                stream.write(("Superseded JobSense archive. Fetch the current source: ${fileLink(context, "master")}\n\n").toByteArray(Charsets.UTF_8))
                docs.master.inputStream().use { it.copyTo(stream) }
            } ?: throw IOException("Old archive could not be marked")
        }
        AutoChatDocument.pause(context)
        prefs.edit().putBoolean("driveMigrationConfirmed", true).apply()
    }
}

class DriveArchiveWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try { DriveArchive.sync(applicationContext); Result.success() }
    catch (error: DriveNeedsConsent) {
        DiagnosticStore.preferences(applicationContext).edit().putString("driveArchiveError", "Reconnect Google Drive to resume uploads").apply()
        Result.success()
    } catch (error: Exception) {
        if (isStopped) Result.success() else {
        // Store only exception type/status, never private response bodies, tokens, or file URLs.
        val failure = generateSequence(error as Throwable) { it.cause }.take(4)
            .joinToString("/") { if (it is DriveHttpError) "HTTP_${it.code}" else it.javaClass.simpleName }
        DiagnosticStore.preferences(applicationContext).edit().putString("driveArchiveError",
            if (error is DriveHttpError && error.code == 403) "Drive could not accept the upload. Check account access and storage." else "Upload pending; JobSense will retry")
            .putString("driveArchiveFailureCode", failure).apply()
        Result.retry()
        }
    }
}

/** Bounded responses and resumable chunked uploads. No tokens, source text or response bodies in logs. */
internal class DriveHttp(private val token: String, private val checkActive: () -> Unit = {},
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun json(method: String, path: String, data: JSONObject? = null): JSONObject {
        val connection = connection("https://www.googleapis.com/drive/v3$path", method)
        try {
            if (data != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpError(code)
            return JSONObject(read(connection))
        } finally { connection.disconnect() }
    }
    fun startUpload(id: String, exists: Boolean, metadata: JSONObject, mime: String, length: Long): String {
        val connection = connection("https://www.googleapis.com/upload/drive/v3/files${if (exists) "/$id" else ""}?uploadType=resumable&fields=id",
            if (exists) "PATCH" else "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("X-Upload-Content-Type", mime); connection.setRequestProperty("X-Upload-Content-Length", length.toString())
            connection.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw DriveHttpError(connection.responseCode)
            return requireNotNull(connection.getHeaderField("Location")).also { validateUrl(it) }
        } finally { connection.disconnect() }
    }
    fun resumeUpload(session: String, file: File, mime: String) {
        validateUrl(session)
        var offset = 0L
        val status = connection(session, "PUT")
        try {
            status.doOutput = true; status.setFixedLengthStreamingMode(0)
            status.setRequestProperty("Content-Range", "bytes */${file.length()}"); status.outputStream.close()
            when (val code = status.responseCode) {
                in 200..299 -> return
                308 -> offset = status.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0
                else -> throw DriveHttpError(code)
            }
        } finally { status.disconnect() }
        require(offset in 0..file.length())
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            do {
                checkActive()
                val amount = minOf(1024 * 1024L, file.length() - offset).toInt()
                val bytes = ByteArray(amount); input.readFully(bytes)
                val upload = connection(session, "PUT")
                try {
                    upload.doOutput = true; upload.setFixedLengthStreamingMode(amount)
                    upload.setRequestProperty("Content-Type", mime)
                    upload.setRequestProperty("Content-Range", if (amount == 0) "bytes */0" else "bytes $offset-${offset + amount - 1}/${file.length()}")
                    upload.outputStream.use { it.write(bytes) }
                    val code = upload.responseCode
                    if (code in 200..299) return
                    if (code != 308) throw DriveHttpError(code)
                    val acknowledged = upload.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0
                    require(acknowledged > offset && acknowledged <= offset + amount)
                    offset = acknowledged; input.seek(offset)
                } finally { upload.disconnect() }
            } while (offset < file.length())
        }
    }
    fun verify(id: String, file: File) {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        val metadata = json("GET", "/files/$id?fields=id,size,md5Checksum,trashed")
        if (metadata.optBoolean("trashed") || metadata.getString("size").toLong() != file.length() || metadata.getString("md5Checksum") != digest(file, "MD5"))
            throw IOException("Drive checksum did not match")
    }
    private fun connection(url: String, method: String): HttpURLConnection {
        checkActive(); validateUrl(url)
        return openConnection(URL(url)).apply {
            requestMethod = method; instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
    }
    private fun read(connection: HttpURLConnection): String {
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size() + count > 1024 * 1024) throw IOException("Drive response too large"); output.write(buffer, 0, count) }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }
    companion object {
        internal fun validateUrl(value: String) {
            val uri = java.net.URI(value)
            require(uri.scheme == "https" && uri.host == "www.googleapis.com" && uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443))
        }
        internal fun digest(file: File, algorithm: String): String {
            val digest = java.security.MessageDigest.getInstance(algorithm)
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
