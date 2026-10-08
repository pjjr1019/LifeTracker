package com.jobsense.feasibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import org.json.JSONObject

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            DiagnosticStore.record(context, "GEOFENCE_ERROR", JSONObject().put("code", event.errorCode))
            return
        }
        val type = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "GEOFENCE_ENTER"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "GEOFENCE_DWELL"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "GEOFENCE_EXIT"
            else -> "GEOFENCE_UNKNOWN"
        }
        DiagnosticStore.record(context, type)
    }
}
