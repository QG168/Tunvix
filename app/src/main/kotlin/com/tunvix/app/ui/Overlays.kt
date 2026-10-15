package com.tunvix.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.*
import com.tunvix.contract.*

@Composable fun AppOverlay(m:TunvixModel) {
    var newGroup by remember {mutableStateOf(false)}
    var groupName by remember {mutableStateOf("")}
    when(val o=m.overlay) {
        Overlay.Appearance -> BottomSheetExact({m.overlay=null},"深浅色") {
            ThemeMode.entries.forEach { mode ->
                SheetEntry(Glyph.IMAGE,themeModeLabel(mode),mode==m.catalog.settings.themeMode) {
                    m.overlay=null
                    m.operation({m.services.catalog.setThemeMode(mode)})
                }
            }
        }
        Overlay.Groups -> BottomSheetExact({m.overlay=null},"选择频道分组") {
            (listOf("未分组")+m.catalog.channels.filter{c->m.catalog.sources.any{it.id==c.sourceId&&it.kind==SourceKind.CUSTOM}}.map{it.group}).distinct().forEach{g->SheetEntry(Glyph.FOLDER,g,g==m.formGroup){m.formGroup=g;m.overlay=null}}
            SheetEntry(Glyph.PLUS,"新建分组"){newGroup=true;groupName="";m.overlay=null}
        }
        is Overlay.Menu -> BottomSheetExact({m.overlay=null},o.channel.name) {
            SheetEntry(Glyph.STAR,if(o.channel.id in m.catalog.favoriteIds)"取消收藏" else "加入收藏"){m.favorite(o.channel)}
            if(m.catalog.sources.any{it.id==o.channel.sourceId&&it.kind==SourceKind.CUSTOM}) {
                SheetEntry(Glyph.PEN,"编辑频道"){m.edit(o.channel)}
                SheetEntry(Glyph.TRASH,"删除频道",danger=true){m.delete(o.channel)}
            }
            if(m.page==Page.RECENT)SheetEntry(Glyph.X,"移除观看记录"){m.overlay=null;m.operation({m.services.catalog.removeRecent(o.channel.id)})}
        }
        is Overlay.Confirm -> ConfirmDialogExact(o.title,o.message,o.button,o.danger,{m.overlay=null}){m.overlay=null;o.action()}
        is Overlay.Network -> ConfirmDialogExact("当前正在使用移动网络","直播视频可能消耗较多流量，是否继续播放？确认后不再提醒，可在设置中重新开启。","继续播放",false,{m.overlay=null}){m.consent(o.channel)}
        is Overlay.Error -> ConfirmDialogExact(o.title,o.message,"知道了",false,{m.overlay=null},cancel="返回"){m.overlay=null}
        null -> Unit
    }
    if(newGroup) Dialog(onDismissRequest={newGroup=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        TransparentDialogBars()
        Column(Modifier.padding(horizontal=30.dp).widthIn(max=350.dp).fillMaxWidth().background(Design.Surface,RoundedCornerShape(23.dp)).padding(24.dp)) {
            Label("新建分组",18,weight=800);Spacer(Modifier.height(16.dp));Field("分组名称",groupName,"例如：我的频道",onChange={groupName=it})
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                DialogAction("取消",Modifier.weight(1f)){newGroup=false}
                DialogAction("确定",Modifier.weight(1f),Design.Primary,Design.OnPrimary){if(groupName.isNotBlank()){m.formGroup=groupName.trim();newGroup=false}}
            }
        }
    }
}
@Composable fun BottomSheetExact(dismiss:()->Unit,title:String,content:@Composable ColumnScope.()->Unit) {
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        TransparentDialogBars()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B1225).copy(alpha=.36f)).clickable(indication=null,interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()},onClick=dismiss),contentAlignment=Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().heightIn(max=maxHeight*.86f).clipCompat().background(Design.Surface).clickable(indication=null,interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()}){}.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(top=10.dp,bottom=24.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(37.dp).height(4.dp).background(Design.Border,RoundedCornerShape(6.dp)))
                Spacer(Modifier.height(18.dp));Label(title,17,weight=800,modifier=Modifier.padding(start=22.dp,end=22.dp,bottom=15.dp));content()
            }
        }
    }
}
private fun Modifier.clipCompat() = clip(RoundedCornerShape(topStart=25.dp,topEnd=25.dp))
@Composable fun SheetEntry(icon:Int,text:String,selected:Boolean=false,danger:Boolean=false,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=57.dp).clickable(role=Role.Button,onClick=onClick).padding(horizontal=22.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
        Symbol(icon,null,21,if(danger)Design.Danger else Design.Sub);Label(text,14,if(danger)Design.Danger else Design.Ink,650,Modifier.weight(1f));if(selected)Symbol(Glyph.CHECK,null,20,Design.Primary)
    }
}
@Composable fun ConfirmDialogExact(title:String,message:String,confirm:String,danger:Boolean,dismiss:()->Unit,cancel:String="取消",accept:()->Unit) {
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        TransparentDialogBars()
        Column(Modifier.padding(horizontal=30.dp).widthIn(max=350.dp).fillMaxWidth().background(Design.Surface,RoundedCornerShape(23.dp)).padding(24.dp)) {
            Box(Modifier.size(54.dp).background(if(danger)Design.DangerSurface else Design.PrimaryLight,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){Symbol(if(danger)Glyph.ALERT else Glyph.INFO,null,28,if(danger)Design.Danger else Design.Primary)}
            Spacer(Modifier.height(15.dp));Label(title,18,weight=800);Spacer(Modifier.height(10.dp));Label(message,13,Design.Muted);Spacer(Modifier.height(23.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                DialogAction(cancel,Modifier.weight(1f),onClick=dismiss)
                DialogAction(confirm,Modifier.weight(1f),if(danger)Design.Danger else Design.Primary,Design.OnPrimary,accept)
            }
        }
    }
}
@Composable fun DialogAction(text:String,modifier:Modifier,background:Color=Design.SecondarySurface,color:Color=Design.Sub,onClick:()->Unit) {
    Box(modifier.heightIn(min=45.dp).clip(RoundedCornerShape(13.dp)).background(background,RoundedCornerShape(13.dp)).clickable(role=Role.Button,onClick=onClick).padding(horizontal=8.dp,vertical=10.dp),contentAlignment=Alignment.Center){Label(text,14,color,730,align=androidx.compose.ui.text.style.TextAlign.Center)}
}
val DesignPages=listOf(
    "基础页面" to listOf("home" to "频道首页","favorites" to "收藏页","recent" to "最近观看","emptyHome" to "首次打开 / 无直播源","emptyFavorites" to "暂无收藏","emptyRecent" to "暂无观看记录","searchEmpty" to "无搜索结果"),
    "播放体验" to listOf("playerChannels" to "竖屏播放 / 频道","playerRemote" to "竖屏播放 / 遥控器","fullscreen" to "横屏全屏","loading" to "正在连接","playFailure" to "播放失败","expired" to "地址可能过期","network" to "移动网络确认"),
    "直播源与导入" to listOf("management" to "管理页","sources" to "全部直播源","editSourceOnline" to "编辑在线直播源","editSourceLocal" to "重新导入本地文件","add" to "添加方式","subscribe" to "订阅链接导入","local" to "本地文件导入","manual" to "手动添加频道","importResult" to "导入结果","duplicate" to "重复链接提示","refreshFail" to "刷新失败","importFail" to "无法识别的文件","downloadFail" to "下载失败","addressMissing" to "地址不存在","accessDenied" to "服务器拒绝访问","editManual" to "编辑自建频道"),
    "设置与弹层" to listOf("about" to "关于 Tunvix","channelMenu" to "频道长按菜单","recentMenu" to "最近观看菜单","deleteSource" to "删除直播源确认","deleteChannel" to "删除频道确认")
)
@Composable fun DesignIndex(m:TunvixModel) {
    Column(Modifier.fillMaxSize().background(Design.Background)) {
        Row(Modifier.fillMaxWidth().background(Design.Surface).padding(20.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Label("Tunvix 界面索引",20,weight=800);Label("UI 演示入口 · 查看各页面与状态",12,Design.Muted)};IconButtonExact(Glyph.X,"关闭界面索引",{m.showIndex=false})}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            DesignPages.forEach{(category,pages)->Label(category,11,Design.Muted,800,Modifier.padding(top=17.dp,bottom=10.dp));pages.forEach{(id,title)->Row(Modifier.fillMaxWidth().padding(bottom=7.dp).clip(RoundedCornerShape(13.dp)).background(Design.Surface,RoundedCornerShape(13.dp)).clickable{m.preview(id)}.padding(horizontal=15.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){Label(title,13,weight=660,modifier=Modifier.weight(1f));Symbol(Glyph.CHEVRON_RIGHT,null,16,Design.Muted)}}}
        }
    }
}

@Composable private fun TransparentDialogBars() {
    val view=LocalView.current
    val dark=Design.Dark
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { dialogWindow ->
            @Suppress("DEPRECATION")
            dialogWindow.navigationBarColor=android.graphics.Color.TRANSPARENT
            dialogWindow.isNavigationBarContrastEnforced=false
            WindowInsetsControllerCompat(dialogWindow,view).apply { isAppearanceLightNavigationBars=!dark;isAppearanceLightStatusBars=!dark }
        }
    }
}
