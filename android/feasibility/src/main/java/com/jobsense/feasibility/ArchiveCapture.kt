package com.jobsense.feasibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Passive capture only: Google Messages owns inbox writes and delivery notifications. */
class ArchiveSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val sender = parts.first().originatingAddress.orEmpty()
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val sentAt = parts.first().timestampMillis
        val bytes = (intent.extras?.get("pdus") as? Array<*>)?.filterIsInstance<ByteArray>()?.fold(ByteArray(0)) { all, part -> all + part }
            ?: return
        val pending = goAsync()
        executor.execute {
            try {
                ArchiveStore.receiveSms(context.applicationContext, sender, body, sentAt, ArchiveStore.hash(bytes))
            } catch (error: Exception) {
                DiagnosticStore.record(context, "ARCHIVE_SMS_CAPTURE_FAILED")
            } finally { pending.finish() }
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}

class ArchiveMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) ArchiveWork.request(context)
    }
}

object ArchiveObservers {
    private var observer: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    @Synchronized fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val reconcile = Runnable { ArchiveWork.request(app) }
        val next = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(reconcile); handler.postDelayed(reconcile, 800) }
        }
        try {
            app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, next)
            app.contentResolver.registerContentObserver(Uri.parse("content://mms"), true, next)
            app.contentResolver.registerContentObserver(Uri.parse("content://mms-sms"), true, next)
            observer = next
        } catch (error: SecurityException) { app.contentResolver.unregisterContentObserver(next) }
    }
}

object ArchiveWork {
    private const val CHECK = "archive-message-check"
    private const val PERIODIC = "archive-message-periodic"
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ArchiveReconcileWorker>(15, TimeUnit.MINUTES).build())
        request(context)
    }
    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ArchiveReconcileWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
}

class ArchiveReconcileWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        ArchiveStore.reconcile(applicationContext)
        DiagnosticStore.preferences(applicationContext).edit().remove("archiveCaptureError").apply()
        ArchiveDocuments.create(applicationContext)
        // Changed shared imports queue Drive themselves. An unchanged history check
        // must not keep replacing an upload that is already making progress.
        AutoChatDocument.requestUpdate(applicationContext)
        WirelessSharing.requestSync(applicationContext)
        Result.success()
    } catch (error: SecurityException) {
        DiagnosticStore.preferences(applicationContext).edit().putString("archiveCaptureError", "Allow SMS access to keep saving messages").apply()
        Result.success()
    } catch (error: Exception) {
        DiagnosticStore.preferences(applicationContext).edit().putString("archiveCaptureError", "History check failed; JobSense will retry").apply()
        DiagnosticStore.record(applicationContext, "ARCHIVE_RECONCILE_FAILED")
        Result.retry()
    }
}
