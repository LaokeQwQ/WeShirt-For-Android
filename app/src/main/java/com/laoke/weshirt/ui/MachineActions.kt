package com.laoke.weshirt.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Compare a frozen confirmation with the current account, room, machine and occupant. */
internal data class MachineActionSnapshot(val endpoint:String,val userId:String?,val building:String,val machine:Machine) {
    fun sameTarget(state:AppState) = state.authReady && userId != null && endpoint == state.endpoint && userId == state.profile?.id && building == state.building && state.machines.any { it.id == machine.id && it == machine }
}
internal class MachineActionSession(private val vm:AppViewModel,private val scope:CoroutineScope,private val binding:String) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var feedback by mutableStateOf<String?>(null)
    private var active = true
    private var job:Job?=null
    fun current() = active && vm.state.value.let { it.authReady && it.endpoint+"|"+it.profile?.id+"|"+it.building == binding }
    fun validate(frozen:MachineActionSnapshot,resource:String) {
        if(!current() || vm.api.baseUrl != frozen.endpoint) throw CancellationException("Account or service changed")
        if(!frozen.sameTarget(vm.state.value)) throw ApiException(409,"machine_changed","client_${UUID.randomUUID()}","机器或登记人已变化，请重新核对后确认")
        val p=vm.state.value.access.optJSONObject("permissions")?.optJSONObject(resource)
        if(resource.isNotBlank() && p?.optBoolean("operate") != true) throw ApiException(403,"forbidden","client_${UUID.randomUUID()}","当前账号没有此操作权限")
    }
    fun run(frozen:MachineActionSnapshot,resource:String="board",block:suspend MachineActionSession.()->Unit) {
        if(busy || vm.state.value.busy || !current()) return
        busy=true;error=null;feedback=null
        job=scope.launch {
            try { validate(frozen,resource);block();if(current()) vm.refresh() }
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(current()) error=clientFailure(e,"操作未完成，请重试")}
            finally { if(active) busy=false }
        }
    }
    suspend fun request(frozen:MachineActionSnapshot,path:String,body:JSONObject?=null,method:String="POST",resource:String="board"):JSONObject {
        validate(frozen,resource)
        val result=vm.api.call(path,method,body)
        currentCoroutineContext().ensureActive()
        if(!current()) throw CancellationException("Account changed")
        return result
    }
    fun close(){active=false;job?.cancel();error=null;feedback=null}
}

@Composable internal fun rememberMachineActionSession(state:AppState,vm:AppViewModel):MachineActionSession {
    val scope=rememberCoroutineScope()
    val binding=state.endpoint+"|"+state.profile?.id+"|"+state.building
    val session=remember(binding,vm){MachineActionSession(vm,scope,binding)}
    DisposableEffect(session){onDispose{session.close()}}
    return session
}

private data class MachineConfirmation(val snapshot:MachineActionSnapshot,val action:String,val title:String,val message:String,val duration:Int=30,val queueId:String="")
private fun verifiedMachineResult(result:JSONObject):JSONObject {
    if(!result.optBoolean("ok")) throw ApiException(502,"invalid_result","client_${UUID.randomUUID()}","服务端未确认操作结果，请刷新后核对")
    return result
}
internal fun urgeResultText(result:JSONObject):String = "取衣提醒已记录 · 累计 ${result.optInt("count",1)} 次\n" + if(result.optBoolean("dmSent")) "钉钉私信已送达" else "未确认钉钉私信送达，对方可能未绑定或关闭了提醒"
internal fun dryerResultText(result:JSONObject):String = when {
    result.optBoolean("skipped") -> "此机器最近 30 分钟内已提醒过，本次未重复发送"
    result.optBoolean("dmSent") -> "现场异常已记录，钉钉私信已送达登记人"
    else -> "现场异常已记录，未确认钉钉私信送达"
}
private fun queueTime(value:String)=runCatching{DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))}.getOrDefault("待确认")

@Composable internal fun MachineActions(machine:Machine,state:AppState,vm:AppViewModel,session:MachineActionSession,login:()->Unit) {
    val frozen=MachineActionSnapshot(state.endpoint,state.profile?.id,state.building,machine)
    var confirmation by remember { mutableStateOf<MachineConfirmation?>(null) }
    var duration by remember(machine.id,machine.kind){mutableIntStateOf(if(machine.kind=="dryer")60 else 30)}
    var proof by remember(machine.id,machine.occupantId){mutableStateOf(false)}
    var reveal by remember(machine.id,machine.occupantId){ mutableStateOf<JSONObject?>(null) }
    val queues=state.queues.filter{it.str("machineId")==machine.id}
    val mine=queues.find{it.optBoolean("mine")}
    val busy=state.busy || session.busy
    val boardAllowed=state.profile!=null && state.access.optJSONObject("permissions")?.optJSONObject("board")?.optBoolean("operate")==true
    val queueAllowed=state.profile!=null && state.access.optJSONObject("permissions")?.optJSONObject("queue")?.optBoolean("operate")==true
    fun ask(action:String,title:String,message:String,minutes:Int=duration) {
        if(state.profile==null){login();return}
        confirmation=MachineConfirmation(frozen,action,title,message,minutes,mine?.str("id").orEmpty())
    }
    if(machine.occupantId.isNotBlank() && machine.occupantId != state.profile?.id && (machine.state=="abnormal" || machine.suspectedUncollected)) {
        OutlinedButton(onClick={ask("urge","确认提醒取衣","${machine.name}\n请先现场确认程序已结束且衣物仍在。每台机器 5 分钟最多提醒一次。")},enabled=!busy && (state.profile==null || boardAllowed),modifier=Modifier.fillMaxWidth()){Text("提醒登记人取衣")}
    }
    if(machine.state=="faulty") {
        Text("机器显示故障，现场确认恢复后才能提交恢复报告。",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={ask("recover","确认上报已恢复","${machine.name}\n我已在现场检查，机器恢复正常。此次仅记录恢复报告，不代表机器已空闲。")},enabled=!busy && (state.profile==null || boardAllowed),modifier=Modifier.fillMaxWidth()){Text("现场已恢复正常")}
    }
    if(machine.kind=="dryer" && machine.occupantId.isNotBlank() && machine.state in listOf("occupied","abnormal")) {
        OutlinedButton(onClick={ask("stuck","确认烘干现场异常","${machine.name}\n请先检查机器，确认剩余时间长时间不变，疑似跳闸或断电。此报告会尝试通知登记人；30 分钟内不重复发送。")},enabled=!busy && (state.profile==null || boardAllowed),modifier=Modifier.fillMaxWidth()){Text("烘干疑似跳闸 · 现场反馈")}
        Text("页面不会因倒计时或网络延迟自动发出跳闸通知。",style=MaterialTheme.typography.bodySmall)
    }
    if(proof && machine.occupantId.isNotBlank() && machine.occupantId != state.profile?.id) MachineProofForm(machine,state,vm,session,{proof=false}){reveal=it;proof=false}
    HorizontalDivider()
    Text("排队预约",style=MaterialTheme.typography.titleMedium)
    if(mine!=null) {
        Text("我的排位 · 第 ${mine.optInt("position")} 位\n预计 ${queueTime(mine.str("startAt"))} 可使用")
        OutlinedButton(onClick={ask("cancelQueue","确认取消预约","${machine.name}\n取消后将释放你的排队位置。")},enabled=!busy && queueAllowed,modifier=Modifier.fillMaxWidth()){Text("取消这台机器的预约")}
    } else {
        val durations=if(machine.kind=="dryer")(10..180 step 10).toList() else listOf(30,42,50,70)
        ChoiceRow("预计使用时长",durations.map{it.toString() to "$it 分钟"},duration.toString(),!busy){duration=it.toInt()}
        OutlinedButton(onClick={ask("queue","确认加入预约","${machine.name}\n预计使用 $duration 分钟；排队按当前机器剩余时间和前方队列估算，实际以上游与现场为准。")},enabled=!busy && machine.state!="faulty" && (state.profile==null || queueAllowed),modifier=Modifier.fillMaxWidth()){Text("等这台 · 加入排队")}
    }
    queues.forEach{q-> key(q.str("id")) {
        Text("${q.optInt("position")}. ${if(q.optBoolean("mine"))"我" else q.str("nick","等待者")} · ${q.optInt("durationMin")} 分钟\n预计 ${queueTime(q.str("startAt"))} 开始 · 等待约 ${q.optInt("waitMin").coerceAtLeast(0)} 分钟",style=MaterialTheme.typography.bodySmall)
    }}
    if(queues.isEmpty()) Text("目前无人排队",style=MaterialTheme.typography.bodySmall)
    if(queues.any{it.optBoolean("uncertain")}) Text("设备结束时间暂不明确，队列已按完整周期保守估算。",style=MaterialTheme.typography.bodySmall)
    Text("结束前约 3 分钟和设备确认结束时提醒；预计时间归零不代表已经结束。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={vm.refresh()},enabled=!busy && !state.loading,modifier=Modifier.fillMaxWidth()){Text("刷新状态与排队")}
    confirmation?.let{action->AlertDialog(onDismissRequest={if(!busy)confirmation=null},title={Text(action.title)},text={Text(action.message)},confirmButton={TextButton(enabled=!busy,onClick={
        confirmation=null
        val resource=if(action.action in listOf("queue","cancelQueue"))"queue" else "board"
        session.run(action.snapshot,resource){
            val id=action.snapshot.machine.id
            when(action.action){
                "urge"->{val r=verifiedMachineResult(request(action.snapshot,"/api/urge",JSONObject().put("machineId",id)));feedback=urgeResultText(r);if(r.optBoolean("needProof"))proof=true}
                "recover"->{verifiedMachineResult(request(action.snapshot,"/api/faults/recover",JSONObject().put("machineId",id)));feedback="恢复报告已提交，请核对机器最新状态"}
                "stuck"->{feedback=dryerResultText(verifiedMachineResult(request(action.snapshot,"/api/dryer/stuck",JSONObject().put("machineId",id))))}
                "queue"->{
                    if(vm.state.value.queues.any{it.optBoolean("mine")})throw ApiException(409,"queue_changed","client_${UUID.randomUUID()}","你已有预约，请先核对当前队列")
                    val result=request(action.snapshot,"/api/queue",JSONObject().put("machineId",id).put("dorm",action.snapshot.building).put("durationMin",action.duration),resource=resource)
                    if(result.optJSONObject("entry")?.str("machineId")!=id)throw ApiException(502,"invalid_result","client_${UUID.randomUUID()}","预约结果未确认，请刷新队列后核对")
                    feedback="已加入预约，预计时间会随机器和队列状态更新"
                }
                "cancelQueue"->{
                    if(vm.state.value.queues.none{it.str("machineId")==id && it.optBoolean("mine") && it.str("id")==action.queueId})throw ApiException(409,"queue_changed","client_${UUID.randomUUID()}","预约已变化，请重新核对")
                    val result=verifiedMachineResult(request(action.snapshot,"/api/queue?machineId=${vm.api.encoded(id)}",method="DELETE",resource=resource));feedback=if(result.optInt("removed")>0)"此机器的预约已取消" else "此机器当前已无我的预约"
                }
            }
        }
    }){Text("确认")}},dismissButton={TextButton(onClick={confirmation=null},enabled=!busy){Text("取消")}})}
    reveal?.let{r->AlertDialog(onDismissRequest={reveal=null},title={Text("凭证已留档")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(if(r.str("phone").isBlank())"对方未留手机号，可以在机器留言或衣物联系中核对。" else "对方手机号：${r.str("phone")}")
        Text("凭证编号：${r.str("auditId").ifBlank{"待查询"}}")
        Text(if(r.optBoolean("adminNotified"))"服务端确认已发出相关通知" else "未确认相关通知送达，申请记录已经保存")
    }},confirmButton={TextButton(onClick={reveal=null}){Text("隐藏并关闭")}},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn))}
    DisposableEffect(Unit){onDispose{reveal=null;confirmation=null}}
}

@Composable private fun MachineProofForm(machine:Machine,state:AppState,vm:AppViewModel,session:MachineActionSession,close:()->Unit,done:(JSONObject)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val snapshot=MachineActionSnapshot(state.endpoint,state.profile?.id,state.building,machine)
    val scopeKey=state.endpoint+"|"+state.profile?.id+"|"+state.building+"|"+machine.id+"|"+machine.occupantId
    var occupied by remember(scopeKey){mutableStateOf("")};var around by remember(scopeKey){mutableStateOf("")}
    var empty by remember(scopeKey){mutableStateOf(false)};var compressing by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf<Triple<MachineActionSnapshot,String,String>?>(null)}
    val enabled=!state.busy && !session.busy && !compressing
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("已累计提醒 3 次 · 查看手机号需现场凭证",style=MaterialTheme.typography.titleSmall)
        Row {Checkbox(empty,{empty=it},enabled=enabled);Text("我确认机器周围没有可放衣物的空桶",Modifier.weight(1f))}
        listOf("occupied" to "衣物占用照（拍到桶内衣物）","around" to "机器周围照（拍清周围无空桶）").forEach{(kind,title)->
            val image=if(kind=="occupied")occupied else around
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title,Modifier.weight(1f));NativeAttachmentActions(enabled,scopeKey+"|"+kind,onMedia={uri,cleanup->
                compressing=true
                scope.launch{try{
                    session.validate(snapshot,"board")
                    val bytes=withContext(Dispatchers.IO){compressPhoto(context,uri,140_000,1200)}
                    session.validate(snapshot,"board")
                    val data="data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
                    require(data.length<=220_000){"照片超出凭证大小限制"}
                    if(kind=="occupied")occupied=data else around=data
                }catch(e:CancellationException){throw e}catch(e:Exception){session.error=clientFailure(e,"照片处理失败，请重新选择")}finally{cleanup();compressing=false}}
            },onError={session.error=clientFailure(IllegalArgumentException(),it)},imagesOnly=true)}
            if(image.isNotBlank()){AsyncImage(image,title,Modifier.fillMaxWidth().heightIn(max=160.dp));TextButton(onClick={if(kind=="occupied")occupied="" else around=""},enabled=enabled){Text("移除此照片")}}
        }
        if(compressing)Text("正在处理照片…")
        Text("提交后将记录你的账号、现场确认和两张照片，用于纠纷追溯，并尝试通知相关人员。",style=MaterialTheme.typography.bodySmall)
        Button(onClick={confirm=Triple(snapshot,occupied,around)},enabled=enabled && empty && occupied.isNotBlank() && around.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("提交凭证并查看手机号")}
        TextButton(onClick=close,enabled=enabled){Text("关闭凭证填写")}
    }}
    confirm?.let{frozen->AlertDialog(onDismissRequest={if(enabled)confirm=null},title={Text("确认提交现场凭证")},text={Text("${machine.name}\n已确认周围无空桶，并准备提交衣物占用和机器周围共两张照片。此次申请会留档。")},confirmButton={TextButton(enabled=enabled,onClick={
        confirm=null
        session.run(frozen.first){
            if(!empty || occupied!=frozen.second || around!=frozen.third)throw ApiException(409,"proof_changed","client_${UUID.randomUUID()}","凭证内容已改变，请重新确认")
            val response=verifiedMachineResult(request(frozen.first,"/api/urge/reveal",JSONObject().put("machineId",machine.id).put("confirmEmpty",true).put("photoOccupied",frozen.second).put("photoAround",frozen.third)))
            session.validate(frozen.first,"board")
            occupied="";around="";empty=false;done(response)
        }
    }){Text("确认提交")}},dismissButton={TextButton(onClick={confirm=null},enabled=enabled){Text("返回修改")}},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn))}
    DisposableEffect(Unit){onDispose{occupied="";around="";confirm=null}}
}
