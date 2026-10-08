package com.jobsense.feasibility

import android.content.Context

object HistoryConnection {
    const val LOCAL = "local"
    const val DRIVE = "drive"
    const val LAPTOP = "laptop"
    fun mode(context: Context) = DiagnosticStore.preferences(context).getString("historyConnectionMode", LOCAL)
    @Synchronized fun select(context: Context, mode: String) {
        require(mode == LOCAL || mode == DRIVE || mode == LAPTOP)
        if (mode != LAPTOP) WirelessSharing.pause(context)
        if (mode != DRIVE) { AutoChatDocument.pause(context); DriveArchive.pause(context) }
        DiagnosticStore.preferences(context).edit().putString("historyConnectionMode", mode).commit()
        if (mode == DRIVE) {
            if (!DiagnosticStore.preferences(context).getBoolean("driveMigrationConfirmed", false)) AutoChatDocument.resume(context)
            DriveArchive.resume(context)
        }
        if (mode == LAPTOP) WirelessSharing.resume(context)
    }
}
