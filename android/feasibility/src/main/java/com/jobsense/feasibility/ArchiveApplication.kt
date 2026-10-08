package com.jobsense.feasibility

import android.app.Application

class ArchiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ArchiveObservers.start(this)
        DriveArchive.schedule(this)
        // Existing WorkManager jobs survive app/process recreation. The UI and capture receiver
        // initialize/migrate the selected archive on their background executor when needed.
    }
}
