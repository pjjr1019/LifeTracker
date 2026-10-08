package com.jobsense.feasibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** User grants access to exactly one Drive document through Android's file picker. */
object AutoChatDocument {
    private const val PERIODIC = "chosen-chat-document-periodic"
    private const val IMMEDIATE = "chosen-chat-document-update"

    fun enabled(context: Context) = DiagnosticStore.preferences(context).getBoolean("autoDocumentEnabled", false)

    @Synchronized fun connect(context: Context, uri: Uri, flags: Int, scopes: List<HistoryScope>) {
        require(scopes.isNotEmpty() && uri.scheme == "content")
        val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
        require(provider?.packageName == "com.google.android.apps.docs") { "Choose Google Drive in the file picker" }
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(access and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "The document does not allow updates" }
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            require(it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0)
        } ?: throw IllegalArgumentException("The Drive document could not be checked")
        context.contentResolver.takePersistableUriPermission(uri, access)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
        val chosen = JSONArray()
        scopes.distinctBy { it.key }.forEach { chosen.put(JSONObject().put("name", it.name).put("number", it.number)
            .put("rawNumber", it.rawNumber).put("country", it.country)) }
        DiagnosticStore.preferences(context).edit().putString("autoDocumentUri", uri.toString())
            .putString("autoDocumentScopes", chosen.toString()).putString("autoDocumentGeneration", UUID.randomUUID().toString())
            .putBoolean("autoDocumentEnabled", true).putString("autoDocumentStatus", "Waiting for first file update").commit()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoChatDocumentWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        requestUpdate(context)
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestUpdate(context: Context) {
        if (enabled(context)) WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoChatDocumentWorker>().setConstraints(network()).build())
    }

    @Synchronized fun pause(context: Context) {
        DiagnosticStore.preferences(context).edit().putBoolean("autoDocumentEnabled", false)
            .putString("autoDocumentGeneration", UUID.randomUUID().toString())
            .putString("autoDocumentStatus", "Updates paused; the existing Drive file remains").commit()
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
    }

    fun status(context: Context): String {
        val preferences = DiagnosticStore.preferences(context)
        val at = preferences.getLong("autoDocumentLastWrite", 0)
        return preferences.getString("autoDocumentStatus", "Not connected to a Drive file").orEmpty() +
            "\nLast completed file write: " + if (at == 0L) "none" else
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
    }

    fun resume(context: Context) {
        val preferences = DiagnosticStore.preferences(context)
        val uri = preferences.getString("autoDocumentUri", null) ?: return
        val saved = JSONArray(preferences.getString("autoDocumentScopes", "[]"))
        val scopes = (0 until saved.length()).map { saved.getJSONObject(it).let { entry ->
            HistoryScope(entry.getString("name"), entry.getString("number"), entry.getString("rawNumber"), entry.getString("country"))
        } }
        try { connect(context, Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, scopes) }
        catch (error: Exception) {
            preferences.edit().putString("autoDocumentStatus", "Reconnect the Drive file to resume").apply()
        }
    }

    fun update(context: Context): Boolean {
        if (HistoryConnection.mode(context) != HistoryConnection.DRIVE) return true
        if (!enabled(context)) return true
        val preferences = DiagnosticStore.preferences(context)
        val config = synchronized(this) { preferences.all }
        if (config["autoDocumentEnabled"] != true) return true
        val generation = config["autoDocumentGeneration"] as String
        val saved = JSONArray(config["autoDocumentScopes"] as String)
        val scopes = (0 until saved.length()).map { index -> saved.getJSONObject(index).let {
            HistoryScope(it.getString("name"), it.getString("number"), it.getString("rawNumber"), it.getString("country"))
        } }
        val file = ChatDocument.create(context, scopes, automatic = true)
        if (!enabled(context) || preferences.getString("autoDocumentGeneration", "") != generation) return true
        val uri = Uri.parse(config["autoDocumentUri"] as String)
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw java.io.IOException("Drive did not open the document for writing")
        if (preferences.getString("autoDocumentGeneration", "") == generation) {
            preferences.edit().putLong("autoDocumentLastWrite", System.currentTimeMillis())
                .putString("autoDocumentStatus", "History file written; Google Drive handles online sync").apply()
            DiagnosticStore.record(context, "CHOSEN_CHAT_DOCUMENT_UPDATED")
        }
        return true
    }
}

class AutoChatDocumentWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        AutoChatDocument.update(applicationContext)
        Result.success()
    } catch (error: Exception) {
        DiagnosticStore.preferences(applicationContext).edit().putString("autoDocumentStatus",
            "File update failed; check Drive connection or reconnect if the file was moved/deleted").apply()
        DiagnosticStore.record(applicationContext, "CHAT_DOCUMENT_UPDATE_FAILED")
        if (AutoChatDocument.enabled(applicationContext)) Result.retry() else Result.success()
    }
}
