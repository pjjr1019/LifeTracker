package com.jobsense.feasibility

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class DiagnosticActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var number: EditText
    private lateinit var name: EditText
    private lateinit var historyStatus: TextView
    private lateinit var autoDocumentStatus: TextView
    private lateinit var laptopStatus: TextView
    private var pendingDocumentScopes: List<HistoryScope>? = null
    private var showingSettings = false
    private val controls = mutableListOf<Button>()
    private val historyWorker = Executors.newSingleThreadExecutor()
    private var historyBusy = false
    private var pendingHistoryScope: HistoryScope? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showingSettings = savedInstanceState?.getBoolean("showingSettings") == true
        pendingDocumentScopes = savedInstanceState?.getString("pendingDocumentScopes")?.let { saved ->
            runCatching {
                val entries = org.json.JSONArray(saved)
                (0 until entries.length()).map { entries.getJSONObject(it).let { entry ->
                    HistoryScope(entry.getString("name"), entry.getString("number"),
                        entry.getString("rawNumber"), entry.getString("country"))
                } }.distinctBy { it.key }.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        val chatsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 2, padding, padding * 2)
        }
        val settingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        var layout = chatsLayout
        fun text(value: String) = TextView(this).apply { text = value; layout.addView(this) }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label; setOnClickListener { action() }; controls.add(this)
            })
        }
        text("JobSense — phone checks").textSize = 24f
        text("Message records stay on this phone unless you enable wireless sharing for chosen chats. Only your chosen conversations are saved. " +
            "This temporary app cannot send texts or receive MMS. Restore Google Messages immediately after the SMS test.")
        val preferences = DiagnosticStore.preferences(this)
        number = EditText(this).apply {
            hint = "Test sender in international format (+1...)"
            setSingleLine(true)
            setText(preferences.getString("testNumber", ""))
        }
        name = EditText(this).apply {
            hint = "Conversation name as shown in Google Messages"
            setSingleLine(true)
            setText(preferences.getString("testName", ""))
        }
        layout.addView(number)
        layout.addView(name)
        button("Choose contact") {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 103)
            } catch (error: ActivityNotFoundException) {
                showHistoryMessage("No contact picker is available. Enter a number with its country code and save it.")
            }
        }
        button("Save contact changes") {
            val normalized = TestContact.normalizedNumber(number.text.toString())
            if (normalized == null) {
                number.error = "Enter an international number starting with +"
            } else {
                saveContact(normalized, name.text.toString().trim(), normalized, Locale.getDefault().country)
                DiagnosticStore.record(this, "TEST_CONTACT_CONFIGURED")
                MessageNotificationListener.refreshTestNotification(this)
                updateStatus()
            }
        }
        text("Selected conversation history").textSize = 20f
        text("Choose a contact above. Import saved SMS from that number, or choose a file containing only that conversation. " +
            "Older RCS messages and MMS are not available through the SMS import. " +
            "Ongoing SMS saving includes received and sent SMS. RCS messages may be unavailable when the chat is open, " +
            "when notifications are hidden, or when you send them. Attachment content is not copied. Nothing is sent to the contact.")
        historyStatus = text("")
        button("Keep saving this conversation") {
            val scope = selectedScope() ?: return@button
            if (!hasPermission(Manifest.permission.READ_SMS)) {
                showHistoryMessage("Import this contact's SMS history first and allow Android's SMS permission. Then enable ongoing saving.")
            } else {
                ConversationSaving.start(this, scope)
                updateStatus()
                showHistoryMessage("Ongoing saving is on for this number. Saved SMS are checked automatically. " +
                    "Google Messages notifications add the messages they expose. RCS and MMS coverage remains partial, " +
                    "and background checks may be delayed by Android.")
            }
        }
        button("Stop saving this conversation") {
            val scope = selectedScope() ?: return@button
            ConversationSaving.stop(this, scope)
            updateStatus()
        }
        button("Stop saving all conversations") {
            ConversationSaving.stop(this)
            updateStatus()
        }
        button("Import this contact's SMS history") {
            val scope = selectedScope() ?: return@button
            AlertDialog.Builder(this).setTitle("Import selected SMS history?")
                .setMessage("Read saved incoming and sent SMS for ${scope.name.ifBlank { "this number" }} only. " +
                    "Android asks for SMS access, but this app filters the import to the saved number. " +
                    "RCS messages and MMS are excluded. Google Messages stays your default app.")
                .setNegativeButton("Cancel", null).setPositiveButton("Import SMS") { _, _ ->
                    pendingHistoryScope = scope
                    if (hasPermission(Manifest.permission.READ_SMS)) {
                        pendingHistoryScope = null
                        importSms(scope)
                    } else {
                        setHistoryBusy(true)
                        requestPermissions(arrayOf(Manifest.permission.READ_SMS), 105)
                    }
                }.show()
        }
        button("Import a conversation export file") {
            val scope = selectedScope() ?: return@button
            AlertDialog.Builder(this).setTitle("Choose this conversation's export")
                .setMessage("Choose a UTF-8 text, JSON or XML file containing only the selected conversation, up to 10 MB. " +
                    "This app preserves the file as supplied; it cannot verify its participants or completeness.")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                    pendingHistoryScope = scope
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/xml", "application/json", "application/xml"))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, 104)
                    } catch (error: ActivityNotFoundException) {
                        pendingHistoryScope = null
                        showHistoryMessage("No file picker is available on this phone.")
                    }
                }.show()
        }
        layout = settingsLayout
        text("Settings").textSize = 24f
        text("Choose how ChatGPT gets your job history. Switching pauses the other route; previously shared copies remain. " +
            "A configured route resumes with its previously approved contacts. Each route needs its one-time connection setup.")
        val connectionChoice = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val localChoice = android.widget.RadioButton(this).apply { id = android.view.View.generateViewId(); text = "Local only — save on this phone" }
        val driveChoice = android.widget.RadioButton(this).apply { id = android.view.View.generateViewId(); text = "Google Drive" }
        val laptopChoice = android.widget.RadioButton(this).apply { id = android.view.View.generateViewId(); text = "Laptop" }
        connectionChoice.addView(localChoice); connectionChoice.addView(driveChoice); connectionChoice.addView(laptopChoice); settingsLayout.addView(connectionChoice)
        val localPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(TextView(this@DiagnosticActivity).apply { text = "Chosen chats stay saved privately on this phone. " +
                "Drive and laptop updates are paused. ChatGPT cannot automatically retrieve this private phone history in this mode. " +
                "Previously shared copies remain where you shared them." })
        }
        val drivePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val laptopPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsLayout.addView(localPanel); settingsLayout.addView(drivePanel); settingsLayout.addView(laptopPanel)
        fun displayConnection(mode: String) {
            localPanel.visibility = if (mode == HistoryConnection.LOCAL) android.view.View.VISIBLE else android.view.View.GONE
            drivePanel.visibility = if (mode == HistoryConnection.DRIVE) android.view.View.VISIBLE else android.view.View.GONE
            laptopPanel.visibility = if (mode == HistoryConnection.LAPTOP) android.view.View.VISIBLE else android.view.View.GONE
        }
        val initialMode = HistoryConnection.mode(this).orEmpty()
        connectionChoice.check(when (initialMode) { HistoryConnection.LAPTOP -> laptopChoice.id; HistoryConnection.DRIVE -> driveChoice.id; else -> localChoice.id })
        displayConnection(initialMode)
        connectionChoice.setOnCheckedChangeListener { _, id ->
            val mode = when (id) { laptopChoice.id -> HistoryConnection.LAPTOP; driveChoice.id -> HistoryConnection.DRIVE; else -> HistoryConnection.LOCAL }
            HistoryConnection.select(this, mode); displayConnection(mode); updateStatus()
        }
        layout = laptopPanel
        text("Laptop connection").textSize = 20f
        text("Your laptop must stay awake and online. This route needs a running JobSense service, a secure phone connection, " +
            "and the service connected to ChatGPT. Enter its HTTPS address and pairing code after setup. " +
            "No USB is needed after the connection is configured.")
        laptopStatus = text(WirelessSharing.status(this))
        button("Connect laptop service") {
            val scopes = (ConversationSaving.scopes(this) + ChatHistoryStore.importedScopes(this)).distinctBy { it.key }
            if (scopes.isEmpty()) { showHistoryMessage("Save a chosen conversation first."); return@button }
            val checked = BooleanArray(scopes.size)
            AlertDialog.Builder(this).setTitle("Choose chats for your laptop")
                .setMultiChoiceItems(scopes.map { it.name.ifBlank { it.number } }.toTypedArray(), checked) { _, index, selected ->
                    checked[index] = selected
                }.setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ ->
                    val chosen = scopes.filterIndexed { index, _ -> checked[index] }
                    if (chosen.isEmpty()) showHistoryMessage("Choose at least one chat.") else {
                        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        val address = EditText(this).apply { hint = "HTTPS service address ending in /snapshot"; setSingleLine(true) }
                        val code = EditText(this).apply { hint = "Pairing code"; setSingleLine(true)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                        fields.addView(address); fields.addView(code)
                        AlertDialog.Builder(this).setTitle("Laptop service connection").setView(fields)
                            .setMessage("Enable automatic uploads of these chosen saved chats to your running laptop service.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Enable") { _, _ ->
                                try { WirelessSharing.configure(this, address.text.toString(), code.text.toString(), chosen); updateStatus() }
                                catch (error: Exception) { showHistoryMessage("Enter the service HTTPS address and pairing code of at least 32 characters.") }
                            }.show()
                    }
                }.show()
        }
        button("Sync laptop now") { WirelessSharing.requestSync(this); updateStatus() }
        button("Pause laptop sync") { WirelessSharing.pause(this); updateStatus() }
        layout = drivePanel
        text("Automatic job history for ChatGPT").textSize = 20f
        text("Choose work chats once and save the connected history file in Google Drive. " +
            "In the file picker, choose Google Drive from its menu and save JobSense-work-chats.txt. JobSense updates that same file in the background; Drive handles cloud syncing. " +
            "Connect Google Drive to ChatGPT once so it can look up the current file for job questions. " +
            "No USB or running laptop is required. Updates may be delayed by Android, Drive or connectivity.")
        autoDocumentStatus = text(AutoChatDocument.status(this))
        button("Connect automatic history file") {
            val scopes = (ConversationSaving.scopes(this) + ChatHistoryStore.importedScopes(this)).distinctBy { it.key }
            if (scopes.isEmpty()) {
                showHistoryMessage("Save a chosen conversation first.")
                return@button
            }
            val checked = BooleanArray(scopes.size)
            AlertDialog.Builder(this).setTitle("Choose chats to keep updated in Drive")
                .setMultiChoiceItems(scopes.map { it.name.ifBlank { it.number } }.toTypedArray(), checked) { _, index, selected ->
                    checked[index] = selected
                }.setNegativeButton("Cancel", null).setPositiveButton("Choose Drive file") { _, _ ->
                    val chosen = scopes.filterIndexed { index, _ -> checked[index] }
                    if (chosen.isEmpty()) showHistoryMessage("Choose at least one chat.")
                    else {
                        pendingDocumentScopes = chosen
                        try {
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "JobSense-work-chats.txt")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            }, 107)
                        } catch (error: ActivityNotFoundException) {
                            pendingDocumentScopes = null
                            showHistoryMessage("No file picker is available on this phone.")
                        }
                    }
                }.show()
        }
        button("Update connected history now") { AutoChatDocument.requestUpdate(this); updateStatus() }
        button("Pause automatic history updates") {
            AutoChatDocument.pause(this); updateStatus()
            showHistoryMessage("Future document updates are paused. A write already in progress may finish. " +
                "The existing Drive file remains; delete it in Drive if needed. Local message saving continues.")
        }
        text("Optional: manual copy").textSize = 18f
        text("Create a file of chosen saved chats, then share it to ChatGPT or save it to Files and attach it. " +
            "No USB or running computer is needed. For future job questions, add the file to a ChatGPT project's sources. " +
            "Share a newer copy to include new messages; ChatGPT cannot read this phone's private files automatically.")
        button("Create chat history file") {
            val scopes = (ConversationSaving.scopes(this) + ChatHistoryStore.importedScopes(this)).distinctBy { it.key }
            if (scopes.isEmpty()) {
                showHistoryMessage("Save a chosen conversation first.")
                return@button
            }
            val checked = BooleanArray(scopes.size)
            AlertDialog.Builder(this).setTitle("Choose conversations for the file")
                .setMultiChoiceItems(scopes.map { it.name.ifBlank { it.number } }.toTypedArray(), checked) { _, index, selected ->
                    checked[index] = selected
                }.setNegativeButton("Cancel", null).setPositiveButton("Create file") { _, _ ->
                    val chosen = scopes.filterIndexed { index, _ -> checked[index] }
                    if (chosen.isEmpty()) showHistoryMessage("Choose at least one chat.")
                    else runHistoryTask("Creating chat history document") {
                        ChatDocument.create(this, chosen)
                        "File ready for ${chosen.size} chosen chats. Tap Share history file or Save history file to Files. " +
                            "ChatGPT can use it after you attach or send it there. New messages require a new file."
                    }
                }.show()
        }
        button("Share history file") {
            val file = ChatDocument.latest(this)
            if (file == null) showHistoryMessage("Create a chat history file first.")
            else {
                val uri = ChatDocument.uri(this, file)
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Selected chat history", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share chosen history to ChatGPT"))
                } catch (error: ActivityNotFoundException) {
                    showHistoryMessage("Save the file to Files, then attach it using ChatGPT's file button.")
                }
            }
        }
        button("Save history file to Files") {
            if (ChatDocument.latest(this) == null) showHistoryMessage("Create a chat history file first.")
            else {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "JobSense-chat-history-" + java.time.LocalDate.now() + ".txt")
                    }, 106)
                } catch (error: ActivityNotFoundException) { showHistoryMessage("No file saver is available. Use Share history file.") }
            }
        }
        layout = settingsLayout
        button("Share saved chats with assistant") {
            val scopes = (ConversationSaving.scopes(this) + ChatHistoryStore.importedScopes(this)).distinctBy { it.key }
            if (scopes.isEmpty()) {
                showHistoryMessage("Choose each contact and tap Keep saving this conversation first.")
                return@button
            }
            val checked = BooleanArray(scopes.size) { true }
            AlertDialog.Builder(this).setTitle("Choose chats to share")
                .setMultiChoiceItems(scopes.map { it.name.ifBlank { it.number } }.toTypedArray(), checked) { _, index, selected ->
                    checked[index] = selected
                }
                .setNegativeButton("Cancel", null).setPositiveButton("Share checked chats") { _, _ ->
                    val chosen = scopes.filterIndexed { index, _ -> checked[index] }
                    if (chosen.isEmpty()) showHistoryMessage("Choose at least one chat.")
                    else runHistoryTask("Preparing chosen chat histories") {
                        ChatHistoryStore.shareConversations(this, chosen)
                        "${chosen.size} chosen chat histories are ready for this assistant through the connected USB phone. " +
                            "Only checked contacts are included. SMS are refreshed; RCS/MMS coverage remains partial. No internet upload was made."
                    }
                }.show()
        }
        button("Remove shared history copy") {
            ChatHistoryStore.revokeAssistantAccess(this)
            DiagnosticStore.record(this, "HISTORY_SHARED_COPY_REMOVED")
            updateStatus()
            showHistoryMessage("The phone's shared copy was removed. Imported records remain on the phone. " +
                "This does not remove a copy the assistant has already read.")
        }
        button("Request temporary SMS role") {
            if (preferences.getString("testNumber", "").isNullOrBlank()) {
                number.error = "Save a test sender first"
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Temporary SMS test")
                    .setMessage("Only switch for a brief incoming SMS test. Sending and MMS are not supported. " +
                        "Restore Google Messages as soon as the test is over.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue") { _, _ -> requestSmsRole() }.show()
            }
        }
        button("Restore Google Messages / default SMS settings") {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        button("Allow test notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("Check current test notification") {
            MessageNotificationListener.refreshTestNotification(this)
        }
        button("Allow precise location") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION), 102)
        }
        button("Allow background location in settings") {
            AlertDialog.Builder(this)
                .setTitle("Test a temporary geofence")
                .setMessage("Choose Permissions > Location > Allow all the time. " +
                    "The test geofence expires after 30 minutes. No continuous location history is recorded.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }.show()
        }
        button("Start a 30-minute test geofence here") { registerGeofence() }
        button("Remove test geofence") {
            LocationServices.getGeofencingClient(this).removeGeofences(geofenceIntent())
                .addOnSuccessListener { DiagnosticStore.record(this, "GEOFENCE_REMOVED") }
                .addOnFailureListener { DiagnosticStore.record(this, "GEOFENCE_REMOVE_FAILED") }
        }
        button("Mark current test situation") {
            val labels = arrayOf("Foreground", "Background", "Screen off", "Battery optimized")
            AlertDialog.Builder(this).setTitle("Record test situation").setItems(labels) { _, index ->
                DiagnosticStore.record(this, "TEST_SITUATION", JSONObject().put("label", labels[index]))
            }.show()
        }
        status = text("")
        val chatsView = ScrollView(this).apply {
            addView(chatsLayout); visibility = if (showingSettings) android.view.View.GONE else android.view.View.VISIBLE
        }
        val settingsView = ScrollView(this).apply {
            addView(settingsLayout); visibility = if (showingSettings) android.view.View.VISIBLE else android.view.View.GONE
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, selected: Boolean, action: () -> Unit) {
            tabs.addView(Button(this).apply { text = label; isSelected = selected; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        tab("Chats", !showingSettings) {
            showingSettings = false; chatsView.visibility = android.view.View.VISIBLE; settingsView.visibility = android.view.View.GONE
        }
        tab("Settings", showingSettings) {
            showingSettings = true; chatsView.visibility = android.view.View.GONE; settingsView.visibility = android.view.View.VISIBLE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(tabs)
            addView(chatsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(settingsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(content)
        val roles = getSystemService(RoleManager::class.java)
        // The user requested ongoing saving for their already selected, permitted chat.
        if (!preferences.getBoolean("autoSavingChoiceMade", false) && hasPermission(Manifest.permission.READ_SMS)) {
            savedScope()?.let { ConversationSaving.start(this, it) }
        }
        // This migration fulfills the user's explicit request to keep their two imported chats saving.
        if (!preferences.getBoolean("multipleConversationChoiceMade", false)) {
            val imported = ChatHistoryStore.importedScopes(this)
            if (imported.size <= 2) imported.forEach { ConversationSaving.start(this, it) }
            preferences.edit().putBoolean("multipleConversationChoiceMade", true).apply()
        }
        if (savedInstanceState?.getBoolean("pendingHistory") == true) {
            pendingHistoryScope = savedScope()
        }
        DiagnosticStore.record(this, "DEVICE_CHECK", JSONObject()
            .put("smsRoleAvailable", roles.isRoleAvailable(RoleManager.ROLE_SMS))
            .put("androidApi", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL))
    }

    override fun onResume() {
        super.onResume()
        ConversationSaving.requestCheck(this)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingHistory", pendingHistoryScope != null)
        outState.putBoolean("showingSettings", showingSettings)
        pendingDocumentScopes?.let { scopes ->
            val saved = org.json.JSONArray()
            scopes.forEach { saved.put(JSONObject().put("name", it.name).put("number", it.number)
                .put("rawNumber", it.rawNumber).put("country", it.country)) }
            outState.putString("pendingDocumentScopes", saved.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        historyWorker.shutdownNow()
        super.onDestroy()
    }

    private fun saveContact(canonical: String, selectedName: String, rawNumber: String, country: String) {
        val updated = try { HistoryScope(selectedName, canonical, rawNumber, country) }
            catch (error: IllegalArgumentException) { HistoryScope(selectedName, canonical, canonical, country) }
        val alreadySaving = ConversationSaving.isEnabled(this, updated)
        ChatHistoryStore.revokeAssistantAccess(this)
        DiagnosticStore.preferences(this).edit().putString("testNumber", canonical)
            .putString("testName", selectedName).putString("testRawNumber", updated.rawNumber)
            .putString("testCountry", country).apply()
        if (alreadySaving) ConversationSaving.start(this, updated)
    }

    private fun savedScope(): HistoryScope? {
        val preferences = DiagnosticStore.preferences(this)
        val canonical = TestContact.normalizedNumber(preferences.getString("testNumber", "").orEmpty()) ?: return null
        return HistoryScope(preferences.getString("testName", "").orEmpty(), canonical,
            preferences.getString("testRawNumber", canonical).orEmpty(), preferences.getString("testCountry", "").orEmpty())
    }

    private fun selectedScope(): HistoryScope? {
        val scope = savedScope()
        if (scope == null || TestContact.normalizedNumber(number.text.toString()) != scope.number || name.text.toString().trim() != scope.name) {
            showHistoryMessage("Choose a contact, or save your changes first.")
            return null
        }
        return scope
    }

    private fun setHistoryBusy(busy: Boolean) {
        historyBusy = busy
        controls.forEach { it.isEnabled = !busy }
        number.isEnabled = !busy
        name.isEnabled = !busy
    }

    private fun showHistoryMessage(message: String) {
        if (!isDestroyed && !isFinishing) AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun runHistoryTask(label: String, action: () -> String) {
        if (historyBusy) return
        setHistoryBusy(true)
        historyStatus.text = "${label}..."
        historyWorker.execute {
            val message = try { action() } catch (error: Exception) {
                DiagnosticStore.record(this, "HISTORY_OPERATION_FAILED", JSONObject().put("errorType", error.javaClass.simpleName))
                if (error is SecurityException) "Android did not allow SMS access. Check this app's SMS permission in Settings, or import a conversation export file."
                else "History could not be imported or shared. Check the selected file or permission and try again."
            }
            handler.post {
                if (!isDestroyed && !isFinishing) {
                    setHistoryBusy(false)
                    updateStatus()
                    showHistoryMessage(message)
                }
            }
        }
    }

    private fun importSms(scope: HistoryScope) = runHistoryTask("Reading selected SMS history") {
        val count = ChatHistoryStore.importSms(this, scope)
        "$count saved SMS messages imported for this contact. RCS and MMS are excluded. " +
            "Tap Share saved chats with assistant and choose the chats to share."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 105) {
            val scope = pendingHistoryScope
            pendingHistoryScope = null
            setHistoryBusy(false)
            if (hasPermission(Manifest.permission.READ_SMS) && scope != null) importSms(scope)
            else {
                DiagnosticStore.record(this, "SMS_HISTORY_PERMISSION_DENIED")
                showHistoryMessage("SMS access was not granted. You can choose a conversation export file instead.")
            }
        }
    }

    private fun requestSmsRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
            DiagnosticStore.record(this, "SMS_ROLE_UNAVAILABLE")
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS), 101)
    }

    @Deprecated("Platform callback retained for this disposable test activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 107) {
            val chosen = pendingDocumentScopes
            pendingDocumentScopes = null
            if (resultCode == RESULT_OK && data?.data != null && chosen != null) {
                try {
                    AutoChatDocument.connect(this, requireNotNull(data.data), data.flags, chosen)
                    showHistoryMessage("Automatic updates connected. Google Drive must also be connected in ChatGPT. " +
                        "Use the current JobSense-work-chats.txt file for job questions; cloud freshness still needs verification.")
                } catch (error: Exception) {
                    showHistoryMessage("Choose a writable Google Drive document. The selected location did not provide " +
                        "persistent Drive access. Nothing was enabled; try connecting again.")
                }
            }
            return
        }
        if (requestCode == 106) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val destination = requireNotNull(data.data)
                val file = ChatDocument.latest(this)
                if (file != null) runHistoryTask("Saving history file") {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: throw java.io.IOException("The file could not be saved")
                    "History file saved. Attach it to your ChatGPT chat or project's sources on this phone."
                }
            }
            return
        }
        if (requestCode == 101) {
            val held = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
            DiagnosticStore.record(this, if (held) "SMS_ROLE_GRANTED" else "SMS_ROLE_NOT_GRANTED")
        }
        if (requestCode == 103 && resultCode == RESULT_OK) {
            val selectedUri = data?.data ?: return
            try {
                contentResolver.query(selectedUri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val selectedName = cursor.getString(0).orEmpty()
                    val rawNumber = cursor.getString(1).orEmpty()
                    val canonical = TestContact.normalizedNumber(cursor.getString(2).orEmpty())
                        ?: TestContact.normalizedNumber(rawNumber)
                        ?: PhoneNumberUtils.formatNumberToE164(rawNumber, Locale.getDefault().country)
                    name.setText(selectedName)
                    number.setText(canonical ?: rawNumber)
                    if (canonical == null) {
                        number.error = "Add the country code, then save this contact"
                        return
                    }
                    saveContact(canonical, selectedName.trim(), rawNumber, Locale.getDefault().country)
                    DiagnosticStore.record(this, "TEST_CONTACT_PICKED")
                    MessageNotificationListener.refreshTestNotification(this)
                    updateStatus()
                }
            } catch (error: SecurityException) {
                DiagnosticStore.record(this, "CONTACT_PICKER_ACCESS_DENIED")
            }
        }
        if (requestCode == 104) {
            val scope = pendingHistoryScope
            pendingHistoryScope = null
            val selectedUri = data?.data
            if (resultCode == RESULT_OK && selectedUri != null && scope != null) {
                runHistoryTask("Reading selected conversation export") {
                    val bytes = ChatHistoryStore.importDocument(this, scope, selectedUri)
                    "$bytes bytes imported from the chosen file. Participants and completeness are unverified. " +
                        "Tap Share saved chats with assistant and choose the chats to share."
                }
            }
        }
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun updateStatus() {
        autoDocumentStatus.text = AutoChatDocument.status(this)
        laptopStatus.text = WirelessSharing.status(this)
        if (!historyBusy) {
            val scope = savedScope()
            historyStatus.text = if (scope == null) "No contact selected." else {
                val history = ChatHistoryStore.summary(this, scope)
                val ledger = ConversationLedger.summary(this, scope)
                "Selected: ${scope.name.ifBlank { "saved number" }}\n" +
                    "Chats being saved: ${ConversationSaving.scopes(this).joinToString { it.name.ifBlank { "saved number" } }}\n" +
                    "Saved SMS imported: ${history.optInt("smsCount")}\n" +
                    "Keep saving new messages: ${ledger.optBoolean("automaticSaving")}\n" +
                    "SMS records saved: ${ledger.optInt("smsEvidenceCount")}\n" +
                    "Messages saved from notifications: ${ledger.optInt("notificationEvidenceCount")}\n" +
                    "Conversation export imported: ${history.optBoolean("hasDocumentImport")}\n" +
                    "Assistant shared snapshot ready: ${history.optBoolean("assistantAccessEnabled")}\n" +
                    "Chats in shared assistant copy: ${ChatHistoryStore.sharedConversationCount(this)}\n" +
                    "RCS/MMS coverage is partial. Use Share saved chats with assistant again for newer saved records."
            }
        }
        val roles = getSystemService(RoleManager::class.java)
        status.text = "SMS role available: ${roles.isRoleAvailable(RoleManager.ROLE_SMS)}\n" +
            "SMS role held: ${roles.isRoleHeld(RoleManager.ROLE_SMS)}\n" +
            "Precise location: ${hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)}\n" +
            "Background location: ${hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}\n\n" +
            DiagnosticStore.summary(this).toString(2)
    }

    private fun geofenceIntent(): PendingIntent = PendingIntent.getBroadcast(this, 0,
        Intent(this, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @SuppressLint("MissingPermission")
    private fun registerGeofence() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            DiagnosticStore.record(this, "GEOFENCE_PERMISSION_MISSING")
            return
        }
        val cancellation = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (location == null) {
                    DiagnosticStore.record(this, "CURRENT_LOCATION_UNAVAILABLE")
                    return@addOnSuccessListener
                }
                val geofence = Geofence.Builder().setRequestId("jobsense-phase0")
                    .setCircularRegion(location.latitude, location.longitude, 150f)
                    .setExpirationDuration(30 * 60 * 1000L).setLoiteringDelay(30_000)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT).build()
                val request = GeofencingRequest.Builder().addGeofence(geofence)
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).build()
                LocationServices.getGeofencingClient(this).addGeofences(request, geofenceIntent())
                    .addOnSuccessListener { DiagnosticStore.record(this, "GEOFENCE_REGISTERED",
                        JSONObject().put("radiusMeters", 150).put("expiresInMinutes", 30)) }
                    .addOnFailureListener { DiagnosticStore.record(this, "GEOFENCE_REGISTER_FAILED",
                        JSONObject().put("errorType", it.javaClass.simpleName)) }
            }
            .addOnFailureListener { DiagnosticStore.record(this, "CURRENT_LOCATION_FAILED") }
    }
}
