package com.jobsense.feasibility

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ArchiveContact(val scope: HistoryScope)
data class ArchiveUiState(val loading: Boolean = true, val busy: Boolean = false, val chats: List<ArchiveChat> = emptyList(),
    val status: ArchiveStatus = ArchiveStatus(0, -1, 0, 0, 0), val canReadMessages: Boolean = false, val canReceiveMessages: Boolean = false,
    val online: Boolean = false, val account: String = "", val driveConnected: Boolean = false, val legacyConnected: Boolean = false,
    val mode: String = HistoryConnection.LOCAL, val captureError: String = "", val uploadError: String = "",
    val appearance: String = "system", val mobileData: Boolean = true, val notice: String = "", val selectedChat: String? = null,
    val messages: List<ArchiveMessage> = emptyList(), val moreMessages: Boolean = false, val messageSearch: String = "",
    val contacts: List<ArchiveContact> = emptyList(), val groups: List<ArchiveThread> = emptyList(), val masterLink: String = "", val indexLink: String = "")

class ArchiveViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ArchiveUiState(selectedChat = savedState["selectedChat"], messageSearch = savedState["messageSearch"] ?: ""))
    val state = mutable.asStateFlow()
    private val app = application.applicationContext
    private var reading = false
    private var messagePages = 1
    init { reload(); ArchiveWork.schedule(app) }

    fun reload() {
        if (reading) return
        reading = true
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    ArchiveStore.initialize(app)
                    val prefs = DiagnosticStore.preferences(app)
                    val connectivity = app.getSystemService(ConnectivityManager::class.java)
                    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    val chats = ArchiveStore.chats(app)
                    mutable.value.copy(loading = false, chats = chats, status = ArchiveStore.status(app),
                        canReadMessages = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
                        canReceiveMessages = app.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
                        online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                        account = DriveArchive.account(app), driveConnected = DriveArchive.enabled(app), legacyConnected = AutoChatDocument.enabled(app),
                        mode = HistoryConnection.mode(app).orEmpty(), captureError = prefs.getString("archiveCaptureError", "").orEmpty(),
                        uploadError = prefs.getString("driveArchiveError", "").orEmpty(), appearance = prefs.getString("archiveAppearance", "system").orEmpty(),
                        mobileData = prefs.getBoolean("archiveMobileData", true), masterLink = DriveArchive.fileLink(app, "master"), indexLink = DriveArchive.fileLink(app, "index"))
                }
                mutable.value = snapshot.copy(busy = mutable.value.busy, notice = mutable.value.notice, selectedChat = mutable.value.selectedChat,
                    messages = mutable.value.messages, messageSearch = mutable.value.messageSearch, contacts = mutable.value.contacts, groups = mutable.value.groups)
                mutable.value.selectedChat?.let { loadMessages(it, mutable.value.messageSearch, reset = false) }
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(loading = false, captureError = "Saved history could not be opened. Retry without removing the app.")
            } finally { reading = false }
        }
    }
    fun notice(message: String) { mutable.value = mutable.value.copy(notice = message) }
    fun clearNotice() = notice("")
    fun operation(success: String = "", action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; if (success.isNotBlank()) notice(success) }
            catch (error: SecurityException) { notice("Allow message or contact access in Android Settings, then try again.") }
            catch (error: Exception) { notice("The action could not finish. Your saved history is still preserved; try again.") }
            finally { mutable.value = mutable.value.copy(busy = false); reload() }
        }
    }
    fun loadContacts() = operation {
        val contacts = app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER), null, null, "display_name COLLATE NOCASE ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val raw = c.getString(1).orEmpty()
                    val country = Locale.getDefault().country.ifBlank { "US" }
                    val number = TestContact.normalizedNumber(c.getString(2).orEmpty()) ?: TestContact.normalizedNumber(raw)
                        ?: PhoneNumberUtils.formatNumberToE164(raw, country) ?: continue
                    val scope = runCatching { HistoryScope(c.getString(0).orEmpty().ifBlank { number }, number, raw, country) }.getOrElse {
                        HistoryScope(c.getString(0).orEmpty().ifBlank { number }, number, number, country)
                    }
                    add(ArchiveContact(scope))
                }
            }.distinctBy { it.scope.key }
        } ?: throw java.io.IOException("Contacts unavailable")
        mutable.value = mutable.value.copy(contacts = contacts)
    }
    fun loadGroups() = operation { mutable.value = mutable.value.copy(groups = ArchiveThreads.read(app).filter { it.participants.size > 1 }) }
    fun addContacts(ids: Set<String>, sharing: Boolean) = operation("Chosen chats added. History importing runs automatically.") {
        ArchiveStore.addContacts(app, mutable.value.contacts.filter { it.scope.key in ids }.map { it.scope }, sharing)
    }
    fun addGroups(ids: Set<Long>, sharing: Boolean) = operation("Chosen groups added. History importing runs automatically.") {
        mutable.value.groups.filter { it.id in ids }.forEach { ArchiveStore.addGroup(app, it, "Work group ${it.id}", sharing) }
    }
    fun configure(chat: ArchiveChat, saving: Boolean? = null, sharing: Boolean? = null, name: String? = null) = operation {
        ArchiveStore.configure(app, chat.id, saving, sharing, name)
        if (saving != null && chat.kind == "INDIVIDUAL" && ConversationSaving.isEnabled(app, chat.individualScope()) && !saving)
            ConversationSaving.stop(app, chat.individualScope())
    }
    fun chooseMode(mode: String) = operation { HistoryConnection.select(app, mode) }
    fun preference(key: String, value: String) {
        DiagnosticStore.preferences(app).edit().putString(key, value).apply(); reload()
    }
    fun mobileData(enabled: Boolean) {
        DiagnosticStore.preferences(app).edit().putBoolean("archiveMobileData", enabled).apply()
        DriveArchive.schedule(app); reload()
    }
    fun selectChat(id: String?) {
        savedState["selectedChat"] = id
        savedState["messageSearch"] = ""
        mutable.value = mutable.value.copy(selectedChat = id, messages = emptyList(), messageSearch = "")
        messagePages = 1
        if (id != null) loadMessages(id, "", true)
    }
    fun searchMessages(value: String) { savedState["messageSearch"] = value; mutable.value = mutable.value.copy(messageSearch = value); mutable.value.selectedChat?.let { loadMessages(it, value, true) } }
    fun olderMessages() { messagePages++; mutable.value.selectedChat?.let { loadMessages(it, mutable.value.messageSearch, false) } }
    private fun loadMessages(id: String, query: String, reset: Boolean) {
        if (reset) messagePages = 1
        val pages = messagePages
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    synchronized(ArchiveStore) {
                        val db = ConversationLedger.archiveDatabase(app)
                        db.beginTransactionNonExclusive()
                        try {
                            val result = (0 until pages).flatMap { page -> ArchiveStore.messages(app, id, query, 100, page * 100) }
                                .distinctBy { it.id }.sortedWith(compareByDescending<ArchiveMessage> { it.at }.thenByDescending { it.id })
                            db.setTransactionSuccessful()
                            result
                        } finally { db.endTransaction() }
                    }
                }
                if (mutable.value.selectedChat == id && mutable.value.messageSearch == query)
                    mutable.value = mutable.value.copy(messages = rows, moreMessages = rows.size == pages * 100)
            } catch (error: Exception) { notice("This conversation could not be opened. Try checking history again.") }
        }
    }
}
