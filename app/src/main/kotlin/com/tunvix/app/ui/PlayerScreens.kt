package com.tunvix.app.ui

import android.view.SurfaceView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import com.tunvix.contract.*

@Composable fun PlayerScreen(m:TunvixModel) {
    val channel=m.currentChannel
    if(channel==null){EmptyState(Glyph.TV,"频道不可用","此频道已被移除，返回频道列表继续浏览。","浏览频道"){m.back()};return}
    Column(Modifier.fillMaxSize().background(Design.Surface)) {
        Row(Modifier.fillMaxWidth().height(62.dp).background(Design.Background).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            IconButtonExact(Glyph.CHEVRON_LEFT,"返回",m::back,Color.Transparent,Design.Ink,size=36,iconSize=23)
            Label(channel.name,15,Design.Ink,750,Modifier.weight(1f),maxLines=1)
            IconButtonExact(Glyph.STAR,"收藏",{m.favorite(channel)},Design.Surface,if(channel.id in m.catalog.favoriteIds)Design.Primary else Design.Ink,size=36,iconSize=19)
        }
        VideoArea(m,channel,Modifier.fillMaxWidth().aspectRatio(16f/9f))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=22.dp,topEnd=22.dp)).background(Design.Surface).padding(start=17.dp,end=17.dp,top=13.dp,bottom=11.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("频道","节目单","遥控器").forEachIndexed{i,t->
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(13.dp)).background(if(m.playerSection==i)Design.PrimaryLight else Color.Transparent,RoundedCornerShape(13.dp)).clickable(role=Role.Tab){m.playerSection=i;m.showControls()},contentAlignment=Alignment.Center){Label(t,14,if(m.playerSection==i)Design.Primary else Design.Muted,730)}
            }
        }
        val pager=rememberPagerState(initialPage=m.playerSection){3}
        LaunchedEffect(m.playerSection){if(pager.currentPage != m.playerSection)pager.animateScrollToPage(m.playerSection)}
        LaunchedEffect(pager.settledPage){m.playerSection=pager.settledPage;m.showControls()}
        HorizontalPager(pager,Modifier.weight(1f),verticalAlignment=Alignment.Top){p->when(p){0->PlayerChannels(m,channel);1->ProgrammePanel(m,channel);else->RemotePanel(m)}}
    }
}
@Composable fun VideoArea(m:TunvixModel,channel:Channel,modifier:Modifier,full:Boolean=false,pip:Boolean=false) {
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    val session=m.playSession
    LaunchedEffect(surface,session,m.playback.videoFrameReady,m.foreground,channel.thumbnailUri,m.playback.audioOnly,pip) {
        val view=surface
        if(view!=null && !m.playback.audioOnly && !pip && m.foreground && m.playback.videoFrameReady && channel.thumbnailUri==null && m.needsPlayingThumbnail(session)) {
            repeat(3) {
                kotlinx.coroutines.delay(250)
                val jpeg=capturePlaybackStill(view)
                if(jpeg!=null) { m.savePlayingThumbnail(channel,jpeg,session); return@LaunchedEffect }
            }
        }
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val bounds=coordinates.boundsInWindow()
        m.videoBounds=android.graphics.Rect(bounds.left.roundToInt(),bounds.top.roundToInt(),bounds.right.roundToInt(),bounds.bottom.roundToInt())
    }.background(Color(0xFF05070E)).then(if(pip)Modifier else Modifier.clickable(onClick=m::toggleControls))) {
        if(m.playback.audioOnly && !pip) {
            Column(Modifier.fillMaxSize().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Symbol(Glyph.HEADPHONES,null,34,Color(0xFFAAAFFF))
                Spacer(Modifier.height(10.dp));Label(channel.name,16,Color.White,750,maxLines=1)
                Label("听音模式 · 可返回桌面或息屏",12,Color(0xFFB8C0D1))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButtonExact(if(m.playback.phase==PlaybackPhase.PAUSED)Glyph.PLAY else Glyph.PAUSE,"播放暂停",m::pauseToggle,Color.Black.copy(alpha=.44f),Color.White,size=48)
                    SmallVideoAction("退出听音",true,m::toggleListening)
                }
            }
        } else {
        if(m.services.demo)ChannelImage(channel,Modifier.fillMaxSize().alpha(.75f),crop=false)
        else BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            val ratio=m.playback.videoAspectRatio.takeIf{it>0} ?: (16f/9f)
            val available=maxWidth.value/maxHeight.value
            val viewModifier=if(available>ratio)Modifier.height(maxHeight).width(maxHeight*ratio) else Modifier.width(maxWidth).height(maxWidth/ratio)
            AndroidView(factory={context->SurfaceView(context).also{surface=it;m.services.playback.attachSurface(it)}},modifier=viewModifier,onRelease={if(surface===it)surface=null;m.services.playback.detachSurface(it)})
        }
        }
        if(!pip && !m.playback.audioOnly && m.controlsVisible)Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.20f),Color.Transparent,Color.Black.copy(alpha=.56f)))))
        if(!pip && !m.playback.audioOnly) {
        if(full) {
            if(m.controlsVisible)FullControls(m,channel)
        } else {
            if(m.controlsVisible)Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(13.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                IconButtonExact(if(m.playback.phase==PlaybackPhase.PAUSED)Glyph.PLAY else Glyph.PAUSE,"播放暂停",m::pauseToggle,Color.Black.copy(alpha=.44f),Color.White,size=35,radius=10,iconSize=19)
                IconButtonExact(Glyph.MAXIMIZE,"全屏",{m.changeFullscreen(true)},Color.Black.copy(alpha=.44f),Color.White,size=35,radius=10,iconSize=19)
            }
        }
        if(m.controlsVisible && !full)PlaybackModeButtons(m,Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        val phase=m.previewPhase ?: m.playback.phase
        if(!pip && (phase==PlaybackPhase.CONNECTING||phase==PlaybackPhase.FAILED)) {
            VideoState(m,phase)
            if(m.playback.audioOnly)Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { SmallVideoAction("退出听音",true,m::toggleListening) }
        }
    }
}
@Composable fun PlaybackModeButtons(m:TunvixModel,modifier:Modifier=Modifier) {
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        IconButtonExact(Glyph.HEADPHONES,"开启听音",m::toggleListening,Color.Black.copy(alpha=.5f),Color.White,size=48,radius=13,iconSize=23)
        IconButtonExact(Glyph.PIP,"小窗播放",m::enterSmallWindow,Color.Black.copy(alpha=.5f),Color.White,size=48,radius=13,iconSize=23)
    }
}
@Composable fun VideoState(m:TunvixModel,phase:PlaybackPhase) {
    Column(Modifier.fillMaxSize().background(Color(0xFF080C17).copy(alpha=.84f)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        if(phase==PlaybackPhase.CONNECTING){CircularProgressIndicator(Modifier.size(31.dp),color=Color(0xFF9698FF),strokeWidth=2.dp);Spacer(Modifier.height(15.dp));Label("正在连接",16,Color.White,700)}
        else {
            Symbol(Glyph.ALERT,null,31,Color.White);Spacer(Modifier.height(12.dp));Label(if(m.previewExpired||m.playback.mayBeExpired)"地址可能已过期" else "暂时无法播放",16,Color.White,700)
            Label(m.playback.errorMessage ?: "连接失败，请重试或切换其他频道。",12,Color(0xFFB8C0D1),align=androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                SmallVideoAction("重试",true){m.previewPhase=null;m.services.playback.retry()}
                if((m.previewExpired||m.playback.mayBeExpired)&&m.catalog.sources.any{it.id==m.currentChannel?.sourceId&&it.kind==SourceKind.SUBSCRIPTION})SmallVideoAction("刷新订阅"){m.refreshPlayingSource()}
                else SmallVideoAction("下一台"){m.skip(1)}
            }
        }
    }
}
@Composable fun SmallVideoAction(text:String,primary:Boolean=false,onClick:()->Unit){Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(primary)Design.Primary else Color.Transparent,RoundedCornerShape(10.dp)).border(1.dp,if(primary)Design.Primary else Color(0xFF5F6980),RoundedCornerShape(10.dp)).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=9.dp)){Label(text,12,if(primary)Design.OnPrimary else Color.White)}}
@Composable fun PlayerChannels(m:TunvixModel,current:Channel) {
    val channels=m.catalog.channels.filter{it.sourceId==current.sourceId&&it.group==m.playerGroup}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=12.dp,bottom=14.dp),horizontalArrangement=Arrangement.SpaceBetween){Label("频道列表",14,Design.Ink,700);Label("${channels.size} 个频道",12,Design.Muted)}
        CenteredGroups(m.groups(current.sourceId),m.playerGroup,current.sourceId,Modifier.fillMaxWidth().padding(top=3.dp,bottom=15.dp),compact=true) { m.playerGroup=it }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(channels.size,key={channels[it].id}) {i->val c=channels[i];val selected=c.id==current.id
                Row(Modifier.fillMaxWidth().heightIn(min=53.dp).clip(RoundedCornerShape(13.dp)).background(if(selected)Design.PrimaryLight else Design.SecondarySurface,RoundedCornerShape(13.dp)).clickable(role=Role.Button){m.play(c)}.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Label((i+1).toString().padStart(2,'0'),11,if(selected)Design.Primary else Design.Muted,modifier=Modifier.width(17.dp));Label(c.name,13,if(selected)Design.Primary else Design.Ink,690,Modifier.weight(1f),maxLines=1);if(selected)Symbol(Glyph.VOLUME,null,16,Design.Primary)
                }
            }
            if(channels.isEmpty())item{Label("此分组暂无频道",13,Design.Muted)}
        }
    }
}
@Composable fun RemotePanel(m:TunvixModel) {
    val haptic=LocalHapticFeedback.current
    fun feedback(){if(m.catalog.settings.hapticsEnabled)haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=18.dp,end=18.dp,top=20.dp,bottom=40.dp)) {
        Label("频道切换",12,Design.Muted,720);Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            RemoteKey("上一台",Glyph.SKIP_PREV,Modifier.weight(1f)){feedback();m.skip(-1)}
            RemoteKey("下一台",Glyph.SKIP_NEXT,Modifier.weight(1f)){feedback();m.skip(1)}
        }
        Spacer(Modifier.height(28.dp));Label("媒体音量",12,Design.Muted,720);Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().background(Design.Background,RoundedCornerShape(20.dp)).padding(horizontal=16.dp,vertical=20.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Label("当前音量",13,weight=750);Label("${m.playback.volumePercent}%",20,Design.Primary,750)}
            Spacer(Modifier.height(21.dp))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                IconButtonExact(Glyph.MINUS,"音量减",{feedback();m.volume(m.playback.volumePercent-7)},color=Design.Primary,size=40,radius=12)
                Slider(value=m.playback.volumePercent.toFloat(),onValueChange={m.volume(it.toInt())},modifier=Modifier.weight(1f).height(40.dp),valueRange=0f..100f,colors=SliderDefaults.colors(thumbColor=Design.Primary,activeTrackColor=Design.Primary,inactiveTrackColor=Design.Border))
                IconButtonExact(Glyph.PLUS,"音量加",{feedback();m.volume(m.playback.volumePercent+7)},color=Design.Primary,size=40,radius=12)
            }
        }
        Spacer(Modifier.height(20.dp));Label("按照当前分组顺序切台，首尾循环\n音量与手机媒体音量同步",11,Design.Muted,modifier=Modifier.fillMaxWidth(),align=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable fun RemoteKey(text:String,icon:Int,modifier:Modifier,onClick:()->Unit){Column(modifier.heightIn(min=133.dp).clip(RoundedCornerShape(19.dp)).background(Design.PrimaryLight,RoundedCornerShape(19.dp)).clickable(role=Role.Button,onClick=onClick).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Symbol(icon,null,29,Design.Primary);Spacer(Modifier.height(10.dp));Label(text,15,Design.Primary,750)}}
@Composable fun FullscreenScreen(m:TunvixModel){m.currentChannel?.let{VideoArea(m,it,Modifier.fillMaxSize(),true)}}
@Composable fun FullControls(m:TunvixModel,channel:Channel) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal=22.dp,vertical=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
            IconButtonExact(Glyph.MINIMIZE,"退出全屏",{m.changeFullscreen(false)},Color.Black.copy(alpha=.5f),Color.White,size=41,radius=13)
            Label(channel.name,16,Color.White,750,Modifier.weight(1f),maxLines=1);Label(if(m.services.demo)"画面预览" else "直播中",12,Color.White)
            PlaybackModeButtons(m)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=22.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                IconButtonExact(Glyph.SKIP_PREV,"上一台",{m.skip(-1)},Color.Black.copy(alpha=.5f),Color.White,size=45,radius=13)
                IconButtonExact(if(m.playback.phase==PlaybackPhase.PAUSED)Glyph.PLAY else Glyph.PAUSE,"播放暂停",m::pauseToggle,Color.Black.copy(alpha=.5f),Color.White,size=45,radius=13)
                IconButtonExact(Glyph.SKIP_NEXT,"下一台",{m.skip(1)},Color.Black.copy(alpha=.5f),Color.White,size=45,radius=13)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IconButtonExact(Glyph.MINUS,"音量减",{m.volume(m.playback.volumePercent-7)},Color.Black.copy(alpha=.5f),Color.White,size=41,radius=13)
                Slider(m.playback.volumePercent.toFloat(),{m.volume(it.toInt())},Modifier.width(100.dp),valueRange=0f..100f)
                IconButtonExact(Glyph.PLUS,"音量加",{m.volume(m.playback.volumePercent+7)},Color.Black.copy(alpha=.5f),Color.White,size=41,radius=13)
            }
        }
    }
}
