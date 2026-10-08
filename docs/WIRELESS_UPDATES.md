# Wireless APK updates

The installed `com.jobsense.feasibility` app has an in-app updater in **Settings
→ App updates**. It checks the default stable JSON manifest at
`https://raw.githubusercontent.com/pjjr1019/LifeTracker/main/android/updates/stable.json`,
reviews its version and notes, downloads over Wi-Fi or (if explicitly enabled) mobile
data, and requires the user to tap **Install update**. Android handles the
installation confirmation; this app does not silently install or bypass it.

The updater rejects HTTP, credentials/query strings in update URLs, cross-host or
redirected APK downloads, wrong package/version, downgrades, oversized or incomplete
downloads, checksum failures, and APK signing certificates that do not exactly match
the installed app. A matching SHA-256 detects transfer corruption; Android's
certificate checks and installer enforce app identity. Host the manifest and APKs on
a publisher-controlled HTTPS service. A trusted publisher/host is still required:
the checksum is supplied by that HTTPS manifest and is not a substitute for server
trust. The manifest and release notes are not separately public-key signed.

## Manifest format

The endpoint returns UTF-8 JSON no larger than 64 KiB:

```json
{
  "packageName": "com.jobsense.feasibility",
  "versionCode": 4,
  "versionName": "0.4.0-github-updates",
  "releaseNotes": "Adds the default GitHub-hosted wireless update feed.",
  "apkUrl": "releases/4/lifetracker-ai-4.apk",
  "sha256": "64 lowercase hexadecimal characters",
  "sizeBytes": 12345678
}
```

`versionCode` must be greater than the installed version. `apkUrl` can be relative
to the manifest, but must resolve to an HTTPS APK on the same host. The manifest's size and SHA-256 must describe the exact APK bytes. The APK is
downloaded from a versioned GitHub Release asset URL; GitHub redirects the transfer
to its HTTPS release-asset host. Only this repository's versioned release-asset path
and GitHub's allowlisted asset hosts are accepted. The updater caps APKs at 250 MiB
and release notes at 2,000 characters. Redirects are not followed.

## Prepare a static release directory

Build an APK with the intended publisher signing key and an incremented version
code. For a local same-PC debug test only:

```powershell
Set-Location android
.\gradlew.bat :feasibility:assembleDebug --no-daemon
```

The debug signing key is machine-specific and **must not be distributed to friends**.
For real users, first establish one protected release key and sign the base app and
all updates with that same key/valid Android signing lineage. No release key is
configured or stored by this repository. Never commit keystores or passwords.

Generate a new immutable APK plus a higher-version stable manifest in a local
directory:

```powershell
.\feasibility\tools\Publish-UpdateManifest.ps1 `
  -ApkPath .\feasibility\build\outputs\apk\debug\feasibility-debug.apk `
  -OutputDirectory .\updates `
  -VersionCode 4 `
  -VersionName 0.4.0-github-updates `
  -ReleaseNotes "Adds the default GitHub-hosted wireless update feed."
```

The script writes `stable.json` and `releases/<versionCode>/lifetracker-ai-<versionCode>.apk`.
It computes the byte length and SHA-256, refuses to overwrite an immutable APK or
publish a non-increasing version, and atomically replaces an existing local manifest.
Create a GitHub Release tagged `v<versionCode>` and upload the generated APK at that
exact asset name. Once the release is published, commit only `stable.json` into
`android/updates/stable.json`; the app uses the fixed manifest URL above, so no
per-device URL setup is required. Keep the source APK signed with the same protected
publisher key for every version. The local debug key only supports private testing
on devices that already trust that same key.

## Verification and limitations

- Exercise manifest URL/field/size/version validation and same-host HTTPS policy
  with `:feasibility:testDebugUnitTest`.
- The release feed is public because this repository is public. Do not include
  private message databases or keystores in release commits.
- Test a real update on a disposable test device only with an APK signed by the
  installed app's same certificate. Confirm the release-asset redirect remains on
  GitHub's allowlisted HTTPS hosts, Android presents its installer, and app data
  survives the in-place update.
- To use the same-phone debug build as a source, publish a debug APK only to a feed
  for devices that already trust the matching debug key. This does not make that APK
  installable as an update on friends' phones.
- This implementation currently performs checks/downloads on user action. It does
  not poll in the background, host files itself, resume partial downloads, or verify
  a detached manifest signature. Transient failures can be retried from Settings;
  each retry safely restarts the download.
- A successful download or opened installer is not proof of completed installation.
  The app reports completion only after it resumes with the offered version installed.
- Do not test by uninstalling, clearing app data, changing the package name, or
  signing with a different key. Make a verified backup before testing updates that
  touch important user data.
