package com.jobsense.feasibility

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.delay

class ArchiveActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[ArchiveViewModel::class.java] }
    private val assistant by lazy { ViewModelProvider(this)[AssistantViewModel::class.java] }
    private val appUpdates by lazy { ViewModelProvider(this)[AppUpdateViewModel::class.java] }
    private var voiceStatus by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var voiceSession by mutableStateOf(false)
    private var sessionAnswerPending=false
    private val sessionTimer=android.os.Handler(android.os.Looper.getMainLooper())
    private val voiceTimer = android.os.Handler(android.os.Looper.getMainLooper())
    private var speech: android.speech.SpeechRecognizer? = null
    private var playback: android.speech.tts.TextToSpeech? = null
    private var playbackGeneration=0
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) startVoice() else { endVoiceSession();voiceStatus="Microphone permission was not granted. You can type your question." }
    }
    private var pickerRequested by mutableStateOf("")
    private var pendingPicker = ""
    private val messagePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ArchiveObservers.start(this); ArchiveWork.schedule(this); model.reload()
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            model.notice("Message access was not granted. You can allow it in Android app settings.")
    }
    private val contactPermissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.loadContacts(); pickerRequested = "contacts" }
        else model.notice("Contact access was not granted. Allow it in Android app settings to select several contacts.")
        pendingPicker = ""
    }
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) { model.notice("Drive connection was canceled. Your history stays saved on this phone."); return@registerForActivityResult }
        try {
            val value = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(requireNotNull(result.data))
            if (DriveArchive.SCOPE !in value.grantedScopes || value.accessToken.isNullOrBlank()) throw DriveNeedsConsent()
            model.operation("Drive connected. Your chosen chats and attachments will upload automatically.") {
                DriveArchive.acceptToken(this, requireNotNull(value.accessToken))
                HistoryConnection.select(this, HistoryConnection.DRIVE)
            }
        } catch (error: Exception) { model.notice("Google did not complete the permission. Try connecting Drive again.") }
    }
    private val recoveryDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.operation("Recovered histories imported. Coverage notes remain attached to each conversation.") {
            contentResolver.openInputStream(uri)?.use { ArchiveRecoveryImporter.import(this, it) }
                ?: throw java.io.IOException("Recovery file unavailable")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPicker = savedInstanceState?.getString("pendingPicker").orEmpty()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val assistantState by assistant.state.collectAsStateWithLifecycle()
            val updateState by appUpdates.state.collectAsStateWithLifecycle()
            LaunchedEffect(assistantState.busy,assistantState.answer,assistantState.error,voiceSession) {
                if(voiceSession && sessionAnswerPending && !assistantState.busy) {
                    sessionAnswerPending=false
                    if(assistantState.answer.isNotBlank()) speakAnswer(assistantState.answer,true)
                    else { endVoiceSession();voiceStatus="Voice session stopped. Review the question or error on screen." }
                }
            }
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { model.reload(); delay(5000) } }
            }
            JobSenseTheme(state.appearance) {
                ArchiveShell(state, pickerRequested, {
                    pickerRequested = ""
                }, ArchiveActions(
                    openChatGpt = { openUrl("https://chatgpt.com/") },
                    permissions = { requestMessageAccess() },
                    androidSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    pickContacts = { chooseContacts() },
                    pickGroups = {
                        if (state.canReadMessages) { model.loadGroups(); pickerRequested = "groups" }
                        else requestMessageAccess()
                    },
                    addContacts = model::addContacts, addGroups = model::addGroups,
                    selectChat = model::selectChat, searchMessages = model::searchMessages, olderMessages = model::olderMessages,
                    saving = { chat, enabled -> model.configure(chat, saving = enabled) },
                    sharing = { chat, enabled -> model.configure(chat, sharing = enabled) },
                    rename = { chat, name -> model.configure(chat, name = name) },
                    refresh = { ArchiveWork.request(this); DriveArchive.requestSync(this); model.reload() },
                    connectDrive = { connectDrive() },
                    mode = model::chooseMode, appearance = { model.preference("archiveAppearance", it) }, mobileData = model::mobileData,
                    diagnostics = { startActivity(Intent(this, DiagnosticActivity::class.java)) },
                    importRecovery = {
                        val staged = java.io.File(filesDir, "archive/pending-recovery.json")
                        if (staged.isFile) model.operation("Recovered work histories imported.") { staged.inputStream().use { ArchiveRecoveryImporter.import(this, it) } }
                        else recoveryDocument.launch(arrayOf("application/json", "text/plain"))
                    },
                    openFile = { openUrl(it) },
                    checkForAppUpdate = appUpdates::check,
                    downloadAppUpdate = appUpdates::download,
                    cancelAppUpdate = appUpdates::cancelDownload,
                    installAppUpdate = { installAppUpdate() },
                    allowMeteredAppUpdates = appUpdates::setAllowMetered,
                    openAttachment = { attachment ->
                        try {
                            val file = ArchiveStore.file(this, attachment.path)
                            val uri = FileProvider.getUriForFile(this, "$packageName.chatfiles", file)
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (error: Exception) { model.notice("This attachment is unavailable or no installed app can open its format.") }
                    }, clearNotice = model::clearNotice
                ), assistantState, AssistantActions(question=assistant::question,scope=assistant::scope,ask=assistant::ask,stopAnswer=assistant::stopAnswer,refresh=assistant::refresh,
                    model=assistant::model,download=assistant::download,cancelDownload=assistant::cancelDownload,rate=assistant::saveRate,
                    shift=assistant::saveShift,assignment=assistant::saveAssignment,equipment=assistant::saveEquipment,speakQuestion={requestVoice()},finishListening={speech?.stopListening();voiceStatus="Finishing transcription…"},stopListening={if(voiceSession) endVoiceSession() else stopVoice()},isListening=listening,
                    speakAnswer={speakAnswer(it)},stopSpeaking={endVoiceSession();playback?.stop()},voiceStatus=voiceStatus,
                    startVoiceSession={requestVoiceSession()},endVoiceSession={endVoiceSession()},voiceSession=voiceSession), updateState)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        appUpdates.refreshInstallStatus()
    }

    private fun installAppUpdate() {
        val apk = AppUpdates.readyApk(this)
        if (apk == null) {
            appUpdates.reportStatus("The verified update file is no longer available. Check for updates and download it again.")
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            appUpdates.reportStatus("Allow this app to install the update in Android Settings, then return and tap Install update.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.chatfiles", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
            appUpdates.installPending()
        } catch (error: Exception) {
            appUpdates.reportStatus("Android could not open the installer. ${error.message ?: "Try again after checking app installation settings."}")
        }
    }
    private fun requestMessageAccess() {
        android.app.AlertDialog.Builder(this).setTitle("Save your chosen work messages")
            .setMessage("Android grants message access. JobSense saves only the contacts and groups you choose. Google Messages stays your texting app.")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                messagePermissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH))
            }.show()
    }
    private fun requestVoice() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startVoice()
        else android.app.AlertDialog.Builder(this).setTitle("Speak a question")
            .setMessage("Allow the microphone for a short on-device transcription. Review its text before asking. No audio recording is saved.")
            .setNegativeButton("Cancel") { _,_->endVoiceSession() }.setOnCancelListener {endVoiceSession()}.setPositiveButton("Continue") { _,_->microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }.show()
    }
    private fun startVoice() {
        stopVoice()
        if(android.os.Build.VERSION.SDK_INT<31 || !android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            endVoiceSession();voiceStatus="On-device speech is unavailable. Download offline speech support in Android or type your question."; return
        }
        try { speech=android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object: android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if(speech===recognizer) voiceStatus="Listening on this phone…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if(speech===recognizer) voiceStatus="Finishing transcription…" }
                override fun onError(error: Int) { if(speech!==recognizer) return; endVoiceSession();voiceStatus="Voice did not finish. Retry or type your question."; speech?.destroy(); speech=null;listening=false;voiceTimer.removeCallbacksAndMessages(null) }
                override fun onResults(results: Bundle?) {
                    if(speech!==recognizer) return
                    val text=results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if(!text.isNullOrBlank()) {
                        assistant.question(text)
                        if(voiceSession) { sessionAnswerPending=true;assistant.ask();voiceStatus="Answering on this phone…" }
                        else voiceStatus="Transcript ready. Review it, then tap Ask."
                    }
                    else { endVoiceSession();voiceStatus="No speech recognized. Retry or type your question." }
                    speech?.destroy(); speech=null;listening=false;voiceTimer.removeCallbacksAndMessages(null)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int,params: Bundle?) {}
            })
            recognizer.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,"en-US").putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
        } } catch (_: Exception) { endVoiceSession();voiceStatus="On-device speech could not start. Type your question or try again.";return }
        voiceStatus="Starting on-device listening…"
        listening=true
        voiceTimer.postDelayed({ if(speech!=null) { speech?.stopListening();voiceStatus="Finishing transcription…" } },30_000)
        voiceTimer.postDelayed({ if(speech!=null) { endVoiceSession();stopVoice();voiceStatus="Voice timed out. Retry or type your question." } },45_000)
    }
    private fun requestVoiceSession() {
        android.app.AlertDialog.Builder(this).setTitle("Start a voice session?")
            .setMessage("For up to 3 minutes, this screen will listen, ask your spoken questions using local data, and read answers aloud. Keep the app visible. No audio is saved and no messages or records are sent or saved automatically. Tap End voice session to stop.")
            .setNegativeButton("Cancel",null).setPositiveButton("Start") { _,_->
                voiceSession=true;sessionAnswerPending=false
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                sessionTimer.postDelayed({endVoiceSession();voiceStatus="Voice session ended after 3 minutes."},180_000)
                requestVoice()
            }.show()
    }
    private fun endVoiceSession() {
        playbackGeneration++
        voiceSession=false;sessionAnswerPending=false;sessionTimer.removeCallbacksAndMessages(null)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopVoice();playback?.stop()
    }
    private fun stopVoice() { voiceTimer.removeCallbacksAndMessages(null); speech?.cancel(); speech?.destroy(); speech=null; voiceStatus="";listening=false }
    private fun speakAnswer(text: String,resume: Boolean=false) {
        playback?.stop(); playback?.shutdown()
        val generation=++playbackGeneration
        playback=android.speech.tts.TextToSpeech(this) callback@{ status ->
            if(generation!=playbackGeneration || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || (resume && !voiceSession)) return@callback
            if(status==android.speech.tts.TextToSpeech.SUCCESS) {
                val localVoice=playback?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language=="en" }
                if(localVoice!=null) {
                    playback?.voice=localVoice
                    playback?.setOnUtteranceProgressListener(object: android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { sessionTimer.post { if(resume && voiceSession && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startVoice() } }
                        @Deprecated("Android legacy callback") override fun onError(id: String?) { sessionTimer.post {endVoiceSession();voiceStatus="Playback failed. Read the answer on screen."} }
                    })
                    playback?.speak(text.take(3500),android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"assistant-answer")
                }
                else { endVoiceSession();model.notice("No offline English playback voice is installed. Read the answer on screen.") }
            } else { endVoiceSession();model.notice("Speech playback is unavailable.") }
        }
    }
    override fun onStop() { endVoiceSession(); super.onStop() }
    override fun onDestroy() { speech?.destroy(); playback?.shutdown(); super.onDestroy() }
    private fun chooseContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            model.loadContacts(); pickerRequested = "contacts"
        } else {
            android.app.AlertDialog.Builder(this).setTitle("Choose multiple contacts")
                .setMessage("Allow contacts so JobSense can show names and numbers in its multi-select picker. No conversation is saved until you select it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                    pendingPicker = "contacts"; contactPermissions.launch(Manifest.permission.READ_CONTACTS)
                }.show()
        }
    }
    private fun connectDrive() {
        android.app.AlertDialog.Builder(this).setTitle("Connect your private Drive archive")
            .setMessage("Choose the same Google account connected to ChatGPT. JobSense will manage its own archive files and attachments. Your existing history file stays available during setup.")
            .setNegativeButton("Cancel", null).setPositiveButton("Connect Google account") { _, _ ->
                Identity.getAuthorizationClient(this).authorize(DriveArchive.authorization(this, selectAccount = true))
                    .addOnSuccessListener { result ->
                        if (result.hasResolution()) authorization.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())
                        else result.accessToken?.let { token -> model.operation { DriveArchive.acceptToken(this, token); HistoryConnection.select(this, HistoryConnection.DRIVE) } }
                    }.addOnFailureListener { model.notice("Google Drive connection needs one-time app setup. Your existing archive is still preserved.") }
            }.show()
    }
    private fun openUrl(value: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
        catch (error: android.content.ActivityNotFoundException) { model.notice("Install or open a browser to use this link.") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingPicker", pendingPicker); super.onSaveInstanceState(outState)
    }
}
