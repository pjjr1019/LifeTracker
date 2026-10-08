# LifeTracker Android app

Android application modules and the stable wireless update feed for `com.jobsense.feasibility`.

Build with JDK 17 and Android SDK 35 from the `android` directory:

```powershell
.\gradlew.bat :feasibility:assembleDebug :feasibility:testDebugUnitTest :feasibility:lintDebug --no-daemon
```

The updater uses the public feed at:

`https://raw.githubusercontent.com/pjjr1019/LifeTracker/main/android/updates/stable.json`

Updates must be signed with the app's existing publisher certificate. Never commit signing keys, passwords, private databases, or personal message archives. Android always asks the user to approve installation.
