package com.jobsense.feasibility

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class AppUpdateOffer(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long
)

data class AppUpdateState(
    val status: String = "Check the official LifeTracker update feed.",
    val currentVersion: String = "",
    val offer: AppUpdateOffer? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val downloadedApk: File? = null,
    val allowMetered: Boolean = false
)

object AppUpdateRules {
    fun validateManifestUrl(value: String): URI {
        val uri = try {
            URI(value.trim())
        } catch (error: Exception) {
            throw IllegalArgumentException("Enter a valid HTTPS manifest URL.", error)
        }
        require(uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && uri.toASCIIString().length <= 2048
        ) { "Use an HTTPS manifest URL without credentials, query parameters, or fragments." }
        return uri
    }

    fun validateArtifactUrl(manifest: URI, value: String): URI {
        val artifact = try {
            manifest.resolve(URI(value))
        } catch (error: Exception) {
            throw IllegalArgumentException("The update manifest contains an invalid APK URL.", error)
        }
        val isSameHost = artifact.host?.equals(manifest.host, ignoreCase = true) == true &&
            artifact.port == manifest.port
        val isOfficialGithubRelease = artifact.host?.equals("github.com", ignoreCase = true) == true &&
            artifact.port == -1 &&
            artifact.rawPath?.matches(
                Regex("/pjjr1019/LifeTracker/releases/download/v[1-9][0-9]*/lifetracker-ai-[1-9][0-9]*\\.apk")
            ) == true
        require(artifact.scheme?.equals("https", ignoreCase = true) == true &&
            (isSameHost || isOfficialGithubRelease) &&
            artifact.rawUserInfo == null && artifact.rawQuery == null && artifact.rawFragment == null &&
            artifact.rawPath?.endsWith(".apk", ignoreCase = true) == true
        ) { "The APK must use the manifest host or an official LifeTracker GitHub release URL." }
        return artifact
    }

    fun validateDownloadUrl(uri: URI) {
        val trustedHost = uri.host?.lowercase(Locale.ROOT) in setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
        )
        require(uri.scheme?.equals("https", ignoreCase = true) == true && trustedHost &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.toASCIIString().length <= 8192
        ) { "The GitHub release redirected to an untrusted download host." }
    }

    fun validateOffer(
        versionCode: Long,
        versionName: String,
        releaseNotes: String,
        sha256: String,
        sizeBytes: Long,
        currentVersionCode: Long
    ) {
        require(versionCode > currentVersionCode) { "The update is not newer than the installed version." }
        require(versionName.isNotBlank() && versionName.length <= 64 && versionName.none(Char::isISOControl)) {
            "The update version name is invalid."
        }
        require(releaseNotes.length <= 2000 && releaseNotes.none { it == '\u0000' }) {
            "The release notes are too long or invalid."
        }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "The update checksum is invalid." }
        require(sizeBytes in 1..MAX_APK_BYTES) { "The APK size is outside the supported limit." }
    }

    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 250L * 1024 * 1024
}

object AppUpdates {
    private const val PREFERENCES = "app_updates"
    private const val METERED_KEY = "allow_metered"
    private const val READY_VERSION_KEY = "ready_version_code"
    private const val MAX_NOTES = 2000
    private const val MAX_DOWNLOAD_REDIRECTS = 4
    const val DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/pjjr1019/LifeTracker/main/android/updates/stable.json"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun readyApk(context: Context): File? {
        val preferences = preferences(context)
        val version = preferences.getLong(READY_VERSION_KEY, 0)
        if (version <= 0) return null
        return File(context.cacheDir, "update-downloads/update-$version.apk").takeIf { it.isFile }
    }

    fun installedVersionCode(context: Context): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    fun check(context: Context, manifestUrl: String): AppUpdateOffer? {
        val manifestUri = AppUpdateRules.validateManifestUrl(manifestUrl)
        val body = readBounded(manifestUri.toURL(), AppUpdateRules.MAX_MANIFEST_BYTES)
        val json = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("The update server returned an invalid manifest.", error)
        }
        val packageName = json.opt("packageName") as? String
            ?: throw IOException("The update manifest has no valid package name.")
        require(packageName == context.packageName) { "This update is for a different app." }
        val versionCode = json.requiredWholeNumber("versionCode")
        val installedVersionCode = installedVersionCode(context)
        if (versionCode <= installedVersionCode) return null
        val versionName = json.opt("versionName") as? String
            ?: throw IOException("The update manifest has no valid version name.")
        val releaseNotes = json.opt("releaseNotes") as? String
            ?: throw IOException("The update manifest has no valid release notes.")
        require(releaseNotes.length <= MAX_NOTES) { "The release notes exceed the supported limit." }
        val checksum = json.opt("sha256") as? String
            ?: throw IOException("The update manifest has no valid checksum.")
        val sizeBytes = json.requiredWholeNumber("sizeBytes")
        val apkUrl = json.opt("apkUrl") as? String
            ?: throw IOException("The update manifest has no valid APK URL.")
        val apk = AppUpdateRules.validateArtifactUrl(manifestUri, apkUrl)
        AppUpdateRules.validateOffer(
            versionCode, versionName, releaseNotes, checksum, sizeBytes,
            installedVersionCode
        )
        return AppUpdateOffer(
            versionCode, versionName, releaseNotes, apk.toASCIIString(),
            checksum.lowercase(Locale.ROOT), sizeBytes
        )
    }

    suspend fun download(
        context: Context,
        offer: AppUpdateOffer,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkUri = URI(offer.apkUrl)
        val partial = File(context.cacheDir, "update-downloads/update-${offer.versionCode}.apk.part")
        val apk = File(context.cacheDir, "update-downloads/update-${offer.versionCode}.apk")
        val folder = partial.parentFile ?: throw IOException("Could not create the update download folder.")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create the update download folder.")
        if (folder.usableSpace < offer.sizeBytes + 8L * 1024 * 1024) {
            throw IOException("There is not enough free storage to download this update.")
        }
        partial.delete()

        val connection = openArtifactConnection(apkUri)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("The update server returned HTTP ${connection.responseCode}.")
            }
            val announcedLength = connection.contentLengthLong
            if (announcedLength >= 0 && announcedLength != offer.sizeBytes) {
                throw IOException("The update size does not match its signed manifest.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val amount = input.read(buffer)
                        if (amount < 0) break
                        received += amount
                        if (received > offer.sizeBytes || received > AppUpdateRules.MAX_APK_BYTES) {
                            throw IOException("The update download exceeded its allowed size.")
                        }
                        digest.update(buffer, 0, amount)
                        output.write(buffer, 0, amount)
                        onProgress((received * 100 / offer.sizeBytes).toInt())
                    }
                    output.fd.sync()
                }
            }
            if (received != offer.sizeBytes) throw IOException("The update download was incomplete. Retry the download.")
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != offer.sha256) throw IOException("The update checksum failed. The APK was discarded.")
            validateApk(context, partial, offer)
            if (apk.exists() && !apk.delete()) throw IOException("Could not replace the previous update file.")
            if (!partial.renameTo(apk)) throw IOException("Could not finish saving the verified update.")
            preferences(context).edit().putLong(READY_VERSION_KEY, offer.versionCode).apply()
            apk
        } catch (error: Exception) {
            partial.delete()
            if (error is CancellationException) throw error
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun clearReadyUpdate(context: Context) {
        readyApk(context)?.delete()
        preferences(context).edit().remove(READY_VERSION_KEY).apply()
    }

    private fun validateApk(context: Context, file: File, offer: AppUpdateOffer) {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: throw IOException("Android could not read the downloaded APK.")
        require(info.packageName == context.packageName) { "The downloaded APK is for a different app." }
        val apkVersion = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        require(apkVersion == offer.versionCode) { "The APK version does not match the update manifest." }
        require(info.versionName == offer.versionName) { "The APK version name does not match the update manifest." }
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?: throw IOException("Could not verify the installed app's signing identity.")
        val updateSigners = info.signingInfo?.apkContentsSigners
            ?: throw IOException("Could not verify the update's signing identity.")
        val installedCertificates = installedSigners.map { sha256(it.toByteArray()) }.toSet()
        val updateCertificates = updateSigners.map { sha256(it.toByteArray()) }.toSet()
        require(installedCertificates.isNotEmpty() && installedCertificates == updateCertificates) {
            "The update was not signed with the installed app's signing certificate."
        }
    }

    private fun openArtifactConnection(artifact: URI): HttpURLConnection {
        var current = artifact
        for (redirectCount in 0..MAX_DOWNLOAD_REDIRECTS) {
            AppUpdateRules.validateDownloadUrl(current)
            val connection = current.toURL().openConnection() as? HttpURLConnection
                ?: throw IOException("Could not open the update connection.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    if (redirectCount == MAX_DOWNLOAD_REDIRECTS) {
                        connection.disconnect()
                        throw IOException("The update server redirected too many times.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: run {
                            connection.disconnect()
                            throw IOException("The update server returned an invalid redirect.")
                        }
                    val next = try {
                        current.resolve(URI(location))
                    } catch (error: Exception) {
                        connection.disconnect()
                        throw IOException("The update server returned an invalid redirect.", error)
                    }
                    connection.disconnect()
                    AppUpdateRules.validateDownloadUrl(next)
                    current = next
                }
                else -> {
                    val responseCode = connection.responseCode
                    connection.disconnect()
                    throw IOException("The update server returned HTTP $responseCode.")
                }
            }
        }
        throw IOException("The update server redirected too many times.")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JSONObject.requiredWholeNumber(name: String): Long {
        val value = opt(name) as? Number
            ?: throw IOException("The update manifest has no valid $name.")
        val number = value.toDouble()
        if (!number.isFinite() || number < 0 || number > Long.MAX_VALUE || number % 1.0 != 0.0) {
            throw IOException("The update manifest has no valid $name.")
        }
        return number.toLong()
    }

    private fun readBounded(url: URL, maximum: Int): ByteArray {
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw IOException("Could not open the update connection.")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("The update server returned HTTP ${connection.responseCode}.")
            }
            val announcedLength = connection.contentLengthLong
            if (announcedLength > maximum) throw IOException("The update manifest is too large.")
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val amount = input.read(buffer)
                    if (amount < 0) break
                    if (output.size() + amount > maximum) throw IOException("The update manifest is too large.")
                    output.write(buffer, 0, amount)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppUpdates.preferences(application)
    private val readyApk = AppUpdates.readyApk(application)
    private val mutableState = MutableStateFlow(
        AppUpdateState(
            status = if (readyApk != null) {
                "A verified update is ready. Android approval is required to install it."
            } else {
                "Check the official LifeTracker update feed."
            },
            currentVersion = application.packageManager.getPackageInfo(
                application.packageName,
                0
            ).versionName.orEmpty(),
            downloadedApk = readyApk,
            allowMetered = preferences.getBoolean(METERED_KEY, false)
        )
    )
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private var operation: Job? = null

    fun setAllowMetered(allow: Boolean) {
        preferences.edit().putBoolean(METERED_KEY, allow).apply()
        mutableState.value = mutableState.value.copy(allowMetered = allow)
    }

    fun check() {
        operation?.cancel()
        operation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                checking = true,
                status = "Checking for updates…"
            )
            try {
                val offer = withContext(Dispatchers.IO) {
                    AppUpdates.check(getApplication(), AppUpdates.DEFAULT_MANIFEST_URL)
                }
                AppUpdates.clearReadyUpdate(getApplication())
                mutableState.value = mutableState.value.copy(
                    checking = false,
                    offer = offer,
                    downloadedApk = null,
                    status = if (offer == null) "No update is available." else "Update available."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(checking = false, status = error.message ?: "Could not check for updates.")
            }
        }
    }

    fun download() {
        val offer = mutableState.value.offer ?: return
        operation?.cancel()
        operation = viewModelScope.launch {
            val connectivity = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (!mutableState.value.allowMetered && connectivity.isActiveNetworkMetered) {
                mutableState.value = mutableState.value.copy(
                    status = "This network uses mobile data. Allow mobile-data downloads or connect to Wi-Fi."
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                downloading = true, downloadedApk = null, progress = 0,
                status = "Downloading update…"
            )
            try {
                val apk = AppUpdates.download(getApplication(), offer) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress)
                }
                mutableState.value = mutableState.value.copy(
                    downloading = false, progress = 100, downloadedApk = apk,
                    status = "Download verified. Android approval is required to install it."
                )
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(downloading = false, status = "Download canceled.")
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    downloading = false, progress = null,
                    status = error.message ?: "The download failed. Retry when the connection is available."
                )
            }
        }
    }

    fun cancelDownload() {
        operation?.cancel()
    }

    fun refreshInstallStatus() {
        val currentVersion = try {
            AppUpdates.installedVersionCode(getApplication())
        } catch (_: Exception) {
            return
        }
        val readyVersion = preferences.getLong(READY_VERSION_KEY, 0)
        if (readyVersion > 0 && currentVersion >= readyVersion) {
            AppUpdates.clearReadyUpdate(getApplication())
            mutableState.value = mutableState.value.copy(
                currentVersion = getApplication<Application>().packageManager.getPackageInfo(packageName(), 0).versionName.orEmpty(),
                offer = null, downloadedApk = null, downloading = false, progress = null,
                status = "Update installed successfully."
            )
        }
    }

    fun installPending() {
        mutableState.value = mutableState.value.copy(
            status = "Android's installer opened. Approve the update there to install it."
        )
    }

    fun reportStatus(message: String) {
        mutableState.value = mutableState.value.copy(status = message)
    }

    private fun packageName() = getApplication<Application>().packageName

    companion object {
        private const val URL_KEY = "manifest_url"
        private const val METERED_KEY = "allow_metered"
        private const val READY_VERSION_KEY = "ready_version_code"
    }
}
