package com.tunvix.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tunvix.contract.*

@Composable fun SourcesScreen(m: TunvixModel) {
    Column(Modifier.fillMaxSize()) {
        Header("全部直播源",m::back)
        PageDescription("按添加顺序列出全部直播源")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
            m.catalog.sources.forEach{s->
                SourceRow(m,s,selected=s.id==m.catalog.selectedSourceId,onSelect={m.selectSource(s)})
            }
        }
        FixedAction("添加直播源",Glyph.PLUS){m.navigate(Page.ADD)}
    }
}

/** 管理一级页：上半部分直播源预览与单选切换，下半部分内嵌应用设置。 */
@Composable fun ManagementScreen(m: TunvixModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(69.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Label("管理",20,weight=800)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start=22.dp,end=20.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Label("直播源",12,Design.Muted,800)
                Row(Modifier.clip(RoundedCornerShape(11.dp)).clickable(role=Role.Button){m.navigate(Page.ADD)}.padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Symbol(Glyph.PLUS,null,15,Design.Primary);Label("添加",13,Design.Primary,750)
                }
            }
            val sources=m.catalog.sources
            val selectedId=m.catalog.selectedSourceId
            if(sources.isEmpty()) {
                // 无源时的简洁添加卡片；应用设置仍然展示。
                Row(Modifier.fillMaxWidth().padding(horizontal=18.dp).clip(RoundedCornerShape(18.dp)).background(Design.Surface,RoundedCornerShape(18.dp)).clickable(role=Role.Button){m.navigate(Page.ADD)}.padding(horizontal=17.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    ListIcon(Glyph.CLOUD)
                    Column(Modifier.weight(1f)) {Label("还没有直播源",14,weight=730);Label("添加订阅、本地文件或手动频道",12,Design.Muted)}
                    Symbol(Glyph.CHEVRON_RIGHT,null,18,Design.Muted)
                }
            } else {
                Column(Modifier.padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    // 按目录顺序预览前 3 个；选中源不在其中时额外显示当前源，避免重复。
                    val preview=sources.take(3).toMutableList()
                    sources.firstOrNull{it.id==selectedId}?.let{current->if(sources.indexOfFirst{it.id==current.id}>=3)preview.add(current)}
                    preview.forEach{s->SourceRow(m,s,selected=s.id==selectedId,onSelect={m.selectSource(s)})}
                }
                if(sources.size>3)Row(Modifier.fillMaxWidth().padding(horizontal=18.dp).clip(RoundedCornerShape(14.dp)).clickable(role=Role.Button){m.navigate(Page.SOURCES)}.padding(horizontal=6.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Label("查看全部直播源",13,Design.Sub,700,modifier=Modifier.weight(1f));Symbol(Glyph.CHEVRON_RIGHT,null,16,Design.Muted)
                }
            }
            SettingSection("后台播放")
            SettingBox {
                SettingRow(Glyph.PIP,"返回桌面自动小窗","视频播放时自动进入系统画中画") {ToggleExact(m.catalog.settings.autoPictureInPicture,"返回桌面自动小窗",m::setAutomaticSmallWindow)}
                SettingRow(Glyph.HEADPHONES,"息屏听音","在播放器点击听音开启，退出听音可恢复画面") {}
                SettingRow(Glyph.GEAR,"小窗系统权限","查看系统画中画授权",{m.requestPictureInPictureSettings?.invoke()}) {Symbol(Glyph.CHEVRON_RIGHT,null,16)}
            }
            SettingSection("播放与网络")
            SettingBox {
                SettingRow(Glyph.WIFI,"移动网络播放提醒","使用移动网络播放前提示流量消耗") {ToggleExact(m.catalog.settings.mobileReminderEnabled,"移动网络播放提醒"){v->m.operation({m.services.catalog.updateSettings(m.catalog.settings.copy(mobileReminderEnabled=v))})}}
                SettingRow(Glyph.VIBRATE,"按键震动反馈","遥控器点击时提供轻微震动") {ToggleExact(m.catalog.settings.hapticsEnabled,"按键震动反馈"){v->m.operation({m.services.catalog.updateSettings(m.catalog.settings.copy(hapticsEnabled=v))})}}
                SettingRow(Glyph.SIGNAL,"显示首帧耗时","检测需要请求直播源，显示最近一次测量结果。") {ToggleExact(m.catalog.settings.firstFrameEnabled,"显示首帧耗时"){v->m.operation({m.services.catalog.updateSettings(m.catalog.settings.copy(firstFrameEnabled=v))})}}
            }
            SettingSection("节目单")
            SettingBox {
                SettingRow(Glyph.LIST,"EPG 节目单","管理节目单订阅",{m.navigate(Page.EPG)}) {Symbol(Glyph.CHEVRON_RIGHT,null,16)}
            }
            SettingSection("外观")
            SettingBox {
                SettingRow(Glyph.IMAGE,"深浅色",onClick={m.overlay=Overlay.Appearance}) {
                    Label(themeModeLabel(m.catalog.settings.themeMode),12,Design.Muted)
                    Symbol(Glyph.CHEVRON_RIGHT,null,16)
                }
            }
            SettingSection("存储与记录")
            SettingBox {
                SettingRow(Glyph.IMAGE,"截图存储","清理已保存的频道截图",{m.overlay=Overlay.Confirm("清理截图缓存？","将删除保存在本机的频道截图，通过 Wi-Fi 或移动网络浏览时会自动重新获取。","清理缓存"){m.operation({m.services.catalog.clearThumbnailCache()},"截图缓存已清理")}}) {Label(String.format(java.util.Locale.ROOT,"%.1f MB",m.catalog.cacheBytes/1048576.0),12,Design.Muted);Symbol(Glyph.CHEVRON_RIGHT,null,16)}
                SettingRow(Glyph.CLOCK,"清空观看记录","清空全部最近观看记录",m::confirmClearHistory){Symbol(Glyph.CHEVRON_RIGHT,null,16)}
            }
            SettingSection("其他")
            SettingBox {SettingRow(Glyph.INFO,"关于 Tunvix",onClick={m.navigate(Page.ABOUT)}){Label("v${com.tunvix.app.BuildConfig.VERSION_NAME}",12,Design.Muted);Symbol(Glyph.CHEVRON_RIGHT,null,16)}}
        }
        BottomNav(m)
    }
}

/** 单个直播源卡片：卡片主体或勾选区点击即切换当前源；刷新/编辑/删除独立响应，不触发切换。 */
@Composable fun SourceRow(m: TunvixModel,s: LiveSource,selected: Boolean,onSelect: ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=92.dp).clip(RoundedCornerShape(18.dp)).background(Design.Surface,RoundedCornerShape(18.dp)).clickable(role=Role.Button,onClick=onSelect).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ListIcon(when(s.kind){SourceKind.SUBSCRIPTION->Glyph.CLOUD;SourceKind.LOCAL_FILE->Glyph.FILE;else->Glyph.PEN})
        Column(Modifier.weight(1f)) {Label(s.name,14,weight=730,maxLines=1);Label("${kindName(s.kind)} · ${m.catalog.channels.count{it.sourceId==s.id}} 个频道",12,Design.Muted,maxLines=1)}
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            if(s.kind==SourceKind.SUBSCRIPTION)RefreshButton("刷新 ${s.name}",s.id in m.refreshingSourceIds,{m.refreshSubscription(s)},size=33,radius=10,iconSize=17,background=Color.Transparent,color=Design.Muted)
            if(s.kind!=SourceKind.CUSTOM)TinyAction(Glyph.PEN,"编辑 ${s.name}"){m.editSource(s)}
            TinyAction(Glyph.TRASH,"删除 ${s.name}"){m.delete(s)}
            // 单选标记：当前源显示蓝紫色勾选。
            Box(Modifier.padding(start=6.dp).size(22.dp),contentAlignment=Alignment.Center) {
                if(selected)Box(Modifier.size(21.dp).background(Design.Primary,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center){Symbol(Glyph.CHECK,null,14,Design.OnPrimary)}
                else Box(Modifier.size(19.dp).border(1.6.dp,Design.Border,androidx.compose.foundation.shape.CircleShape))
            }
        }
    }
}
fun kindName(kind: SourceKind)=when(kind){SourceKind.SUBSCRIPTION->"在线订阅";SourceKind.LOCAL_FILE->"本地文件";SourceKind.CUSTOM->"手动创建"}
@Composable fun ListIcon(icon: Int) {Box(Modifier.size(44.dp).background(Design.PrimaryLight,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Symbol(icon,null,21,Design.Primary)}}
@Composable fun TinyAction(icon: Int,description: String,onClick:()->Unit){IconButtonExact(icon,description,onClick,background=Color.Transparent,color=Design.Muted,size=33,radius=10,iconSize=17)}
@Composable fun FixedAction(text: String,icon: Int,enabled: Boolean=true,onClick:()->Unit){Box(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=12.dp,bottom=18.dp)){PrimaryButton(text,icon,Modifier.fillMaxWidth(),enabled,onClick)}}
@Composable fun AddScreen(m:TunvixModel) {
    Column(Modifier.fillMaxSize()) {
        Header("添加直播源",m::back);PageDescription("选择一种方式，导入你自己的频道。")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AddOption(Glyph.LINK,"订阅链接","支持 M3U / M3U8 频道列表\n及兼容的 APTV 订阅",Design.Primary,Design.PrimaryLight){m.navigate(Page.SUBSCRIBE)}
            AddOption(Glyph.FILE,"本地文件","从设备选择 M3U、M3U8\n或 TXT 频道列表",Color(0xFF16A88B),Design.SuccessSurface){m.navigate(Page.LOCAL)}
            AddOption(Glyph.PEN,"手动添加频道","填写频道名称和播放链接\n保存到「自建频道」",Color(0xFFEF9C43),Design.WarningSurface){m.navigate(Page.MANUAL)}
        }
    }
}
@Composable fun AddOption(icon:Int,title:String,description:String,color:Color,background:Color,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Design.Surface,RoundedCornerShape(20.dp)).clickable(role=Role.Button,onClick=onClick).padding(horizontal=17.dp,vertical=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(54.dp).background(background,RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center){Symbol(icon,null,25,color)}
        Column(Modifier.weight(1f)){Label(title,16,weight=800);Spacer(Modifier.height(5.dp));Label(description,12,Design.Muted)}
        Symbol(Glyph.CHEVRON_RIGHT,null,18,Design.Muted)
    }
}
@Composable fun FormScreen(m:TunvixModel,selectFile:()->Unit) {
    val manual=m.page==Page.MANUAL;val editSource=m.page==Page.EDIT_SOURCE
    val source=m.catalog.sources.firstOrNull{it.id==m.editingSourceId}
    val local=m.page==Page.LOCAL || editSource&&source?.kind==SourceKind.LOCAL_FILE
    val title=if(editSource)if(local)"重新导入文件" else "编辑直播源" else if(manual)if(m.editingChannelId!=null)"编辑频道" else "手动添加频道" else if(local)"本地文件导入" else "订阅链接"
    Column(Modifier.fillMaxSize()) {
        Header(title,m::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start=18.dp,end=18.dp,top=4.dp,bottom=26.dp)) {
            if(local&&!editSource)UploadArea(m,selectFile)
            Field(if(manual)"频道名称" else "直播源名称",m.formName,if(manual)"例如：我的高清频道" else if(local)"例如：家庭电视列表" else "例如：我的订阅源",onChange={m.formName=it})
            if(local) {
                if(editSource)UploadArea(m,selectFile)
                if(!editSource){Label("订阅链接",13,weight=750);Spacer(Modifier.height(10.dp));Hint("请先从安卓系统文件选择器中选择频道列表，文件内容将在本地解析。")}
                else Hint("本地文件需要重新选择才能更新频道。")
            } else Field(if(manual)"播放链接" else "订阅链接",m.formLink,if(manual)"请输入以 https:// 或 http:// 开头的直接播放地址" else "粘贴 M3U / M3U8 或 APTV 订阅地址",multiline=true,
                helper=if(manual)"支持常见的直播播放地址，长链接可自动换行。" else "重复导入时会提示刷新已有直播源，不会直接创建重复源。",onChange={m.formLink=it})
            if(manual) {
                Label("频道分组（可选）",13,weight=750);Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(51.dp).clip(RoundedCornerShape(14.dp)).background(Design.Surface,RoundedCornerShape(14.dp)).border(1.dp,Design.Border,RoundedCornerShape(14.dp)).clickable{m.overlay=Overlay.Groups}.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {Label(m.formGroup,14,modifier=Modifier.weight(1f));Symbol(Glyph.CHEVRON_DOWN,null,17)}
                Spacer(Modifier.height(8.dp));Label("不选择时归入「未分组」，统一保存在「自建频道」。",11,Design.Muted)
            }
            if(editSource&&!local)Hint("编辑订阅源不会直接修改已导入的频道内容。")
        }
        FixedAction(if(m.busy)"正在解析…" else if(editSource)"保存修改" else if(manual)if(m.editingChannelId!=null)"保存修改" else "保存频道" else if(local)"解析文件" else "解析订阅",if(manual||editSource)Glyph.CHECK else Glyph.ARROW_RIGHT,!m.busy){if(editSource)m.saveSource() else if(manual)m.saveChannel() else m.parse()}
    }
}
@Composable fun UploadArea(m:TunvixModel,selectFile:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom=21.dp)) {
        Column(Modifier.fillMaxWidth().height(185.dp).clip(RoundedCornerShape(20.dp)).background(Design.PrimaryLight,RoundedCornerShape(20.dp)).border(1.dp,Design.Border,RoundedCornerShape(20.dp)).clickable(role=Role.Button,onClick=selectFile),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Symbol(Glyph.UPLOAD,null,37,Design.Primary);Spacer(Modifier.height(10.dp));Label(if(m.fileUri==null)"选择本地文件" else "已选择文件",14,Design.Primary,700);Spacer(Modifier.height(10.dp));Label("M3U / M3U8 / TXT",11,Design.Muted)
        }
        if(m.fileUri!=null){Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth().background(Design.Surface,RoundedCornerShape(16.dp)).padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){Symbol(Glyph.FILE,null,25,Design.Primary);Label(m.fileName,13,weight=750,modifier=Modifier.weight(1f),maxLines=1);TinyAction(Glyph.REFRESH,"重新选择文件",selectFile)}}
    }
}
@Composable fun ResultScreen(m:TunvixModel) {
    val report=m.report
    Column(Modifier.fillMaxSize()) {
        Header("导入结果",m::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(start=22.dp,end=22.dp,top=32.dp,bottom=14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.size(98.dp).background(Design.SuccessSurface,CircleShape),contentAlignment=Alignment.Center){Symbol(Glyph.CHECK,null,49,Color(0xFF28B586))}
                Spacer(Modifier.height(20.dp));Label("解析完成",22,weight=800);Spacer(Modifier.height(10.dp));Label("频道列表已成功识别\n确认后即可在首页查看",13,Design.Muted,align=androidx.compose.ui.text.style.TextAlign.Center)
            }
            Row(Modifier.padding(horizontal=18.dp,vertical=22.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                StatBox("${report?.channels?.size ?: 0}","有效频道",Design.Primary,Modifier.weight(1f));StatBox("${report?.groupCount ?: 0}","频道分组",Design.Ink,Modifier.weight(1f))
            }
            if((report?.skippedCount ?: 0)>0)Row(Modifier.padding(horizontal=18.dp).background(Design.WarningSurface,RoundedCornerShape(13.dp)).padding(13.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){Symbol(Glyph.INFO,null,17,Design.Warning);Label("其中 ${report?.skippedCount} 条内容无法识别，已自动跳过，不影响其他频道导入。",12,Design.Warning,modifier=Modifier.weight(1f))}
            Row(Modifier.padding(18.dp).fillMaxWidth().background(Design.Surface,RoundedCornerShape(18.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){ListIcon(Glyph.FILE);Column(Modifier.weight(1f)){Label(m.formName.ifBlank{"我的新订阅"},14,weight=730);Label("${if(m.importRequest is ImportRequest.LocalFile)"本地文件" else "在线订阅"} · 即将导入",12,Design.Muted)};Symbol(Glyph.CHECK,null,18)}
            if(m.services.demo)Hint("UI 演示结果：未读取实际订阅内容。",Modifier.padding(horizontal=18.dp))
        }
        FixedAction("确认导入",Glyph.CHECK,!m.busy&&report!=null){m.commit()}
    }
}
@Composable fun StatBox(value:String,label:String,color:Color,modifier:Modifier) {Column(modifier.background(Design.Surface,RoundedCornerShape(18.dp)).padding(horizontal=8.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally){Label(value,29,color,800);Spacer(Modifier.height(7.dp));Label(label,12,Design.Muted)}}
@Composable fun SettingSection(title:String){Label(title,12,Design.Muted,800,Modifier.padding(start=22.dp,end=22.dp,top=16.dp,bottom=11.dp))}
@Composable fun SettingBox(content:@Composable ColumnScope.()->Unit){Column(Modifier.padding(start=18.dp,end=18.dp,bottom=10.dp).fillMaxWidth().background(Design.Surface,RoundedCornerShape(18.dp)),content=content)}
@Composable fun SettingRow(icon:Int,title:String,note:String?=null,onClick:(()->Unit)?=null,trailing:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier).padding(horizontal=14.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Symbol(icon,null,20,Design.Primary);Column(Modifier.weight(1f)){Label(title,14,weight=720);note?.let{Spacer(Modifier.height(5.dp));Label(it,11,Design.Muted)}};trailing()
    }
    HorizontalDivider(Modifier.padding(horizontal=14.dp),color=Design.Border)
}
@Composable fun AboutScreen(m:TunvixModel) {
    Column(Modifier.fillMaxSize()) {
        Header("关于 Tunvix",m::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top=32.dp,bottom=25.dp),horizontalAlignment=Alignment.CenterHorizontally){androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.tunvix.app.R.drawable.app_icon),contentDescription="Tunvix 应用图标",modifier=Modifier.size(70.dp),contentScale=androidx.compose.ui.layout.ContentScale.Fit);Spacer(Modifier.height(14.dp));Label("Tunvix",20,weight=800);Spacer(Modifier.height(7.dp));Label("版本 ${com.tunvix.app.BuildConfig.VERSION_NAME} · Android${if(m.services.demo)" · UI 演示版" else ""}",12,Design.Muted)}
            SettingBox {SettingRow(Glyph.TV,"属于你的直播播放器","自行导入播放源 · 不内置直播频道");SettingRow(Glyph.INFO,"兼容系统","Android 10 及以上")}
            if(m.services.demo) {Hint("此版本使用设计稿的静态画面和内存数据，真实导入、播放与持久化尚未接入。",Modifier.padding(18.dp));FixedAction("查看全部界面状态",Glyph.LIST){m.showIndex=true}}
        }
    }
}

fun themeModeLabel(mode: ThemeMode): String = when(mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }
