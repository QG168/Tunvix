package com.tunvix.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.tunvix.contract.*

@OptIn(ExperimentalFoundationApi::class)
@Composable fun HomeScreen(model: TunvixModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        if(model.searchOpen) Row(Modifier.fillMaxWidth().height(86.dp).padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
            Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp)).background(Design.Surface,RoundedCornerShape(14.dp)).border(1.dp,Design.Border,RoundedCornerShape(14.dp)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Symbol(Glyph.SEARCH,null,20,Design.Muted)
                BasicTextField(model.query,{model.query=it},Modifier.weight(1f),singleLine=true,textStyle=TextStyle(fontSize=14.sp,color=Design.Ink),cursorBrush=SolidColor(Design.Primary),decorationBox={inner->Box{if(model.query.isEmpty())Label("搜索频道",14,Design.Muted);inner()}})
                if(model.query.isNotEmpty())IconButtonExact(Glyph.X,"清除搜索",{model.query=""},size=24,iconSize=17)
            }
            Label("取消",14,Design.Primary,750,Modifier.clickable{model.searchOpen=false;model.query=""})
        } else Row(Modifier.fillMaxWidth().height(86.dp).padding(start=23.dp,end=19.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column {
                Row {
                    androidx.compose.material3.Text("Tun",fontSize=26.sp,fontWeight=FontWeight(820),color=Design.Ink,letterSpacing=(-1.2).sp)
                    androidx.compose.material3.Text("vix",fontSize=26.sp,fontWeight=FontWeight(850),color=Design.Primary,letterSpacing=(-1.2).sp)
                }
                Label(if(model.currentSource==null)"未选择直播源" else model.currentSource?.name ?: "未选择直播源",11,Design.Muted,maxLines=1)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                IconButtonExact(Glyph.SEARCH,"搜索",{model.searchOpen=true})
                val canRefresh=model.catalog.channels.isNotEmpty() && model.currentSource!=null
                RefreshButton("刷新当前分组画面",model.screenshotRefreshing,model::refreshGroupScreenshots,enabled=canRefresh)
            }
        }
        if(!model.emptyPreview && model.catalog.channels.isNotEmpty()) CenteredGroups(
            model.groups(),model.group,model.catalog.selectedSourceId.orEmpty(),Modifier.fillMaxWidth().padding(bottom=15.dp)
        ) { model.group=it }
        val channels=if(model.emptyPreview)emptyList() else UiRules.visibleChannels(model.catalog,model.group,model.query)
        Box(Modifier.weight(1f)) {
            when {
                model.emptyPreview || model.catalog.channels.isEmpty() -> EmptyState(Glyph.CLOUD,"还没有直播源","添加自己的直播源，开始观看喜爱的频道","添加直播源"){model.navigate(Page.ADD)}
                channels.isEmpty() && model.query.isNotEmpty() -> EmptyState(Glyph.SEARCH,"没有找到相关频道","换个关键词试试，或清除当前搜索条件","清除搜索"){model.query=""}
                channels.isEmpty() -> EmptyState(Glyph.TV,"暂无频道","此分组暂时没有频道，可以切换其他分组查看")
                else -> ChannelGrid(model,channels,false)
            }
        }
        BottomNav(model)
    }
}
@Composable fun ChannelGrid(model: TunvixModel, channels: List<Channel>, showSource: Boolean) {
    key(model.page,model.catalog.selectedSourceId,model.group,model.query) {
    val state=androidx.compose.runtime.saveable.rememberSaveable(model.page,model.catalog.selectedSourceId,model.group,model.query,saver=LazyGridState.Saver) { LazyGridState() }
    val detectEnabled=model.catalog.settings.firstFrameEnabled
    LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxSize(),state=state,contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        items(channels,key={it.id}) {c->
            // 标签只在开关开启时展示；三个浏览页读取同一份测量结果。
            val tag=if(detectEnabled) {
                val detecting=c.id in model.catalog.detectingIds
                UiRules.firstFrameLabel(c.firstFrame,detecting) to frameTierColor(UiRules.firstFrameTier(c.firstFrame,detecting))
            } else null
            ChannelCard(c,if(showSource)model.catalog.sources.firstOrNull{it.id==c.sourceId}?.name else null,{model.play(c)},{model.overlay=Overlay.Menu(c)},allowNetwork=true,capturing=c.id in model.catalog.capturingIds,frameTag=tag?.first,frameColor=tag?.second ?: Design.Muted)
        }
    }
    LaunchedEffect(state,channels) {
        snapshotFlow { UiRules.thumbnailWindow(channels,state.layoutInfo.visibleItemsInfo.map { it.index }) }
            .collect { model.setVisibleChannels(it) }
    }
    DisposableEffect(model) { onDispose { model.setVisibleChannels(emptyList()) } }
    }
}
@Composable fun BottomNav(model: TunvixModel) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Design.Background,Design.Surface)))) {
        HorizontalDivider(color=Design.Border,thickness=1.dp)
        Row(Modifier.height(74.dp).padding(start=13.dp,end=13.dp,top=7.dp,bottom=1.dp)) {
            listOf(Triple(Page.HOME,Glyph.GLOBE,"频道"),Triple(Page.FAVORITES,Glyph.STAR,"收藏"),Triple(Page.RECENT,Glyph.CLOCK,"最近"),Triple(Page.MANAGEMENT,Glyph.MANAGE,"管理")).forEach{(page,icon,title)->
                val selected=model.page==page
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(if(selected)Design.PrimaryLight else Color.Transparent,RoundedCornerShape(18.dp)).clickable(role=Role.Tab){model.tab(page)},verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Symbol(icon,title,24,if(selected)Design.Primary else Design.Sub);Spacer(Modifier.height(3.dp));Label(title,12,if(selected)Design.Primary else Design.Sub,600)
                }
            }
        }
    }
}
@Composable fun CollectionScreen(model: TunvixModel) {
    val favorite=model.page==Page.FAVORITES
    val ids=if(model.emptyCollectionPreview)emptyList() else if(favorite)model.catalog.favoriteIds.toList() else model.catalog.recentIds
    val channels=ids.mapNotNull { id->model.catalog.channels.firstOrNull{it.id==id} }
    Column(Modifier.fillMaxSize()) {
        Header(if(favorite)"我的收藏" else "最近观看",{model.tab(Page.HOME)}) {Label("${channels.size} 个频道",13,Design.Muted)}
        if(channels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Label(if(favorite)"收藏的频道会汇总在这里" else "按最近观看时间排列",12,Design.Muted)
            if(!favorite)Label("清空记录",12,Design.Primary,700,Modifier.clickable{model.confirmClearHistory()})
        }
        Box(Modifier.weight(1f)) {
            if(channels.isEmpty())EmptyState(if(favorite)Glyph.STAR else Glyph.CLOCK,if(favorite)"暂无收藏" else "暂无观看记录",if(favorite)"看到喜欢的频道，点亮星标就能在这里快速找到。" else "看过的频道会自动出现在这里，方便下次继续观看。","浏览频道"){model.tab(Page.HOME)}
            else ChannelGrid(model,channels,true)
        }
        BottomNav(model)
    }
}
fun TunvixModel.confirmClearHistory() {overlay=Overlay.Confirm("清空观看记录？","此操作会移除全部最近观看记录，不影响直播源与收藏。","清空记录",true){operation({services.catalog.clearRecents()},"观看记录已清空")}}
