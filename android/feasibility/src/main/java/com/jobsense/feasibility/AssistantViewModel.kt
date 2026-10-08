package com.jobsense.feasibility

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*

data class AssistantUiState(val question: String = "", val busy: Boolean = false, val answer: String = "",
    val evidence: List<AssistantEvidence> = emptyList(), val scope: Set<String> = emptySet(), val modelReady: Boolean = false,
    val modelStatus: String = "Not downloaded", val useModel: Boolean = false, val rate: String = "",
    val workSummary: String = "No confirmed shifts", val shifts: List<JSONObject> = emptyList(), val assignments: List<JSONObject> = emptyList(), val equipment: List<JSONObject> = emptyList(), val error: String = "")

class AssistantViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val prefs = DiagnosticStore.preferences(app)
    private val mutable = MutableStateFlow(AssistantUiState(question = saved["assistantQuestion"] ?: "",
        scope = prefs.getStringSet("assistantChatScope", emptySet()).orEmpty().toSet(), useModel = prefs.getBoolean("assistantUseModel",true),
        rate = prefs.getString("assistantHourlyRate", "").orEmpty()))
    val state = mutable.asStateFlow()
    val zone: ZoneId = ZoneId.of("America/New_York")
    private var askGeneration = 0
    init {
        if(!prefs.contains("assistantChatScope")) {
            val chosen=ArchiveStore.chats(app).filter { it.saving || it.sharing }.map { it.id }.toSet()
            prefs.edit().putStringSet("assistantChatScope",chosen).apply()
            mutable.value=mutable.value.copy(scope=chosen)
        }
        refresh()
    }
    fun refresh() {
        viewModelScope.launch {
            try {
                val records = withContext(Dispatchers.IO) { Triple(PrivateWorkRecords.read(app,"SHIFT"),PrivateWorkRecords.read(app,"EQUIPMENT"),PrivateWorkRecords.read(app,"ASSIGNMENT")) }
                val hours = WorkAssistantCore.hours(records.first.map(::shift), rate())
                mutable.value = mutable.value.copy(modelReady=LocalAiModels.ready(app),modelStatus=prefs.getString("assistantModelStatus","Not downloaded").orEmpty(),
                    shifts=records.first,equipment=records.second,assignments=records.third,workSummary="${hours.minutes/60}h ${hours.minutes%60}m confirmed${hours.amount?.let { " · $it gross at your saved rate" } ?: " · rate not set"}")
            } catch (_: Exception) { mutable.value=mutable.value.copy(error="Saved records could not be read. Keep app data intact and try again.") }
        }
    }
    private fun rate() = mutable.value.rate.takeIf { it.isNotBlank() }?.let { BigDecimal(it) }
    private fun shift(value: JSONObject) = ConfirmedShift(value.getString("id"),Instant.parse(value.getString("start")),Instant.parse(value.getString("end")),value.getInt("breakMinutes"),true)
    fun question(value: String) { saved["assistantQuestion"]=value.take(1000); mutable.value=mutable.value.copy(question=value.take(1000)) }
    fun scope(id: String, checked: Boolean) {
        val scope = if(checked) mutable.value.scope+id else mutable.value.scope-id
        prefs.edit().putStringSet("assistantChatScope",scope).apply()
        // Excluding a chat immediately removes any cached answer/evidence from the UI.
        mutable.value=mutable.value.copy(scope=scope,answer="",evidence=emptyList())
    }
    fun model(enabled: Boolean) { prefs.edit().putBoolean("assistantUseModel",enabled).apply(); mutable.value=mutable.value.copy(useModel=enabled) }
    fun download() { LocalAiModels.download(app); refresh() }
    fun cancelDownload() { LocalAiModels.cancelDownload(app); refresh() }
    fun saveRate(value: String) {
        try { require(value.isBlank() || BigDecimal(value).signum() >= 0); prefs.edit().putString("assistantHourlyRate",value).apply(); mutable.value=mutable.value.copy(rate=value,error=""); refresh() }
        catch (_: Exception) { mutable.value=mutable.value.copy(error="Enter a valid nonnegative hourly rate, or leave it blank.") }
    }
    fun ask() {
        val request=mutable.value
        if(request.busy || request.question.isBlank()) return
        mutable.value=request.copy(busy=true,error="",answer="",evidence=emptyList())
        val generation=++askGeneration
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val date=WorkAssistantCore.resolvedDate(request.question,LocalDate.now(zone))
                    val evidence=AssistantSearch.search(app,request.question,request.scope)
                    val addresses=WorkAssistantCore.addressEvidence(request.question,evidence,LocalDate.now(zone),zone)
                    val q=request.question.lowercase()
                    val terms=WorkAssistantCore.terms(q)
                    val assets=PrivateWorkRecords.read(app,"EQUIPMENT").filter { row ->
                        WorkAssistantCore.terms(row.getString("asset")).any { it in terms }
                    }.distinctBy { it.getString("asset").lowercase() }
                    val jobs=if(date!=null && listOf("address","job","assignment","work").any { it in q })
                        PrivateWorkRecords.read(app,"ASSIGNMENT").filter { it.getString("date")==date.toString() } else emptyList()
                    val base = when {
                        WorkAssistantCore.isHoursQuestion(q) -> {
                            val shifts=WorkAssistantCore.shiftsForDate(PrivateWorkRecords.read(app,"SHIFT").map(::shift),date,zone)
                            val result=WorkAssistantCore.hours(shifts,rate())
                            if(shifts.isEmpty()) "No confirmed work sessions${date?.let { " for $it" } ?: ""}. Messages alone do not certify paid hours. Add reviewed shifts in Work."
                            else "${result.minutes/60}h ${result.minutes%60}m from ${shifts.size} confirmed shifts${date?.let { " for $it" } ?: ""}.${result.amount?.let { " Gross estimate: $it at your saved hourly rate; deductions and overtime are not calculated." } ?: " Add an hourly rate to estimate gross pay."}"
                        }
                        assets.isNotEmpty() -> assets.joinToString("\n\n") { row ->
                            "${row.getString("asset")}: last user-confirmed at ${row.getString("location")} on ${row.getString("time")}" +
                                row.optString("handler").takeIf { it.isNotBlank() }?.let { " · handler: $it" }.orEmpty() +
                                ". Record ${row.getString("id")}. This does not rule out later movement; compare message evidence below."
                        } + if(assets.size>1) "\nSeveral assets match. Specify the size/name you mean." else ""
                        jobs.isNotEmpty() -> jobs.joinToString("\n\n") { row ->
                            "Planned job ${row.getString("date")}: ${row.getString("address")}\n${row.getString("task")}\nUser-reviewed assignment, record ${row.getString("id")}. This does not certify attendance or completed work."
                        }
                        addresses.isNotEmpty() -> addresses.joinToString("\n") { (index,suggestion) ->
                            "Message [S${index+1}] mentions ${suggestion.address}${suggestion.date?.let { " for $it" }.orEmpty()}."
                        } + if(addresses.map { it.second.address }.distinct().size>1)
                            "\nSeveral addresses match. Check the dated instructions below before choosing the current job."
                        else "\nThis is message evidence; attendance and completed work are unverified."
                        request.scope.isEmpty() -> "Choose the saved chats the assistant may search. Your confirmed work and equipment records can still be queried without sharing chats."
                        evidence.isEmpty() -> "No matching evidence in the chats you selected. Try a contact name, address, equipment name, or a phrase from the message."
                        else -> "Found ${evidence.size} matching messages${date?.let { " · question date: $it" } ?: ""}. Read the dated sources below; instructions do not prove completed work or equipment movement."
                    }
                    val factual = WorkAssistantCore.isHoursQuestion(q) || assets.isNotEmpty() || jobs.isNotEmpty() || addresses.isNotEmpty()
                    val answer=if(request.useModel && !factual && evidence.isNotEmpty()) {
                        if(!LocalAiModels.ready(app)) "$base\nLocal model is not ready. Download it in AI setup; no cloud AI was used."
                        else try {
                            val generated=LocalAiModels.answer(app,WorkAssistantCore.prompt(request.question,evidence,date,zone.id))
                            val excerpt=WorkAssistantCore.verifiedExcerpt(generated,evidence)
                            if(excerpt!=null) "Source excerpt selected by local AI:\n$excerpt\nThis is a message quotation, not proof that its instructions were completed."
                            else "$base\nThe local model did not return a verifiable source quotation, so its draft was withheld."
                        } catch (_: Exception) { "$base\nLocal AI could not finish on this device. Search results remain available; no cloud fallback was used." }
                    } else base
                    answer to evidence
                }
                if(generation==askGeneration && mutable.value.scope==request.scope && mutable.value.question==request.question)
                    mutable.value=mutable.value.copy(answer=result.first,evidence=result.second)
            } catch (error: IllegalArgumentException) { if(generation==askGeneration) mutable.value=mutable.value.copy(error=error.message ?: "Check the question's dates and saved records.") }
            catch (_: Exception) { if(generation==askGeneration) mutable.value=mutable.value.copy(error="The question could not be answered. Check dates and saved records, then try again.") }
            finally { mutable.value=mutable.value.copy(busy=false) }
        }
    }
    fun stopAnswer() {
        askGeneration++
        viewModelScope.launch(Dispatchers.IO) { LocalAiModels.cancelAnswer() }
        mutable.value=mutable.value.copy(answer="Answer stopped. You can edit the question and try again.")
        // Keep busy until the native request actually stops, preventing concurrent model loads.
    }
    override fun onCleared() { LocalAiModels.cancelAnswer(); super.onCleared() }
    fun saveShift(date: String,start: String,end: String,breakMinutes: String,overnight: Boolean,amendsId: String="") = save {
        val from=WorkAssistantCore.exactLocalTime(date,start,zone)
        val toDate=if(overnight) LocalDate.parse(date).plusDays(1).toString() else date
        val to=WorkAssistantCore.exactLocalTime(toDate,end,zone)
        val rest=breakMinutes.ifBlank { "0" }.toInt()
        val existing=PrivateWorkRecords.read(app,"SHIFT").filter { it.getString("id")!=amendsId }.map(::shift)
        WorkAssistantCore.hours(existing+ConfirmedShift("new",from,to,rest,true),rate())
        PrivateWorkRecords.save(app,"SHIFT",JSONObject().put("start",from.toString()).put("end",to.toString()).put("breakMinutes",rest).put("zone",zone.id).put("source","USER_CONFIRMED").put("amendsId",amendsId))
    }
    private fun sourceRecord(id: String): JSONObject? {
        if(id.isBlank()) return null
        val source=AssistantSearch.source(app,id,mutable.value.scope) ?: throw IllegalArgumentException("This source is no longer in your assistant's chosen chats. Review it again.")
        return JSONObject().put("messageId",source.id).put("chatId",source.chatId).put("at",source.at).put("direction",source.direction).put("body",source.body).put("source",source.source)
    }
    fun saveEquipment(asset: String,location: String,handler: String,note: String,sourceId: String="") = save {
        require(asset.isNotBlank() && location.isNotBlank() && asset.length<=100 && location.length<=200 && note.length<=1000)
        PrivateWorkRecords.save(app,"EQUIPMENT",JSONObject().put("asset",asset.trim()).put("location",location.trim()).put("handler",handler.take(100))
            .put("note",note).put("time",Instant.now().toString()).put("source","USER_CONFIRMED").put("messageEvidence",sourceRecord(sourceId)))
    }
    fun saveAssignment(date: String,address: String,task: String,sourceId: String="") = save {
        LocalDate.parse(date)
        require(address.isNotBlank() && address.length<=200 && task.length<=1000) { "Enter a job address and a valid date." }
        PrivateWorkRecords.save(app,"ASSIGNMENT",JSONObject().put("date",date).put("address",address.trim()).put("task",task).put("source","USER_REVIEWED_PLAN").put("messageEvidence",sourceRecord(sourceId)))
    }
    private fun save(action: () -> Unit) {
        if(mutable.value.busy) return
        mutable.value=mutable.value.copy(busy=true,error="")
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (error: IllegalArgumentException) { mutable.value=mutable.value.copy(error=error.message ?: "Check the entered details.") }
            catch (_: Exception) { mutable.value=mutable.value.copy(error="Could not save this record. Your existing records remain intact.") }
            finally { mutable.value=mutable.value.copy(busy=false); refresh() }
        }
    }
}
