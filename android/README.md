# LifeTracker Android app

This repository contains the Android application modules and the stable wireless
update feed used by the app installed as `com.jobsense.feasibility`.

## Build

Use JDK 17 and Android SDK 35. From this directory:

```powershell
.\gradlew.bat :feasibility:assembleDebug :feasibility:testDebugUnitTest :feasibility:lintDebug --no-daemon
```

The updater uses the default public feed at
`https://raw.githubusercontent.com/pjjr1019/LifeTracker/main/android/updates/stable.json`.
To publish an update, increment `feasibility/build.gradle.kts`, build an APK with
the same protected signing key, then run
`feasibility/tools/Publish-UpdateManifest.ps1` with `-OutputDirectory .\updates`.
Upload `releases/<version>/lifetracker-ai-<version>.apk` as an asset on the matching
GitHub Release (`v<version>`); commit `stable.json` to `android/updates` only after
the release is published. The repository is public; never commit signing keys,
passwords, private databases, or personal message archives.

See [the update guide](../docs/WIRELESS_UPDATES.md) in the source workspace for
manifest requirements and Android's user-approved installer flow.
