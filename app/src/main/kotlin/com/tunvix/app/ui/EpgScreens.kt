package com.tunvix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tunvix.app.services.EpgSource
import com.tunvix.contract.Channel
import kotlinx.coroutines.delay
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun epgTime(millis: Long, pattern: String = "HH:mm"): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

@Composable fun ProgrammePanel(m: TunvixModel, channel: Channel) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val schedule = m.epg.schedule(channel)
    val programmes = schedule?.second.orEmpty()
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    var selectedDay by remember(channel.id, today) { mutableStateOf(today) }
    val dates = (listOf(today) + programmes.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate() }).distinct().sorted()
    val visible = programmes.filter { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate() == selectedDay }
    val listState = rememberLazyListState()
    var matching by remember(channel.id) { mutableStateOf(false) }
    val currentIndex = visible.indexOfFirst { now >= it.start && now < it.end }
    LaunchedEffect(channel.id, selectedDay, programmes) { if (currentIndex >= 0) listState.scrollToItem(currentIndex) }
    LaunchedEffect(channel.id) { m.services.epg?.refresh(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(channel.name, 14, weight=750, maxLines=1)
                Label(schedule?.first ?: "暂无匹配节目单", 11, Design.Muted)
            }
            TextButton(onClick={ matching=true }) { Label("匹配", 12, Design.Primary, 700) }
            TextButton(onClick=m::refreshEpg, enabled=!m.epg.refreshing) { Label(if(m.epg.refreshing) "更新中" else "刷新", 12, Design.Primary, 700) }
        }
        LazyRow(contentPadding=PaddingValues(horizontal=18.dp, vertical=10.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(dates, key={ it.toString() }) { day ->
                GroupChip(if(day==today) "今天" else day.format(DateTimeFormatter.ofPattern("MM-dd")), selectedDay==day, true) { selectedDay=day }
            }
        }
        if(visible.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
                Label(if(m.epg.refreshing) "正在获取节目单" else "暂无节目单", 15, weight=750)
                Spacer(Modifier.height(8.dp))
                Label("此频道或日期可能尚未收录，可尝试匹配频道或添加其他订阅。", 12, Design.Muted)
                TextButton(onClick={m.navigate(Page.EPG)}) {Label("管理 EPG 订阅",13,Design.Primary,700)}
            }
        } else LazyColumn(state=listState, modifier=Modifier.weight(1f), contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=20.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(visible) { item ->
                val playing = now >= item.start && now < item.end
                val ended = now >= item.end
                Row(Modifier.fillMaxWidth().background(if(playing) Design.PrimaryLight else Design.SecondarySurface, RoundedCornerShape(13.dp)).padding(14.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Label("${epgTime(item.start)}\n${epgTime(item.end)}", 11, if(playing) Design.Primary else Design.Muted, 700)
                    Column(Modifier.weight(1f)) {
                        Label(item.title, 13, if(playing) Design.Primary else if(ended) Design.Muted else Design.Ink, 700)
                        Spacer(Modifier.height(4.dp))
                        Label(if(playing) "正在播出" else if(ended) "已播出" else "即将播出", 11, if(playing) Design.Primary else Design.Muted)
                    }
                }
            }
        }
    }
    if(matching) EpgMatchDialog(m, channel) { matching=false }
}

@Composable private fun EpgMatchDialog(m:TunvixModel, channel:Channel, close:()->Unit) {
    var query by remember { mutableStateOf("") }
    val stations = m.epg.sources.flatMap { source ->
        m.epg.documents[source.id]?.stations.orEmpty().map { source to it }
    }.filter { (_, station) -> query.isBlank() || station.names.any { it.contains(query,true) } }
    AlertDialog(onDismissRequest=close, containerColor=Design.Surface,
        title={Label("匹配节目单频道",17,weight=750)},
        text={Column {
            Field("搜索",query,"输入频道名称",required=false,onChange={query=it})
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                item { TextButton(onClick={m.matchEpg(channel.id,null,null);close()}) {Label("恢复自动匹配",13,Design.Primary,700)} }
                items(stations) { (source, station) ->
                    Column(Modifier.fillMaxWidth().clickable {m.matchEpg(channel.id,source.id,station.id);close()}.padding(vertical=12.dp)) {
                        Label(station.names.firstOrNull() ?: station.id,13,weight=700)
                        Label(source.name,11,Design.Muted)
                    }
                }
                if(stations.isEmpty()) item {Label("没有可匹配的频道，请先更新订阅",12,Design.Muted)}
            }
        }}, confirmButton={TextButton(onClick=close){Label("关闭",13,Design.Primary)}})
}

@Composable fun EpgSettingsScreen(m:TunvixModel) {
    var editing by remember { mutableStateOf<EpgSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("EPG 节目单",m::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
                Label("自定义优先 · 内置补缺",12,Design.Muted,modifier=Modifier.weight(1f))
                TextButton(onClick=m::refreshEpg,enabled=!m.epg.refreshing) {Label(if(m.epg.refreshing)"更新中" else "全部刷新",12,Design.Primary,700)}
            }
            m.epg.sources.forEachIndexed { index, source ->
                SettingBox {
                    Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Label(source.name,14,weight=750)
                        Label(if(source.builtin)"内置默认 · 保留且不可删除" else if(index==0)"优先使用" else "备用订阅",11,Design.Primary,700)
                        Label(source.url,11,Design.Muted,maxLines=3)
                        val updated=m.epg.updated[source.id]
                        val document=m.epg.documents[source.id]
                        Label(if(updated==null)"尚未获取节目单" else "${document?.stations?.size ?: 0} 个频道 · 更新于 ${epgTime(updated,"MM-dd HH:mm")}",11,Design.Muted)
                        m.epg.errors[source.id]?.let {Label("更新失败：$it${if(document!=null)"，继续使用缓存" else ""}",11,Design.Warning,maxLines=3)}
                        if(!source.builtin) Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick={editing=source}){Label("修改",12,Design.Primary,700)}
                            if(index!=0)TextButton(onClick={m.prioritizeEpg(source.id)}){Label("设为优先",12,Design.Primary,700)}
                            TextButton(onClick={m.removeEpg(source)}){Label("删除",12,Design.Danger,700)}
                        }
                    }
                }
            }
            Hint("支持 XMLTV 的 XML / XML.GZ 订阅，节目覆盖和日期范围取决于订阅源。",Modifier.padding(horizontal=18.dp,vertical=10.dp))
        }
        FixedAction("添加节目单订阅",Glyph.PLUS){adding=true}
    }
    if(adding || editing!=null) EpgSourceDialog(editing,close={adding=false;editing=null}) { name,url ->
        m.saveEpg(editing?.id,name,url);adding=false;editing=null
    }
}

@Composable private fun EpgSourceDialog(source:EpgSource?,close:()->Unit,save:(String,String)->Unit) {
    var name by remember(source?.id) { mutableStateOf(source?.name ?: "") }
    var url by remember(source?.id) { mutableStateOf(source?.url ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=close,containerColor=Design.Surface,
        title={Label(if(source==null)"添加节目单订阅" else "修改节目单订阅",17,weight=750)},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Field("名称",name,"我的节目单",onChange={name=it})
            Field("订阅地址",url,"https://example.com/epg.xml.gz",onChange={url=it})
            error?.let{Label(it,12,Design.Danger)}
        }}, dismissButton={TextButton(onClick=close){Label("取消",13,Design.Muted)}},
        confirmButton={TextButton(onClick={
            val uri=runCatching{URI(url.trim())}.getOrNull()
            error=if(name.isBlank())"请输入名称" else if(uri==null || uri.scheme?.lowercase() !in listOf("http","https") || uri.host.isNullOrBlank() || uri.userInfo!=null)"请输入有效的 HTTP 或 HTTPS 地址" else null
            if(error==null)save(name.trim(),url.trim())
        }){Label("保存",13,Design.Primary,700)}})
}
