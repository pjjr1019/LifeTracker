package com.jobsense.feasibility

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AssistantActions(val question: (String)->Unit={}, val scope: (String,Boolean)->Unit={_,_->}, val ask: ()->Unit={}, val stopAnswer: ()->Unit={},
    val refresh: ()->Unit={}, val model: (Boolean)->Unit={}, val download: ()->Unit={}, val cancelDownload: ()->Unit={},
    val rate: (String)->Unit={}, val shift: (String,String,String,String,Boolean,String)->Unit={_,_,_,_,_,_->},
    val assignment: (String,String,String,String)->Unit={_,_,_,_->},
    val equipment: (String,String,String,String,String)->Unit={_,_,_,_,_->}, val speakQuestion: ()->Unit={}, val finishListening: ()->Unit={}, val stopListening: ()->Unit={}, val isListening: Boolean=false,
    val speakAnswer: (String)->Unit={}, val stopSpeaking: ()->Unit={}, val voiceStatus: String="",
    val startVoiceSession: ()->Unit={},val endVoiceSession: ()->Unit={},val voiceSession: Boolean=false)

@Composable fun AssistantPanel(archive: ArchiveUiState, state: AssistantUiState, actions: AssistantActions, modifier: Modifier=Modifier) {
    val latestActions by rememberUpdatedState(actions)
    DisposableEffect(Unit) { onDispose { if(latestActions.voiceSession) latestActions.endVoiceSession() } }
    var page by rememberSaveable { mutableStateOf(0) }
    var source by remember { mutableStateOf<AssistantEvidence?>(null) }
    var confirm by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("America/New_York")).toString()) }
    var start by rememberSaveable { mutableStateOf("") }; var end by rememberSaveable { mutableStateOf("") }
    var rest by rememberSaveable { mutableStateOf("0") }; var overnight by rememberSaveable { mutableStateOf(false) }
    var amendsId by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf(state.rate) }
    var asset by rememberSaveable { mutableStateOf("") }; var location by rememberSaveable { mutableStateOf("") }
    var handler by rememberSaveable { mutableStateOf("") }; var note by rememberSaveable { mutableStateOf("") }
    var jobDate by rememberSaveable { mutableStateOf(date) }; var address by rememberSaveable { mutableStateOf("") }; var task by rememberSaveable { mutableStateOf("") }
    var suggestionSource by rememberSaveable { mutableStateOf("") }
    var showScope by rememberSaveable { mutableStateOf(false) }
    val time = remember { DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a z").withZone(ZoneId.of("America/New_York")) }
    LaunchedEffect(Unit) { while(true) { actions.refresh(); delay(3000) } }
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp), verticalArrangement=Arrangement.spacedBy(14.dp), contentPadding=PaddingValues(bottom=24.dp)) {
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Ask","Work","Jobs","Equipment","AI setup").forEachIndexed { index,label -> FilterChip(selected=page==index,onClick={page=index},label={Text(label)}) }
        } }
        if(state.error.isNotBlank()) item { Text(state.error,color=MaterialTheme.colorScheme.error) }
        when(page) {
            0 -> {
                item { OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Ask about your saved work",style=MaterialTheme.typography.titleMedium)
                    Text("Phone only · ${if(state.useModel && state.modelReady) "Local AI excerpt selection" else "Evidence search"}. No cloud AI requests.")
                    TextButton(onClick={showScope=!showScope}) { Text("${state.scope.size} chats selected · ${if(showScope) "Hide" else "Change"}") }
                    if(showScope) {
                    Text("Assistant access only. Drive sharing stays separate.",style=MaterialTheme.typography.bodySmall)
                    archive.chats.forEach { chat -> Row {
                        Checkbox(checked=chat.id in state.scope,onCheckedChange={actions.scope(chat.id,it)},enabled=!state.busy)
                        Text(chat.name,Modifier.padding(top=14.dp))
                    } }
                    if(archive.chats.isEmpty()) Text("Add saved chats first from the Chats tab.")
                    }
                    OutlinedTextField(value=state.question,onValueChange=actions.question,label={Text("Your question")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5,enabled=!state.busy)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick=actions.ask,enabled=!state.busy && state.question.isNotBlank()) { Text("Ask") }
                        OutlinedButton(onClick=actions.speakQuestion,enabled=!state.busy) { Text("Speak") }
                    }
                    if(actions.voiceStatus.isNotBlank()) {
                        Text(actions.voiceStatus)
                        if(actions.isListening) Row { TextButton(onClick=actions.finishListening) { Text("Finish dictation") }; TextButton(onClick=actions.stopListening) { Text("Cancel") } }
                    }
                    Text("Voice text is shown for review. Tap Ask when it is correct. Audio is not saved.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=if(actions.voiceSession) actions.endVoiceSession else actions.startVoiceSession,enabled=!state.busy || actions.voiceSession) {
                        Text(if(actions.voiceSession) "End voice session" else "Start voice session")
                    }
                    if(actions.voiceSession) Text("Voice session active · phone screen must stay open · stops after 3 minutes",color=MaterialTheme.colorScheme.primary)
                    if(Regex("(?i)\\bi (just )?(left|dropped|picked up|returned)\\b").containsMatchIn(state.question))
                        TextButton(onClick={note=state.question;asset="";location="";suggestionSource="";page=3}) { Text("Review as equipment note") }
                    if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working on your phone…"); TextButton(onClick=actions.stopAnswer) { Text("Stop answer") } }
                } } }
                if(state.answer.isNotBlank()) item { OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(state.answer)
                    Text("History coverage: older RCS and missing attachments may be unavailable. Only saved evidence is searched.",style=MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick={actions.speakAnswer(state.answer)}) { Text("Read aloud") }; TextButton(onClick=actions.stopSpeaking) { Text("Stop speech") } }
                } } }
                itemsIndexed(state.evidence,key={_,e->e.id}) { index,e -> OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("[S${index+1}] ${e.contact}",style=MaterialTheme.typography.titleSmall)
                    Text("${time.format(Instant.ofEpochMilli(e.at))} · ${if(e.direction=="INCOMING") "Received" else "Sent / other"}",style=MaterialTheme.typography.bodySmall)
                    Text(e.body.take(300))
                    TextButton(onClick={source=e}) { Text("Read source message") }
                    val suggestions=WorkAssistantCore.suggestions(e,ZoneId.of("America/New_York"))
                    if(suggestions.address!=null) TextButton(onClick={
                        address=suggestions.address;jobDate=suggestions.date?.toString().orEmpty();task=e.body.take(1000);suggestionSource=e.id;page=2
                    }) { Text("Review suggested job") }
                    if(suggestions.equipment!=null) TextButton(onClick={
                        asset=suggestions.equipment;location="";handler="";note=e.body.take(1000);suggestionSource=e.id;page=3
                    }) { Text("Review equipment mention") }
                } } }
            }
            1 -> {
                item { Text("Confirmed work",style=MaterialTheme.typography.titleLarge); Text(state.workSummary) }
                item { Text("Only shifts you review and confirm count here. Message mentions and GPS alone do not prove paid work.") }
                item { OutlinedTextField(rate,{rate=it},label={Text("Hourly rate (optional)")},modifier=Modifier.fillMaxWidth()); TextButton(onClick={actions.rate(rate)},enabled=!state.busy) { Text("Save rate") } }
                item { Text(if(amendsId.isBlank()) "Add a reviewed shift · Eastern time" else "Correct a saved shift · original retained",style=MaterialTheme.typography.titleMedium)
                    if(amendsId.isNotBlank()) TextButton(onClick={amendsId=""}) { Text("Cancel correction") }
                }
                item { OutlinedTextField(date,{date=it},label={Text("Date · YYYY-MM-DD")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(start,{start=it},label={Text("Start · HH:mm (24-hour)")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(end,{end=it},label={Text("End · HH:mm (24-hour)")},modifier=Modifier.fillMaxWidth()) }
                item { Row { Checkbox(overnight,{overnight=it}); Text("Ends the following day",Modifier.padding(top=14.dp)) } }
                item { OutlinedTextField(rest,{rest=it},label={Text("Unpaid break minutes")},modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={confirm="shift"},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Review shift") } }
                itemsIndexed(state.shifts,key={_,r->r.getString("id")}) { _,r -> OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${time.format(Instant.parse(r.getString("start")))} → ${time.format(Instant.parse(r.getString("end")))}")
                    Text("${r.getInt("breakMinutes")} unpaid minutes · user confirmed",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={
                        val from=Instant.parse(r.getString("start")).atZone(ZoneId.of("America/New_York"))
                        val to=Instant.parse(r.getString("end")).atZone(ZoneId.of("America/New_York"))
                        date=from.toLocalDate().toString();start=from.toLocalTime().toString();end=to.toLocalTime().toString()
                        rest=r.getInt("breakMinutes").toString();overnight=to.toLocalDate()>from.toLocalDate();amendsId=r.getString("id")
                    },enabled=!state.busy) { Text("Correct shift") }
                } } }
            }
            2 -> {
                item { Text("Planned jobs",style=MaterialTheme.typography.titleLarge); Text("Keep reviewed addresses and instructions separate from confirmed work hours.") }
                if(suggestionSource.isNotBlank()) item { Text("Suggested from a saved message. Check the date and address before saving.");TextButton(onClick={suggestionSource=""}) { Text("Detach message evidence") } }
                item { OutlinedTextField(jobDate,{jobDate=it},label={Text("Job date · YYYY-MM-DD")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(address,{address=it.take(200)},label={Text("Job address")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(task,{task=it.take(1000)},label={Text("Instructions / task")},modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={confirm="job"},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Review planned job") } }
                itemsIndexed(state.assignments,key={_,r->r.getString("id")}) { _,r -> OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(r.getString("date"),style=MaterialTheme.typography.titleMedium);Text(r.getString("address"));Text(r.getString("task"))
                    Text("User-reviewed plan · completion unverified",style=MaterialTheme.typography.bodySmall)
                } } }
            }
            3 -> {
                item { Text("Equipment memory",style=MaterialTheme.typography.titleLarge); Text("Save confirmed observations. Instructions in texts are shown as evidence, not completed movements.") }
                if(suggestionSource.isNotBlank()) item { Text("A text mention does not establish where equipment is now. Enter a location you have confirmed.");TextButton(onClick={suggestionSource=""}) { Text("Detach message evidence") } }
                item { OutlinedTextField(asset,{asset=it.take(100)},label={Text("Equipment name and size")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(location,{location=it.take(200)},label={Text("Confirmed location")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(handler,{handler=it.take(100)},label={Text("Who has it (optional)")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(note,{note=it.take(1000)},label={Text("Note (optional)")},modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={confirm="equipment"},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("Review observation") } }
                itemsIndexed(state.equipment,key={_,r->r.getString("id")}) { _,r -> OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(r.getString("asset"),style=MaterialTheme.typography.titleMedium)
                    Text(r.getString("location")); Text("Confirmed ${time.format(Instant.parse(r.getString("time")))}",style=MaterialTheme.typography.bodySmall)
                    if(r.optString("handler").isNotBlank()) Text("Handler: ${r.getString("handler")}")
                    if(r.optString("note").isNotBlank()) Text(r.getString("note"))
                } } }
            }
            4 -> {
                item { Text("Local AI",style=MaterialTheme.typography.titleLarge); Text("Search works offline immediately. The experimental local model selects a message excerpt. Only quotations checked against the source are shown; broader answers are still being validated.") }
                item { OutlinedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(state.modelReady) "Model ready" else state.modelStatus,style=MaterialTheme.typography.titleMedium)
                    Text("SmolLM2 360M · approximately 374 MB · downloaded on Wi-Fi · no AI account or per-question fee")
                    if(!state.modelReady) Button(onClick=actions.download) { Text("Download local model") }
                    if(!state.modelReady) TextButton(onClick=actions.cancelDownload) { Text("Pause download") }
                    Row { Checkbox(state.useModel,actions.model,enabled=!state.busy); Text("Use local AI excerpts",Modifier.padding(top=14.dp)) }
                    Text("No cloud fallback. If the model cannot run, dated search results remain available. Phone memory, speed and battery checks are pending.",style=MaterialTheme.typography.bodySmall)
                } } }
                item { Text("Privacy",style=MaterialTheme.typography.titleMedium); Text("New work/equipment records are encrypted with this phone's Android key. Existing message archives remain in private app storage; full archive encryption is still pending. Assistant questions and voice recordings are not added to Drive.") }
                item { Text("Optional voice sessions listen, ask and read answers while this app stays visible. No background wake word. Every work or equipment record still needs confirmation.",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    source?.let { e -> AlertDialog(onDismissRequest={source=null},title={Text(e.contact)},text={
        LazyColumn { item { Text(time.format(Instant.ofEpochMilli(e.at))); Text(e.body); Text("${e.source}\nSource ID: ${e.id}",style=MaterialTheme.typography.bodySmall) } }
    },confirmButton={TextButton(onClick={source=null}) { Text("Close") }}) }
    if(confirm.isNotBlank()) AlertDialog(onDismissRequest={confirm=""},title={Text(when(confirm) { "shift"->"Confirm this shift";"job"->"Confirm planned job";else->"Confirm equipment observation" })},
        text={Text(if(confirm=="shift") "$date · $start → $end${if(overnight) " next day" else ""}\n$rest unpaid minutes\nOnly confirm actual completed work."
            else if(confirm=="job") "$jobDate\n$address\n$task\nSave as planned work, not proof of completion."
            else "$asset\nLocation: $location\nHandler: $handler\n$note\nThis records your observation at the current time.")},
        confirmButton={TextButton(onClick={when(confirm) { "shift"->actions.shift(date,start,end,rest,overnight,amendsId);"job"->actions.assignment(jobDate,address,task,suggestionSource);else->actions.equipment(asset,location,handler,note,suggestionSource) }; confirm="";amendsId=""}) { Text("Confirm and save") }},
        dismissButton={TextButton(onClick={confirm=""}) { Text("Go back") }})
}
