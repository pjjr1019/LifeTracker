package com.jobsense.feasibility

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class MessageNotificationListener : NotificationListenerService() {
    private val observedVersions = linkedMapOf<String, Long>()
    private var connected = false
    private val messageWorker = Executors.newSingleThreadExecutor()
    private val observerHandler = Handler(Looper.getMainLooper())
    private var smsObserverRegistered = false
    private val checkSms = Runnable { ConversationSaving.requestCheck(this) }
    private val smsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            observerHandler.removeCallbacks(checkSms)
            observerHandler.postDelayed(checkSms, 800)
        }
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (connected && intent.action == REFRESH_ACTION) {
                ensureSmsObserver()
                scanActiveNotifications()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, refreshReceiver, IntentFilter(REFRESH_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        messageWorker.shutdownNow()
        observerHandler.removeCallbacks(checkSms)
        if (smsObserverRegistered) contentResolver.unregisterContentObserver(smsObserver)
        unregisterReceiver(refreshReceiver)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        DiagnosticStore.record(this, "NOTIFICATION_LISTENER_CONNECTED")
        ensureSmsObserver()
        ConversationSaving.requestCheck(this)
        scanActiveNotifications()
    }

    private fun ensureSmsObserver() {
        if (smsObserverRegistered) return
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
            smsObserverRegistered = true
        } catch (error: SecurityException) {
            DiagnosticStore.record(this, "SMS_CHANGE_OBSERVER_UNAVAILABLE")
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        DiagnosticStore.record(this, "NOTIFICATION_LISTENER_DISCONNECTED")
    }

    private fun scanActiveNotifications() {
        val candidates = activeNotifications.orEmpty().filter {
            it.packageName == "com.google.android.apps.messaging"
        }
        if (candidates.isEmpty()) DiagnosticStore.record(this, "NO_ACTIVE_GOOGLE_MESSAGES_NOTIFICATION")
        candidates.forEach { captureNotification(it, "ACTIVE_RESCAN") }
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        captureNotification(notification, "POSTED_NOTIFICATION")
    }

    private fun captureNotification(notification: StatusBarNotification, captureMethod: String) {
        if (notification.packageName != "com.google.android.apps.messaging") return
        val preferences = DiagnosticStore.preferences(this)
        val expectedName = preferences.getString("testName", "").orEmpty()
        val expectedNumber = preferences.getString("testNumber", "").orEmpty()
        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.notification)
        val messages = style?.messages.orEmpty()
        // Capture only the named test conversation. No general notification scraping.
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val identity = NotificationIdentity(
            titles = listOf(title, conversationTitle, style?.conversationTitle?.toString().orEmpty()),
            senderNames = messages.mapNotNull { it.person?.name?.toString() },
            senderUris = messages.mapNotNull { it.person?.uri },
            isGroupConversation = style?.isGroupConversation == true,
        )
        val matched = TestContact.matchesNotification(expectedName, expectedNumber, identity)
        val matchingScopes = TestContact.matchingConversations(ConversationSaving.scopes(this), identity)
        if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (matchingScopes.size > 1) {
            // A shared display name is not sufficient to choose between two saved contacts.
            DiagnosticStore.record(this, "CONVERSATION_NOTIFICATION_IDENTITY_AMBIGUOUS")
            return
        }
        if (!matched && matchingScopes.isEmpty()) {
            // Export only the reason; never disclose another conversation's title or body.
            DiagnosticStore.record(this, "GOOGLE_MESSAGES_NOTIFICATION_NOT_MATCHED")
            return
        }
        for (scope in matchingScopes) {
            messageWorker.execute {
            if (ConversationSaving.isEnabled(this, scope)) {
            try {
                val exposed = style?.let { it.historicMessages + it.messages }.orEmpty()
                if (exposed.isNotEmpty()) {
                    for (message in exposed) {
                        if (Thread.currentThread().isInterrupted || !ConversationSaving.isEnabled(this, scope)) break
                        val messageAt = message.timestamp.takeIf { it > 0 } ?: notification.postTime
                        val sender = message.person?.uri ?: message.person?.name?.toString().orEmpty()
                        val text = message.text?.toString().orEmpty()
                        val direction = if (message.person == null) "OUTGOING_INDICATED" else "INCOMING_INDICATED"
                        val key = MessageEvidenceKey.from("GOOGLE_MESSAGES_NOTIFICATION", notification.key,
                            messageAt.toString(), sender, text, direction, message.dataMimeType.orEmpty())
                        ConversationLedger.saveNotification(this, scope, key, messageAt, JSONObject()
                            .put("body", text).put("messageAtMillis", messageAt).put("notificationPostedAtMillis", notification.postTime)
                            .put("direction", direction).put("senderMetadata", sender)
                            .put("attachmentMimeType", message.dataMimeType).put("attachmentContentCopied", false)
                            .put("channel", "UNKNOWN_NOTIFICATION").put("coverage", "PARTIAL"))
                    }
                } else {
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val key = MessageEvidenceKey.from("NOTIFICATION_TEXT_FALLBACK", notification.key, notification.postTime.toString(), text)
                        ConversationLedger.saveNotification(this, scope, key, notification.postTime, JSONObject()
                            .put("body", text).put("direction", "UNKNOWN").put("channel", "UNKNOWN_NOTIFICATION")
                            .put("coverage", "PARTIAL").put("sourceDetail", "COLLAPSED_TEXT_FALLBACK"))
                    }
                }
                ConversationSaving.requestCheck(this)
            } catch (error: Exception) {
                DiagnosticStore.record(this, "CONVERSATION_NOTIFICATION_SAVE_FAILED")
            }
            }
            }
        }
        if (observedVersions[notification.key] == notification.postTime) return
        if (observedVersions.size >= 100) observedVersions.remove(observedVersions.keys.first())
        observedVersions[notification.key] = notification.postTime
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        DiagnosticStore.record(this, "MESSAGE_NOTIFICATION_CAPTURED", JSONObject()
            .put("package", notification.packageName).put("key", notification.key)
            .put("title", title).put("text", body)
            .put("conversationTitle", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString())
            .put("postedAtMillis", notification.postTime)
            .put("captureMethod", captureMethod)
            .put("messagingStyleCount", messages.size)
            .put("hasPersonMetadata", messages.any { it.person != null })
            .put("channel", "UNKNOWN_NOTIFICATION")
            .put("coverage", "PARTIAL"))
    }

    companion object {
        private const val REFRESH_ACTION = "com.jobsense.feasibility.REFRESH_TEST_NOTIFICATION"

        fun refreshTestNotification(context: Context) {
            context.sendBroadcast(Intent(REFRESH_ACTION).setPackage(context.packageName))
        }
    }
}
