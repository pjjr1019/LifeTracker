package com.jobsense.feasibility

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.text.DateFormat
import java.util.Date

data class ArchiveActions(
    val openChatGpt: () -> Unit = {}, val permissions: () -> Unit = {}, val androidSettings: () -> Unit = {},
    val pickContacts: () -> Unit = {}, val pickGroups: () -> Unit = {},
    val addContacts: (Set<String>, Boolean) -> Unit = { _, _ -> }, val addGroups: (Set<Long>, Boolean) -> Unit = { _, _ -> },
    val selectChat: (String?) -> Unit = {}, val searchMessages: (String) -> Unit = {}, val olderMessages: () -> Unit = {},
    val saving: (ArchiveChat, Boolean) -> Unit = { _, _ -> }, val sharing: (ArchiveChat, Boolean) -> Unit = { _, _ -> },
    val rename: (ArchiveChat, String) -> Unit = { _, _ -> }, val refresh: () -> Unit = {}, val connectDrive: () -> Unit = {},
    val mode: (String) -> Unit = {}, val appearance: (String) -> Unit = {}, val mobileData: (Boolean) -> Unit = {},
    val diagnostics: () -> Unit = {}, val importRecovery: () -> Unit = {}, val openFile: (String) -> Unit = {}, val openAttachment: (ArchiveAttachment) -> Unit = {}, val clearNotice: () -> Unit = {},
    val checkForAppUpdate: () -> Unit = {}, val downloadAppUpdate: () -> Unit = {},
    val cancelAppUpdate: () -> Unit = {}, val installAppUpdate: () -> Unit = {}, val allowMeteredAppUpdates: (Boolean) -> Unit = {})

private val LightColors = lightColorScheme(primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF0F172A),
    onBackground = Color(0xFF0F172A), onSurfaceVariant = Color(0xFF475569), outlineVariant = Color(0xFFE2E8F0))
private val DarkColors = darkColorScheme(primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF134E4A),
    primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1220), surface = Color(0xFF172033), onSurface = Color(0xFFF1F5F9),
    onBackground = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFFCBD5E1), outlineVariant = Color(0xFF334155))

@Composable fun JobSenseTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        typography = Typography(headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ArchiveShell(state: ArchiveUiState, requestedPicker: String = "", consumePicker: () -> Unit = {}, actions: ArchiveActions = ArchiveActions(),
    assistantState: AssistantUiState = AssistantUiState(), assistantActions: AssistantActions = AssistantActions(),
    updateState: AppUpdateState = AppUpdateState()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var picker by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var pickerSearch by rememberSaveable { mutableStateOf("") }
    var shareNew by rememberSaveable { mutableStateOf(true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(requestedPicker) { if (requestedPicker.isNotBlank()) { picker = requestedPicker; selected = ""; pickerSearch = ""; consumePicker() } }
    LaunchedEffect(state.notice) { if (state.notice.isNotBlank()) { snackbar.showSnackbar(state.notice); actions.clearNotice() } }
    val chat = state.chats.firstOrNull { it.id == state.selectedChat }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = {
                Column {
                    if (chat != null || tab != 0) Text("LifeTracker-AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(chat?.name ?: listOf("LifeTracker-AI", "Work chats", "Assistant", "Settings")[tab], fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            },
                navigationIcon = { if (chat != null) IconButton(onClick = { actions.selectChat(null) }) { Icon(Icons.Default.ArrowBack, "Back to chats") } },
                actions = { if (chat == null && tab == 1) IconButton(onClick = { picker = "add" }) { Icon(Icons.Default.Add, "Add chats") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            if (chat == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Home" to Icons.Default.Home, "Chats" to Icons.Default.List, "Assistant" to Icons.Default.Search, "Settings" to Icons.Default.Settings).forEachIndexed { index, entry ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(entry.second, null) }, label = { Text(entry.first) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        }) { padding ->
        if (state.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (chat != null) ChatDetail(state, chat, actions, Modifier.padding(padding))
        else when (tab) {
            0 -> HomeScreen(state, actions, { tab = 1 }, { picker = "add" }, { tab = 2 }, Modifier.padding(padding))
            1 -> ChatList(state, actions, { picker = "add" }, Modifier.padding(padding))
            2 -> AssistantPanel(state, assistantState, assistantActions, Modifier.padding(padding))
            else -> SettingsScreen(state, actions, advanced, { advanced = !advanced }, updateState, Modifier.padding(padding))
        }
    }
    if (picker == "add") ModalBottomSheet(onDismissRequest = { picker = "" }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add work conversations", style = MaterialTheme.typography.headlineSmall)
            Text("Choose several contacts or groups and import their available history together.")
            Button(onClick = { picker = ""; actions.pickContacts() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose contacts") }
            OutlinedButton(onClick = { picker = ""; actions.pickGroups() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose work groups") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (picker == "contacts" || picker == "groups") ModalBottomSheet(onDismissRequest = { picker = "" }) {
        val checked = selected.split('|').filter { it.isNotBlank() }.toSet()
        val shownContacts = state.contacts.filter { it.scope.name.contains(pickerSearch, true) || it.scope.number.contains(pickerSearch) }
        val shownGroups = state.groups.filter { it.participants.joinToString().contains(pickerSearch, true) || it.id.toString().contains(pickerSearch) }
        val shownIds = if (picker == "contacts") shownContacts.map { it.scope.key }.toSet() else shownGroups.map { it.id.toString() }.toSet()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (picker == "contacts") "Choose contacts" else "Choose work groups", style = MaterialTheme.typography.headlineSmall)
            Text("Only checked conversations will be added. Existing chats keep saving.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = pickerSearch, onValueChange = { pickerSearch = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = (checked + shownIds).joinToString("|") }, enabled = shownIds.isNotEmpty() && !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (pickerSearch.isBlank()) "Select all" else "Select all shown") }
                TextButton(onClick = { selected = "" }, enabled = checked.isNotEmpty() && !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Clear selection") }
            }
            Text("${checked.size} selected · Imports available sent and received message history", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 80.dp, max = 330.dp)) {
                if (picker == "contacts") {
                    val candidates = shownContacts
                    if (candidates.isEmpty() && !state.busy) item { Text("No contacts found. Check contact access or try another search.", Modifier.padding(vertical = 16.dp)) }
                    items(candidates, key = { it.scope.key }) { candidate ->
                        PickerRow(candidate.scope.name, candidate.scope.number, candidate.scope.key in checked) {
                            selected = (if (candidate.scope.key in checked) checked - candidate.scope.key else checked + candidate.scope.key).joinToString("|")
                        }
                    }
                } else {
                    val groups = shownGroups
                    if (groups.isEmpty() && !state.busy) item { Text("No saved SMS/MMS groups found. Older RCS-only groups need history recovery.", Modifier.padding(vertical = 16.dp)) }
                    items(groups, key = { it.id }) { group ->
                        val id = group.id.toString()
                        PickerRow("Work group ${group.id}", group.participants.joinToString(", "), id in checked) {
                            selected = (if (id in checked) checked - id else checked + id).joinToString("|")
                        }
                    }
                }
            }
            SettingSwitch("Share with ChatGPT", "Include selected chats in your private Drive archive", shareNew) { shareNew = it }
            Button(onClick = {
                if (picker == "contacts") actions.addContacts(checked, shareNew) else actions.addGroups(checked.mapNotNull { it.toLongOrNull() }.toSet(), shareNew)
                picker = ""; tab = 1
            }, enabled = checked.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Add ${checked.size} ${if (checked.size == 1) "chat" else "chats"} & import history") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun HomeScreen(state: ArchiveUiState, actions: ArchiveActions, chats: () -> Unit, add: () -> Unit, assistant: () -> Unit, modifier: Modifier) {
    val saving = state.chats.count { it.saving }; val shared = state.chats.count { it.sharing }
    var details by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Your work, in one place.", style = MaterialTheme.typography.titleLarge) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("YOUR MESSAGES", style = MaterialTheme.typography.labelLarge) }
                    Text(when { !state.canReadMessages || !state.canReceiveMessages -> "Allow message access"
                        saving == 0 -> "Choose your work chats"
                        state.captureError.isNotBlank() -> "History needs attention"
                        state.uploadError.isNotBlank() -> "Drive needs attention"
                        !state.online -> "Saved on your phone"
                        state.driveConnected && state.status.uploadedRevision < state.status.revision -> "Saving & updating Drive"
                        state.driveConnected -> "Your archive is connected"
                        state.legacyConnected -> "Your existing Drive file is connected"
                        else -> "Saving your conversations" }, style = MaterialTheme.typography.headlineSmall)
                    Text("$saving ${if (saving == 1) "chat" else "chats"} saving · $shared chosen for ChatGPT")
                    StatusLine("Latest saved message", timeLabel(state.status.latestMessage))
                    StatusLine("Last Drive update", timeLabel(state.status.uploadedAt))
                    if(state.status.attachmentsWaitingForUpload > 0) Text("Photos and files are still uploading",style=MaterialTheme.typography.bodySmall)
                    if(state.chats.any { it.coverage.isNotBlank() } || state.status.unavailableAttachments>0)
                        Text("Some older history or files need checking",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={details=!details},contentPadding=PaddingValues(0.dp)) { Text(if(details) "Hide archive details" else "Archive details") }
                    if(details) {
                    HorizontalDivider()
                    StatusLine("Last history check", timeLabel(state.chats.filter { it.saving }.minOfOrNull { it.lastCheck } ?: 0))
                    StatusLine("Confirmed archive upload", timeLabel(state.status.uploadedAt))
                    StatusLine("Saved revision", state.status.revision.toString())
                    StatusLine("Uploaded revision", if (state.status.uploadedRevision < 0) "Not confirmed yet" else state.status.uploadedRevision.toString())
                    if (state.status.attachmentsWaitingForUpload > 0) Text("${state.status.attachmentsWaitingForUpload} attachments waiting to upload")
                    if (state.status.attachmentsWaitingForDownload > 0) Text("${state.status.attachmentsWaitingForDownload} attachments waiting to save")
                    if (state.status.unavailableAttachments > 0) Text("${state.status.unavailableAttachments} attachments unavailable on this phone")
                    }
                    if (!state.online) Text("Uploads resume automatically when you’re online.")
                }
            }
        }
        if (!state.canReadMessages || !state.canReceiveMessages) item { NoticeCard("Enable message saving", "Allow Android message access for your chosen chats.", "Allow access", actions.permissions) }
        if (state.captureError.isNotBlank()) item { NoticeCard("Message saving needs attention", state.captureError, "Check history now", actions.refresh) }
        if (state.uploadError.isNotBlank()) item {
            val needsConsent = state.uploadError.startsWith("Reconnect Google Drive")
            NoticeCard("Upload needs attention", state.uploadError, if (needsConsent) "Reconnect Drive" else "Try upload again",
                if (needsConsent) actions.connectDrive else actions.refresh)
        }
        if (state.chats.isEmpty()) item { Button(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add your work chats") } }
        else item {
            Button(onClick = assistant, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Default.Search,null); Spacer(Modifier.width(8.dp)); Text("Ask about work") }
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=chats,modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("Saved chats") }
                OutlinedButton(onClick=actions.openChatGpt,modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("Open ChatGPT") }
            }
        }
        if (!state.driveConnected) item { NoticeCard("Finish automatic sharing", "Connect JobSense to Drive to upload groups and attachments directly. Your existing file stays available during setup.", "Connect Google Drive", actions.connectDrive) }
        if (details && state.chats.any { it.coverage.isNotBlank() }) item {
            NoticeCard("Older history still needs checking", "Saved texts are available. Older RCS messages and missing attachments are not assumed complete.", "View chats", chats)
        }
    }
}

@Composable private fun ChatList(state: ArchiveUiState, actions: ArchiveActions, add: () -> Unit, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    val chats = state.chats.filter { it.name.contains(search, true) || it.number.contains(search) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Search work chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(onClick = add, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add contacts or groups") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (chats.isEmpty()) Text(if (state.chats.isEmpty()) "Choose your boss, coworkers, and work groups to start saving their history." else "No matching chats.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(chats, key = { it.id }) { chat ->
                OutlinedCard(onClick = { actions.selectChat(chat.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(chat.name)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chat.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${chat.messageCount} saved · ${if (chat.kind == "GROUP") "Group" else "Contact"}", style = MaterialTheme.typography.bodySmall)
                            Text(if (chat.latestMessage == 0L) "History import pending" else timeLabel(chat.latestMessage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${if (chat.saving) "Saving" else "Paused"} · ${if (chat.sharing) "Shared with ChatGPT" else "Phone only"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.Default.Info, "Open conversation details")
                    }
                }
            }
        }
    }
}

@Composable private fun ChatDetail(state: ArchiveUiState, chat: ArchiveChat, actions: ArchiveActions, modifier: Modifier) {
    var rename by rememberSaveable(chat.id) { mutableStateOf(false) }
    var newName by rememberSaveable(chat.id) { mutableStateOf(chat.name) }
    var stopSharing by rememberSaveable(chat.id) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedCard {
                Column(Modifier.padding(16.dp)) {
                    SettingSwitch("Save messages", "Keep saving this conversation in the background", chat.saving) { actions.saving(chat, it) }
                    SettingSwitch("Share with ChatGPT", "Include in your private Drive archive", chat.sharing) { if (!it) stopSharing = true else actions.sharing(chat, true) }
                    TextButton(onClick = { rename = true }) { Text("Rename conversation") }
                }
            }
        }
        if (chat.coverage.isNotBlank()) item { Text("History coverage: ${chat.coverage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(value = state.messageSearch, onValueChange = actions.searchMessages, label = { Text("Search this history") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth()) }
        item { Text("Newest messages first", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.messages.isEmpty()) item { Text("No saved messages match. History imports run automatically; older RCS may need recovery.", Modifier.padding(vertical = 20.dp)) }
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            fun dateLabel(at: Long) = if (at > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)) else "Original date unavailable"
            val date = dateLabel(message.at)
            if (index == 0 || dateLabel(state.messages[index - 1].at) != date)
                Text(date, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val outgoing = message.direction == "OUTGOING_SENT"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                Card(modifier = Modifier.fillMaxWidth(0.92f), colors = CardDefaults.cardColors(containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (outgoing) "You" else if (message.direction == "INCOMING") if (chat.kind == "GROUP") message.sender.ifBlank { "Group member" } else chat.name else "${message.direction.lowercase().replace('_', ' ')} · ${message.source}", style = MaterialTheme.typography.labelMedium)
                        if (message.body.isNotEmpty()) Text(message.body, style = MaterialTheme.typography.bodyLarge)
                        message.attachments.forEach { attachment ->
                            OutlinedCard(onClick = { actions.openAttachment(attachment) }, enabled = attachment.path.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    if (attachment.mime.startsWith("image/") && attachment.path.isNotBlank()) AttachmentThumbnail(attachment)
                                    Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${attachment.mime} · ${attachment.state.lowercase()}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text(if (message.at > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.at)) else "Imported evidence · original time unknown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.moreMessages) item { OutlinedButton(onClick = actions.olderMessages, modifier = Modifier.fillMaxWidth()) { Text("Load older messages") } }
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename conversation") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { actions.rename(chat, newName); rename = false }, enabled = newName.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (stopSharing) AlertDialog(onDismissRequest = { stopSharing = false }, title = { Text("Stop sharing this chat?") },
        text = { Text("It will be removed from the current Drive archive after the next successful upload. Its saved history stays on your phone. Copies already read by ChatGPT remain in those chats.") },
        confirmButton = { TextButton(onClick = { actions.sharing(chat, false); stopSharing = false }) { Text("Stop sharing") } },
        dismissButton = { TextButton(onClick = { stopSharing = false }) { Text("Cancel") } })
}

@Composable private fun SettingsScreen(state: ArchiveUiState, actions: ArchiveActions, advanced: Boolean, toggleAdvanced: () -> Unit,
    updateState: AppUpdateState, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("App updates") }
        item {
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Wireless updates", style = MaterialTheme.typography.titleMedium)
                Text("Check a trusted HTTPS update feed, review its notes, then download and approve installation through Android. Your app data stays in place during a compatible in-app update.", style = MaterialTheme.typography.bodySmall)
                Text("Update feed: GitHub releases. APKs must match this installed app's identity and signing certificate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Installed version: ${updateState.currentVersion.ifBlank { "unknown" }}", style = MaterialTheme.typography.labelMedium)
                Text(updateState.status, style = MaterialTheme.typography.bodyMedium)
                if (updateState.downloading) {
                    if (updateState.progress != null) LinearProgressIndicator(progress = { updateState.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (updateState.offer != null) {
                    Text("Version ${updateState.offer.versionName}", style = MaterialTheme.typography.titleSmall)
                    Text(updateState.offer.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
                SettingSwitch("Allow mobile-data downloads", "When off, APK downloads wait for Wi-Fi.", updateState.allowMetered, actions.allowMeteredAppUpdates)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = actions.checkForAppUpdate,
                        enabled = !updateState.checking && !updateState.downloading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(if (updateState.checking) "Checking…" else "Check for updates") }
                    if (updateState.downloading) {
                        OutlinedButton(onClick = actions.cancelAppUpdate) { Text("Cancel") }
                    } else if (updateState.downloadedApk != null) {
                        Button(onClick = actions.installAppUpdate) { Text("Install update") }
                    } else if (updateState.offer != null) {
                        Button(onClick = actions.downloadAppUpdate, enabled = !updateState.checking) { Text("Download") }
                    }
                }
            } }
        }
        item { SectionTitle("ChatGPT connection") }
        item {
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                Text(state.account.ifBlank { if (state.legacyConnected) "Existing file connection preserved" else "Choose your Google account" }, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.connectDrive, modifier = Modifier.fillMaxWidth()) { Text(if (state.driveConnected) "Reconnect or change account" else "Connect automatic archive") }
                if (state.masterLink.isNotBlank()) TextButton(onClick = { actions.openFile(state.masterLink) }) { Text("Open work-history document") }
                if (state.indexLink.isNotBlank()) TextButton(onClick = { actions.openFile(state.indexLink) }) { Text("Open archive index") }
                if (state.masterLink.isNotBlank()) Text("Use this current source in ChatGPT. The old file remains available until replacement retrieval is verified.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            OutlinedCard { Column(Modifier.padding(16.dp)) {
                SettingSwitch("Automatic Drive sharing", "Phone saving continues when sharing is paused", state.mode == HistoryConnection.DRIVE) { actions.mode(if (it) HistoryConnection.DRIVE else HistoryConnection.LOCAL) }
                SettingSwitch("Use mobile data", "Upload texts and attachments without Wi-Fi", state.mobileData, actions.mobileData)
            } }
        }
        item { SectionTitle("Appearance") }
        item {
            OutlinedCard { Column(Modifier.padding(8.dp)) {
                listOf("system" to "Follow phone settings", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { actions.appearance(value) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.appearance == value, onClick = { actions.appearance(value) }); Text(label)
                    }
                }
            } }
        }
        item { SectionTitle("Message access") }
        item {
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.canReadMessages && state.canReceiveMessages) "Message permissions allowed" else "Message permission needs attention")
                Text("Use SMS/MMS for reliable future capture. Changing RCS settings does not recover older messages.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.permissions) { Text("Review message permissions") }
                TextButton(onClick = actions.androidSettings) { Text("Open Android app settings") }
            } }
        }
        item { TextButton(onClick = toggleAdvanced, modifier = Modifier.fillMaxWidth()) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") } }
        if (advanced) {
            item { OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Laptop connection", style = MaterialTheme.typography.titleMedium)
                Text("Requires your laptop to stay awake and online. The existing relay remains available in phone diagnostics.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.mode(HistoryConnection.LAPTOP) }) { Text("Use Laptop mode") }
                TextButton(onClick = actions.diagnostics) { Text("Open phone diagnostics") }
                TextButton(onClick = actions.importRecovery) { Text("Import recovered work histories") }
                TextButton(onClick = actions.refresh) { Text("Check history and retry uploads") }
            } } }
        }
    }
}

@Composable private fun PickerRow(title: String, subtitle: String, checked: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { toggle() }); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun SettingSwitch(title: String, subtitle: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(enabled, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable private fun NoticeCard(title: String, body: String, label: String, action: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = action) { Text(label) }
    } }
}
@Composable private fun Avatar(name: String) {
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().split(' ').filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString(""), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
@Composable private fun StatusLine(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.bodyMedium) } }
@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
private fun timeLabel(value: Long) = if (value == 0L) "Not yet" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

@Composable private fun AttachmentThumbnail(attachment: ArchiveAttachment) {
    val context = LocalContext.current
    var image by remember(attachment.path, attachment.sha256) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(attachment.path, attachment.sha256) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val file = ArchiveStore.file(context, attachment.path)
                val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, info)
                if (info.outWidth <= 0 || info.outHeight <= 0) null else {
                    var sample = 1
                    while (maxOf(info.outWidth, info.outHeight) / sample > 512) sample *= 2
                    android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
        image = decoded
    }
    image?.let { Image(it.asImageBitmap(), "Attachment preview: ${attachment.name}", Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
}

internal fun previewArchiveState(): ArchiveUiState {
    val now = 1791406800000L
    val scope = HistoryScope("Jordan · Supervisor", "+15551234567", "+15551234567", "US")
    val chat = ArchiveChat(scope.key, scope.name, "INDIVIDUAL", scope.number, scope.rawNumber, scope.country, -1, emptyList(), true, true,
        "Older RCS history has not been verified", now, now, 128)
    return ArchiveUiState(loading = false, chats = listOf(chat), canReadMessages = true, canReceiveMessages = true, online = true,
        driveConnected = true, mode = HistoryConnection.DRIVE, status = ArchiveStatus(12, 12, now, now, 0), account = "work@example.com")
}
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable private fun HomePreview() = JobSenseTheme("light") { ArchiveShell(previewArchiveState()) }
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable private fun DarkHomePreview() = JobSenseTheme("dark") { ArchiveShell(previewArchiveState()) }
