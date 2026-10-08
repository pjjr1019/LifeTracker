package com.jobsense.feasibility

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object ConversationSaving {
    private const val PERIODIC = "selected-conversation-periodic"
    private const val IMMEDIATE = "selected-conversation-check"

    fun scopes(context: Context): List<HistoryScope> {
        val preferences = DiagnosticStore.preferences(context)
        if (preferences.contains("autoConversations")) {
            val saved = JSONObject(preferences.getString("autoConversations", "{}").orEmpty())
            return saved.keys().asSequence().map { key ->
                val entry = saved.getJSONObject(key)
                HistoryScope(entry.getString("name"), entry.getString("number"), entry.getString("rawNumber"), entry.getString("country"))
                    .also { require(it.key == key) }
            }.toList()
        }
        if (!preferences.getBoolean("autoConversationSaving", false)) return emptyList()
        val number = preferences.getString("autoNumber", "").orEmpty()
        if (TestContact.normalizedNumber(number) == null) return emptyList()
        return listOf(HistoryScope(preferences.getString("autoName", "").orEmpty(), number,
            preferences.getString("autoRawNumber", number).orEmpty(), preferences.getString("autoCountry", "").orEmpty()))
    }

    fun isEnabled(context: Context, scope: HistoryScope) = scopes(context).any { it.key == scope.key }

    @Synchronized private fun saveScopes(context: Context, scopes: List<HistoryScope>) {
        val saved = JSONObject()
        scopes.forEach { scope -> saved.put(scope.key, JSONObject().put("name", scope.name).put("number", scope.number)
            .put("rawNumber", scope.rawNumber).put("country", scope.country)) }
        DiagnosticStore.preferences(context).edit().putString("autoConversations", saved.toString())
            .putBoolean("autoSavingChoiceMade", true).apply()
    }

    @Synchronized fun start(context: Context, scope: HistoryScope) {
        saveScopes(context, scopes(context).filterNot { it.key == scope.key } + scope)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ConversationSavingWorker>(15, TimeUnit.MINUTES).build())
        requestCheck(context)
        DiagnosticStore.record(context, "CONVERSATION_AUTOMATIC_SAVING_ENABLED")
        MessageNotificationListener.refreshTestNotification(context)
        ArchiveStore.initialize(context)
        if (ArchiveStore.chats(context).none { it.id == scope.key }) {
            // Import metadata without re-entering the legacy saving API.
            ArchiveStore.registerLegacyContact(context, scope)
        }
        ArchiveStore.configure(context, scope.key, saving = true)
    }

    @Synchronized fun stop(context: Context, selected: HistoryScope? = null) {
        val stopped = scopes(context).filter { selected == null || it.key == selected.key }
        ArchiveStore.initialize(context)
        val remaining = if (selected == null) emptyList() else scopes(context).filterNot { it.key == selected.key }
        saveScopes(context, remaining)
        if (remaining.isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
        }
        DiagnosticStore.record(context, "CONVERSATION_AUTOMATIC_SAVING_DISABLED")
        stopped.forEach { ArchiveStore.configure(context, it.key, saving = false) }
    }

    fun requestCheck(context: Context) {
        if (scopes(context).isNotEmpty()) WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ConversationSavingWorker>().build())
    }
}

class ConversationSavingWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        var retry = false
        for (scope in ConversationSaving.scopes(applicationContext)) {
            try {
                ConversationLedger.syncSms(applicationContext, scope, automatic = true)
            } catch (error: SecurityException) {
                DiagnosticStore.record(applicationContext, "CONVERSATION_SMS_PERMISSION_MISSING")
            } catch (error: Exception) {
                if (ConversationSaving.isEnabled(applicationContext, scope)) {
                    DiagnosticStore.record(applicationContext, "CONVERSATION_SAVE_FAILED")
                    retry = true
                }
            }
        }
        WirelessSharing.requestSync(applicationContext)
        AutoChatDocument.requestUpdate(applicationContext)
        return if (retry) Result.retry() else Result.success()
    }
}
