package com.jobsense.feasibility

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object DiagnosticStore {
    fun preferences(context: Context) = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, kind: String, payload: JSONObject = JSONObject()) {
        val preferences = preferences(context)
        val events = JSONArray(preferences.getString("events", "[]"))
        val power = context.getSystemService(PowerManager::class.java)
        val event = JSONObject()
            .put("type", kind)
            .put("observedAtUtc", Instant.now().toString())
            .put("screenOn", power.isInteractive)
            .put("batteryOptimized", !power.isIgnoringBatteryOptimizations(context.packageName))
            .put("payload", payload)
        events.put(event)
        // Bound the disposable spike's storage. Evidence stays on this phone.
        val bounded = JSONArray()
        for (index in maxOf(0, events.length() - 100) until events.length()) {
            bounded.put(events.getJSONObject(index))
        }
        preferences.edit().putString("events", bounded.toString()).commit()
        context.openFileOutput("phase0_summary.json", Context.MODE_PRIVATE).use {
            it.write(summary(context).toString(2).toByteArray())
        }
    }

    fun summary(context: Context): JSONObject {
        val counts = JSONObject()
        val timeline = JSONArray()
        val events = JSONArray(preferences(context).getString("events", "[]"))
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            val type = event.getString("type")
            counts.put(type, counts.optInt(type) + 1)
            // Export only timing and device state; never message content or contact details.
            val exportedEvent = JSONObject().put("type", type)
                .put("observedAtUtc", event.getString("observedAtUtc"))
                .put("screenOn", event.getBoolean("screenOn"))
                .put("batteryOptimized", event.getBoolean("batteryOptimized"))
            if (type == "MESSAGE_NOTIFICATION_CAPTURED") {
                val payload = event.getJSONObject("payload")
                exportedEvent.put("captureMethod", payload.optString("captureMethod", "POSTED_NOTIFICATION"))
                    .put("postedAtMillis", payload.getLong("postedAtMillis"))
                    .put("textLength", payload.optString("text").length)
            }
            if (type == "SMS_HISTORY_IMPORTED") {
                exportedEvent.put("messageCount", event.getJSONObject("payload").optInt("messageCount"))
            }
            if (type == "CONVERSATION_SMS_MESSAGES_SAVED" || type == "CONVERSATION_SMS_CHECKED") {
                exportedEvent.put("newEvidenceCount", event.getJSONObject("payload").optInt("newEvidenceCount"))
            }
            timeline.put(exportedEvent)
        }
        val roles = context.getSystemService(RoleManager::class.java)
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val notifications = context.getSystemService(NotificationManager::class.java)
        val preferences = preferences(context)
        val canonical = TestContact.normalizedNumber(preferences.getString("testNumber", "").orEmpty())
        val history = if (canonical == null) JSONObject() else ChatHistoryStore.summary(context,
            HistoryScope(preferences.getString("testName", "").orEmpty(), canonical,
                preferences.getString("testRawNumber", canonical).orEmpty(), preferences.getString("testCountry", "").orEmpty()))
        val ledger = if (canonical == null) JSONObject() else ConversationLedger.summary(context,
            HistoryScope(preferences.getString("testName", "").orEmpty(), canonical,
                preferences.getString("testRawNumber", canonical).orEmpty(), preferences.getString("testCountry", "").orEmpty()))
        return JSONObject().put("eventCounts", counts).put("totalEvents", events.length())
            .put("selectedHistory", history)
            .put("conversationSaving", ledger)
            .put("enabledConversationCount", ConversationSaving.scopes(context).size)
            .put("sharedConversationCount", ChatHistoryStore.sharedConversationCount(context))
            .put("automaticChatDocumentEnabled", AutoChatDocument.enabled(context))
            .put("automaticChatDocumentLastWriteMillis", preferences(context).getLong("autoDocumentLastWrite", 0))
            .put("wirelessSharingEnabled", WirelessSharing.enabled(context))
            .put("wirelessLastSuccessMillis", preferences(context).getLong("wirelessLastSuccess", 0))
            .put("timeline", timeline)
            .put("device", JSONObject().put("model", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT))
            .put("smsRole", JSONObject().put("available", roles.isRoleAvailable(RoleManager.ROLE_SMS))
                .put("held", roles.isRoleHeld(RoleManager.ROLE_SMS))
                .put("defaultPackage", Telephony.Sms.getDefaultSmsPackage(context)))
            .put("permissions", JSONObject()
                .put("readSms", granted(Manifest.permission.READ_SMS))
                .put("preciseLocation", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("backgroundLocation", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .put("notificationAccess", notifications.isNotificationListenerAccessGranted(
                    ComponentName(context, MessageNotificationListener::class.java))))
    }
}
