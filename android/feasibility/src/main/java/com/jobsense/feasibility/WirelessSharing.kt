package com.jobsense.feasibility

import android.content.Context
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.util.UUID
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Explicit, independent sharing whitelist; switching the viewed contact never expands it. */
object WirelessSharing {
    private const val PERIODIC = "chosen-chat-wireless-periodic"
    private const val IMMEDIATE = "chosen-chat-wireless-sync"
    private const val MAX_BYTES = 10 * 1024 * 1024

    fun enabled(context: Context) = DiagnosticStore.preferences(context).getBoolean("wirelessEnabled", false)

    @Synchronized fun configure(context: Context, address: String, token: String, scopes: List<HistoryScope>) {
        val endpoint = WirelessEndpoint.validate(address)
        require(token.length >= 32 && token.all { it.code in 33..126 }) { "Enter the service pairing code" }
        require(scopes.isNotEmpty()) { "Choose at least one conversation" }
        val chosen = JSONArray()
        scopes.distinctBy { it.key }.forEach { chosen.put(JSONObject().put("name", it.name).put("number", it.number)
            .put("rawNumber", it.rawNumber).put("country", it.country)) }
        DiagnosticStore.preferences(context).edit().putString("wirelessAddress", endpoint)
            .putString("wirelessToken", token).putString("wirelessScopes", chosen.toString())
            .putString("wirelessGeneration", UUID.randomUUID().toString()).putBoolean("wirelessEnabled", true).putString("wirelessStatus", "Waiting for connection").commit()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WirelessSharingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network()).build())
        requestSync(context)
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSync(context: Context) {
        if (enabled(context)) WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WirelessSharingWorker>().setConstraints(network()).build())
    }

    @Synchronized fun pause(context: Context) {
        DiagnosticStore.preferences(context).edit().putString("wirelessGeneration", UUID.randomUUID().toString()).putBoolean("wirelessEnabled", false)
            .putString("wirelessStatus", "Paused; previous online copy remains").commit()
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
    }

    fun status(context: Context): String {
        val preferences = DiagnosticStore.preferences(context)
        return preferences.getString("wirelessStatus", "Not connected").orEmpty() +
            "\nLast successful upload: " + preferences.getLong("wirelessLastSuccess", 0).let {
                if (it == 0L) "none" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            }
    }

    fun resume(context: Context) {
        val preferences = DiagnosticStore.preferences(context)
        val address = preferences.getString("wirelessAddress", null) ?: return
        val saved = JSONArray(preferences.getString("wirelessScopes", "[]"))
        val scopes = (0 until saved.length()).map { saved.getJSONObject(it).let { entry ->
            HistoryScope(entry.getString("name"), entry.getString("number"), entry.getString("rawNumber"), entry.getString("country"))
        } }
        try { configure(context, address, preferences.getString("wirelessToken", "").orEmpty(), scopes) }
        catch (error: Exception) { preferences.edit().putString("wirelessStatus", "Reconnect your laptop service to resume").apply() }
    }

    fun upload(context: Context): Boolean {
        if (HistoryConnection.mode(context) != HistoryConnection.LAPTOP) return true
        if (!enabled(context)) return true
        val preferences = DiagnosticStore.preferences(context)
        // Capture one configuration; edits/pause never mix endpoint, token or scope selections.
        val config = synchronized(this) { preferences.all }
        if (config["wirelessEnabled"] != true) return true
        val generation = config["wirelessGeneration"] as String
        val chosen = JSONArray(config["wirelessScopes"] as String)
        val chats = JSONArray()
        for (index in 0 until chosen.length()) {
            val entry = chosen.getJSONObject(index)
            val scope = HistoryScope(entry.getString("name"), entry.getString("number"),
                entry.getString("rawNumber"), entry.getString("country"))
            val output = ByteArrayOutputStream()
            val bounded = object : FilterOutputStream(output) {
                override fun write(value: Int) {
                    require(output.size() < MAX_BYTES); output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(output.size().toLong() + length <= MAX_BYTES); output.write(bytes, offset, length)
                }
            }
            ConversationLedger.export(context, scope, bounded)
            require(output.size() <= MAX_BYTES) { "History is too large for this feasibility connection" }
            chats.put(JSONObject().put("id", scope.key).put("name", scope.name).put("number", scope.number)
                .put("lastSmsCheckMillis", ConversationLedger.summary(context, scope).getLong("lastSmsCheckMillis"))
                .put("ledger", JSONObject(output.toString("UTF-8"))))
        }
        val data = JSONObject().put("scope", "USER_CHOSEN_CONVERSATIONS").put("capturedAtMillis", System.currentTimeMillis()).put("conversations", chats)
            .toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTES) { "History is too large for this feasibility connection" }
        val address = WirelessEndpoint.validate(config["wirelessAddress"] as String)
        val connection = URL(address).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + config["wirelessToken"] as String)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(data.size)
            if (!enabled(context) || preferences.getString("wirelessGeneration", "") != generation) return true
            connection.outputStream.use { it.write(data) }
            val success = connection.responseCode in 200..299
            if (preferences.getString("wirelessGeneration", "") != generation) return true
            preferences.edit().putString("wirelessStatus", if (success) "Chosen chats uploaded" else
                "Connection rejected (" + connection.responseCode + ")").apply()
            if (success) preferences.edit().putLong("wirelessLastSuccess", System.currentTimeMillis()).apply()
            return success
        } finally { connection.disconnect() }
    }
}

class WirelessSharingWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        if (WirelessSharing.upload(applicationContext)) Result.success() else Result.retry()
    } catch (error: Exception) {
        DiagnosticStore.preferences(applicationContext).edit()
            .putString("wirelessStatus", "Unable to connect; will retry when sharing is on").apply()
        if (WirelessSharing.enabled(applicationContext)) Result.retry() else Result.success()
    }
}
