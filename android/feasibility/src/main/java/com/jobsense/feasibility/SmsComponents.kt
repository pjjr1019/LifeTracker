package com.jobsense.feasibility

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val sender = parts.first().originatingAddress.orEmpty()
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val sentAt = parts.first().timestampMillis
        // A default SMS handler must preserve ALL incoming SMS in the system inbox,
        // even though only the explicit test sender enters our diagnostic storage.
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        }
        try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (error: RuntimeException) {
            DiagnosticStore.record(context, "SMS_INBOX_WRITE_FAILED")
        }
        val expected = DiagnosticStore.preferences(context).getString("testNumber", "").orEmpty()
        if (!TestContact.matchesNumber(expected, sender)) return
        DiagnosticStore.record(context, "SMS_CAPTURED", JSONObject()
            .put("sender", sender).put("body", body).put("sentAtMillis", sentAt)
            .put("receivedAtMillis", System.currentTimeMillis())
            .put("multipartCount", parts.size))
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagnosticStore.record(context, "MMS_UNSUPPORTED")
    }
}

class RespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticStore.record(this, "SEND_UNSUPPORTED")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
