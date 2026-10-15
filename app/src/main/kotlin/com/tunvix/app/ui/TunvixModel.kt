package com.tunvix.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tunvix.app.services.AppServices
import com.tunvix.contract.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

enum class Page { HOME, FAVORITES, RECENT, MANAGEMENT, PLAYER, SOURCES, ADD, SUBSCRIBE, LOCAL, MANUAL, EDIT_SOURCE, RESULT, ABOUT, EPG }
sealed interface Overlay {
    data object Groups : Overlay
    data object Appearance : Overlay
    data class Menu(val channel: Channel) : Overlay
    data class Confirm(val title: String, val message: String, val button: String, val danger: Boolean = false, val action: () -> Unit) : Overlay
    data class Error(val title: String, val message: String) : Overlay
    data class Network(val channel: Channel) : Overlay
}
class TunvixModel(val services: AppServices) : ViewModel() {
    var epg by mutableStateOf(com.tunvix.app.services.EpgState()); private set
    private fun epgAction(action: suspend (com.tunvix.app.services.EpgRepository) -> Unit) {
        val repository = services.epg ?: run { toast = "演示版不获取在线节目单"; return }
        viewModelScope.launch {
            try { action(repository) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                overlay = Overlay.Error("节目单操作未完成", error.message ?: "请重试")
            }
        }
    }
    fun refreshEpg() = epgAction { it.refresh() }
    fun saveEpg(id: String?, name: String, url: String) = epgAction { it.save(id, name, url) }
    fun prioritizeEpg(id: String) = epgAction { it.prioritize(id) }
    fun removeEpg(source: com.tunvix.app.services.EpgSource) {
        if (source.builtin) return
        overlay = Overlay.Confirm("删除节目单订阅？", "将删除“${source.name}”及其本地节目单缓存。", "删除", true) {
            epgAction { it.remove(source.id) }
        }
    }
    fun matchEpg(channelId: String, sourceId: String?, stationId: String?) = epgAction { it.match(channelId, sourceId, stationId) }
    var catalogReady by mutableStateOf(services.catalogReady.value); private set
    var catalogError by mutableStateOf(services.catalogError.value); private set
    var foreground by mutableStateOf(true)
    var pictureInPicture by mutableStateOf(false)
    var videoBounds: android.graphics.Rect? = null
    var requestPictureInPicture: (() -> Unit)? = null
    var requestPictureInPictureSettings: (() -> Unit)? = null
    var requestPictureInPicturePermission: (() -> Unit)? = null
    fun enterSmallWindow() {
        if (services.demo) { toast="演示模式不支持系统小窗"; return }
        requestPictureInPicture?.invoke()
    }
    fun toggleListening() {
        services.playback.setAudioOnly(!playback.audioOnly)
        fullscreen=false; showControls()
    }
    fun setAutomaticSmallWindow(enabled: Boolean) {
        operation({ services.catalog.updateSettings(catalog.settings.copy(autoPictureInPicture=enabled)) })
        if (enabled) requestPictureInPicturePermission?.invoke()
    }
    fun closePictureInPicture() {
        services.playback.stop()
        pictureInPicture = false
        fullscreen = false
        overlay = null
        previewPhase = null
        previewExpired = false
        history.clear()
        page = Page.HOME
    }
    var screenshotRefreshing by mutableStateOf(false); private set
    var refreshingSourceIds by mutableStateOf<Set<String>>(emptySet()); private set
    private var screenshotJob: Job? = null
    private fun cancelScreenshotRefresh() { screenshotJob?.cancel(); screenshotJob=null }
    fun onBackground() { foreground=false; cancelScreenshotRefresh(); services.playback.onBackground() }
    fun refreshSubscription(source: LiveSource) {
        if(source.id in refreshingSourceIds)return
        refreshingSourceIds=refreshingSourceIds+source.id
        viewModelScope.launch {
            try { when(val result=services.catalog.refreshSource(source.id)) {
                is Outcome.Success -> toast="${source.name}：订阅已更新"
                is Outcome.Failure -> toast="刷新失败：${result.message}"
            } } finally { refreshingSourceIds=refreshingSourceIds-source.id }
        }
    }
    fun refreshGroupScreenshots() {
        if(screenshotRefreshing)return
        val ids=UiRules.visibleChannels(catalog,group,"").map{it.id}
        if(ids.isEmpty()){toast="当前分组没有频道";return}
        val source=catalog.selectedSourceId; val selectedGroup=group
        screenshotRefreshing=true
        screenshotJob=viewModelScope.launch {
            val guard=launch { snapshotFlow { Triple(foreground,page,catalog.selectedSourceId to group) }.collect { (active,currentPage,current) ->
                if(!active || currentPage!=Page.HOME || current!=(source to selectedGroup))screenshotJob?.cancel()
            } }
            try {
                services.catalog.requestThumbnails(emptyList(),false)
                when(val result=services.catalog.refreshThumbnails(ids)) {
                    is Outcome.Success -> toast="已更新 ${ids.size}/${ids.size} 个画面，失败 0 个"
                    is Outcome.Failure -> toast=result.message
                }
            } finally { guard.cancel(); screenshotRefreshing=false }
        }
    }
    private var visibleIds by mutableStateOf<List<String>>(emptyList())
    fun setVisibleChannels(ids: List<String>) { visibleIds = ids.distinct() }
    var catalog by mutableStateOf(services.catalog.snapshot.value); private set
    var playback by mutableStateOf(services.playback.snapshot.value); private set
    var page by mutableStateOf(Page.HOME)
    var overlay by mutableStateOf<Overlay?>(null)
    private var selectedGroup by mutableStateOf(UiRules.restoredGroup(catalog))
    private var groupSaveJob: Job? = null
    var group: String
        get() = selectedGroup
        set(value) {
            if (selectedGroup == value) return
            selectedGroup = value
            visibleIds = emptyList()
            val source = catalog.selectedSourceId ?: return
            if (!catalogReady || value !in groups()) return
            groupSaveJob?.cancel()
            groupSaveJob = viewModelScope.launch {
                when (val result = services.catalog.rememberBrowseGroup(source, value)) {
                    is Outcome.Failure -> toast = "分组保存失败：${result.message}"
                    is Outcome.Success -> Unit
                }
            }
        }
    private var selectedPlayerGroup by mutableStateOf("央视频道")
    var playerGroup: String
        get() = selectedPlayerGroup
        set(value) { if (selectedPlayerGroup!=value) { selectedPlayerGroup=value;showControls() } }
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var fullscreen by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var controlsInteraction by mutableStateOf(0L); private set
    var playSession by mutableStateOf(0L); private set
    private var capturedSession: Long? = null
    fun showControls() { controlsVisible = true; controlsInteraction++ }
    fun toggleControls() {
        if (playback.phase != PlaybackPhase.PLAYING) showControls()
        else { controlsVisible = !controlsVisible; controlsInteraction++ }
    }
    fun changeFullscreen(value: Boolean) { fullscreen = value; showControls() }
    fun savePlayingThumbnail(channel: Channel, jpeg: ByteArray, session: Long) {
        if (session != playSession || page != Page.PLAYER || playback.channelId != channel.id || capturedSession == session) return
        // Once accepted, leaving the screen does not cancel the tiny cache commit.
        viewModelScope.launch {
            val result = services.catalog.savePlaybackThumbnail(channel, jpeg)
            if (result is Outcome.Success && session == playSession) capturedSession = session
        }
    }
    fun needsPlayingThumbnail(session: Long): Boolean = capturedSession != session
    var playerSection by mutableStateOf(0)
    var remote: Boolean
        get() = playerSection == 2
        set(value) { playerSection = if (value) 2 else 0; showControls() }
    var showIndex by mutableStateOf(false)
    var emptyPreview by mutableStateOf(false)
    var emptyCollectionPreview by mutableStateOf(false)
    var previewPhase by mutableStateOf<PlaybackPhase?>(null)
    var previewExpired by mutableStateOf(false)
    var formName by mutableStateOf("")
    var formLink by mutableStateOf("")
    var formGroup by mutableStateOf("未分组")
    var fileUri by mutableStateOf<String?>(null)
    var fileName by mutableStateOf("")
    var editingChannelId by mutableStateOf<String?>(null)
    var editingSourceId by mutableStateOf<String?>(null)
    var report by mutableStateOf<ImportReport?>(null)
    var importRequest by mutableStateOf<ImportRequest?>(null)
    var busy by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private val history = ArrayDeque<Page>()
    private var importJob: Job? = null
    private var importGeneration = 0
    private var navigationGeneration = 0
    private var pendingRefresh: Pair<Int, Channel>? = null
    /** Browse request key: visible ids, network consent, detection toggle and the exact media list. */
    private data class BrowseKey(val ids: List<String>, val network: Boolean, val detect: Boolean, val media: List<Pair<Pair<String,String>,Map<String,String>>>)
    fun onForeground() {
        foreground = true
        services.playback.onForeground()
        val pending = pendingRefresh; pendingRefresh = null
        if (pending != null && page == Page.PLAYER && navigationGeneration == pending.first) play(pending.second, true)
    }
    private fun cancelImport() { if(importJob?.isActive == true){importGeneration++;importJob?.cancel();busy=false}; importJob=null }
    var mobileNetwork by mutableStateOf(false)
    val currentChannel get() = catalog.channels.firstOrNull { it.id == playback.channelId }
    val currentSource get() = catalog.sources.firstOrNull { it.id == catalog.selectedSourceId }
    fun groups(sourceId: String? = catalog.selectedSourceId): List<String> {
        val real = catalog.channels.filter { it.sourceId == sourceId }.map { it.group }.distinct()
        return if (services.demo && sourceId == "online") listOf("央视频道","卫视频道","体育","电影","少儿","港澳台","4K专区") else real.ifEmpty { listOf("未分组") }
    }
    private data class ControlsKey(val phase: PlaybackPhase, val enabled: Boolean, val visible: Boolean, val interaction: Long, val fullscreen: Boolean)
    init {
        services.epg?.let { repository ->
            viewModelScope.launch { repository.state.collect { epg = it } }
            epgAction { it.load() }
        }
        viewModelScope.launch {
            snapshotFlow {
                val phase=previewPhase ?: playback.phase
                ControlsKey(phase,UiRules.controlsAutoHide(page,phase,foreground,overlay!=null||showIndex),controlsVisible,controlsInteraction,fullscreen)
            }.collectLatest { state ->
                if (state.phase!=PlaybackPhase.PLAYING && page==Page.PLAYER) controlsVisible=true
                if (state.enabled && state.visible) { delay(3500);controlsVisible=false }
            }
        }
        viewModelScope.launch { services.catalogReady.collect { catalogReady = it } }
        viewModelScope.launch { services.catalogError.collect { catalogError = it } }
        viewModelScope.launch {
            snapshotFlow {
                val browse = page in listOf(Page.HOME, Page.FAVORITES, Page.RECENT) && foreground && catalogReady && catalogError == null
                val ids=visibleIds.filter { id -> catalog.channels.any { it.id == id } }
                val requested=if(browse && !screenshotRefreshing)ids else emptyList()
                BrowseKey(requested,browse && !screenshotRefreshing,catalog.settings.firstFrameEnabled,
                    catalog.channels.filter { it.id in requested.toSet() }.map { it.id to it.url to it.headers })
            }.collectLatest { key -> services.catalog.requestThumbnails(key.ids, key.network) }
        }
        viewModelScope.launch { services.catalog.snapshot.collect { next ->
            val changedSource = next.selectedSourceId != catalog.selectedSourceId
            catalog = next
            if (changedSource || group !in groups()) {
                selectedGroup = UiRules.restoredGroup(next)
                visibleIds = emptyList()
            }
        } }
        viewModelScope.launch { services.playback.snapshot.collect {
            playback = it
            if (it.phase==PlaybackPhase.IDLE) showControls()
        } }
    }
    fun navigate(next: Page) {
        cancelScreenshotRefresh(); cancelImport(); navigationGeneration++
        if (page == Page.PLAYER && next != Page.PLAYER) services.playback.stop()
        overlay = null; fullscreen = false; emptyPreview = false; emptyCollectionPreview = false
        if (page != next) history.addLast(page)
        page = next
        if (next in listOf(Page.SUBSCRIBE, Page.LOCAL, Page.MANUAL)) {
            formName = ""; formLink = ""; formGroup = "未分组"; fileUri = null; fileName = ""; editingChannelId = null; editingSourceId = null
        }
    }
    fun tab(next: Page) { navigate(next); history.clear(); searchOpen = false; query = "" }
    fun back() {
        if(overlay==null&&!showIndex&&!fullscreen){cancelImport();navigationGeneration++}
        when {
            showIndex -> showIndex = false
            overlay != null -> overlay = null
            fullscreen -> changeFullscreen(false)
            searchOpen && page == Page.HOME -> { searchOpen = false; query = "" }
            page == Page.PLAYER -> { services.playback.stop(); page = history.removeLastOrNull() ?: Page.HOME; fullscreen = false }
            history.isNotEmpty() -> page = history.removeLast()
            page != Page.HOME -> page = Page.HOME
        }
    }
    fun operation(block: suspend () -> Outcome<Unit>, success: String? = null, onSuccess: (() -> Unit)? = null) {
        if (busy) return
        val originPage = page; val originGeneration = navigationGeneration
        viewModelScope.launch { busy = true; try {
            when (val result = block()) { is Outcome.Failure -> overlay = Overlay.Error("操作未完成", result.message); is Outcome.Success -> { if (success != null) toast = success; if(page==originPage && navigationGeneration==originGeneration) onSuccess?.invoke() } }
        } finally { busy = false } }
    }
    /** Switches the current source and stays on the calling page (management keeps its tab). */
    fun selectSource(source: LiveSource) { cancelScreenshotRefresh(); if(page == Page.PLAYER)services.playback.stop(); navigationGeneration++;overlay = null; operation({services.catalog.selectSource(source.id)}); query = ""; searchOpen = false }
    fun favorite(c: Channel) { if(page==Page.PLAYER)showControls();overlay = null; operation({ services.catalog.setFavorite(c.id,c.id !in catalog.favoriteIds) }) }
    fun play(c: Channel, consent: Boolean = false) {
        if (!catalogReady || catalogError != null) return
        cancelScreenshotRefresh(); navigationGeneration++
        if (mobileNetwork && catalog.settings.mobileReminderEnabled && !consent) { overlay = Overlay.Network(c); return }
        if (!services.demo && !services.backendReady) { overlay = Overlay.Error("播放器尚未接入", "当前为 UI 验收版本，播放器由独立模块提供。"); return }
        if (page != Page.PLAYER) history.addLast(page)
        page = Page.PLAYER; overlay = null; previewPhase = null; previewExpired = false; playerGroup = c.group; playSession++; showControls()
        services.playback.play(c)
        viewModelScope.launch { services.catalog.recordWatched(c.id) }
    }
    fun consent(c: Channel) { operation({ services.catalog.updateSettings(catalog.settings.copy(mobileReminderEnabled=false)) }); play(c,true) }
    fun skip(delta: Int) { currentChannel?.let { play(UiRules.nextChannel(catalog.channels,it,delta)) } }
    fun pauseToggle() { showControls(); if (playback.phase == PlaybackPhase.PAUSED) services.playback.resume() else services.playback.pause() }
    fun volume(value: Int) { showControls(); services.playback.setVolume(value.coerceIn(0,100)) }
    fun edit(c: Channel) {
        navigate(Page.MANUAL); editingChannelId=c.id; formName=c.name;formLink=c.url;formGroup=c.group
    }
    fun editSource(source: LiveSource) { navigate(Page.EDIT_SOURCE);editingSourceId=source.id;formName=source.name;formLink=source.location.orEmpty();fileUri=null }
    fun delete(c: Channel) { overlay=Overlay.Confirm("删除此频道？", "「${c.name}」将从自建频道中移除，此操作无法撤销。", "删除频道",true) { operation({ services.catalog.deleteCustomChannel(c.id) },"频道已删除") } }
    fun delete(source: LiveSource) { overlay=Overlay.Confirm("删除直播源？", "删除后将同时移除该源的频道、截图缓存、收藏和观看记录，此操作无法撤销。", "删除直播源",true) { operation({ services.catalog.deleteSource(source.id) },"直播源已删除") } }
    fun saveChannel() {
        if (formName.isBlank() || !UiRules.validHttpAddress(formLink)) { toast="请填写频道名称和有效的 HTTP/HTTPS 播放链接";return }
        val channelId=editingChannelId;val draft=ChannelDraft(formName.trim(),formLink.trim(),formGroup)
        operation({ services.catalog.saveCustomChannel(channelId, draft) },"频道已保存到自建频道") {page=Page.HOME;history.clear()}
    }
    fun parse(local: Boolean = page == Page.LOCAL) {
        if (busy) return
        if (formName.isBlank()) { toast="请输入直播源名称";return }
        if (local && fileUri == null) { toast="请先选择频道文件";return }
        if (!local && !UiRules.validHttpAddress(formLink)) { toast="请输入有效的 HTTP/HTTPS 订阅链接";return }
        val request = if (local) ImportRequest.LocalFile(fileUri!!) else ImportRequest.Subscription(formLink.trim())
        val duplicate = catalog.sources.firstOrNull { it.kind == SourceKind.SUBSCRIPTION && it.location == formLink.trim() && !local && it.id != editingSourceId }
        if (duplicate != null) { overlay=Overlay.Confirm("该订阅已存在", "可以刷新「${duplicate.name}」，不会创建重复直播源。", "刷新已有源") { operation({services.catalog.refreshSource(duplicate.id)},"订阅已更新") };return }
        val token=++importGeneration;val origin=page
        report=null;busy=true
        importJob=viewModelScope.launch { try {
            val result=services.importer.inspect(request)
            if(token!=importGeneration||page!=origin)return@launch
            when (result) {
                is Outcome.Failure -> overlay=Overlay.Error("导入失败",result.message)
                is Outcome.Success -> { report=result.value;importRequest=request;page=Page.RESULT; if(services.demo) toast="演示解析：使用示例结果，未访问订阅内容" }
            }
        } finally { if(token==importGeneration)busy=false } }
    }
    fun commit() {
        val r=report ?: return; val request=importRequest ?: return
        val name=formName.trim();val replaceId=editingSourceId
        operation({services.catalog.commitImport(name,request,r,replaceId)},"直播源已导入") {page=Page.HOME;history.clear();group=r.channels.firstOrNull()?.group ?: "未分组"}
    }
    fun saveSource() {
        val id=editingSourceId ?: return
        if(formName.isBlank()) {toast="请输入直播源名称";return}
        val source=catalog.sources.firstOrNull {it.id==id} ?: return
        if(source.kind==SourceKind.LOCAL_FILE && fileUri!=null) { parse(true);return }
        if(source.kind==SourceKind.SUBSCRIPTION && !UiRules.validHttpAddress(formLink)) {toast="请输入有效订阅链接";return}
        val name = formName.trim()
        val location = if(source.kind==SourceKind.SUBSCRIPTION) formLink.trim() else source.location
        operation({services.catalog.updateSource(id,name,location)},"直播源信息已保存") {page=Page.SOURCES}
    }
    fun preview(target: String) {
        if (!services.demo) return
        overlay=null;showIndex=false;emptyPreview=false;emptyCollectionPreview=false;searchOpen=false;query="";fullscreen=false;previewPhase=null;previewExpired=false
        when(target) {
            "emptyHome" -> {page=Page.HOME;emptyPreview=true}
            "emptyFavorites" -> {page=Page.FAVORITES;emptyCollectionPreview=true}
            "emptyRecent" -> {page=Page.RECENT;emptyCollectionPreview=true}
            "searchEmpty" -> {page=Page.HOME;searchOpen=true;query="不存在的频道"}
            "playerChannels","playerRemote","fullscreen","loading","playFailure","expired","network" -> {
                catalog.channels.firstOrNull()?.let { play(it,true) };remote=target=="playerRemote";fullscreen=target=="fullscreen"
                previewPhase=when(target) {"loading" -> PlaybackPhase.CONNECTING;"playFailure","expired" -> PlaybackPhase.FAILED;else -> null};previewExpired=target=="expired"
                if(target=="network") currentChannel?.let {overlay=Overlay.Network(it)}
            }
            "sourceSwitch" -> {page=Page.MANAGEMENT}
            "importResult" -> {page=Page.RESULT;formName="我的新订阅";report=ImportReport(List(128){ChannelDraft("频道 $it","https://example.com/$it","分组 ${it%12}")},3);importRequest=ImportRequest.Subscription("https://example.com/demo.m3u")}
            "channelMenu","recentMenu" -> {page=if(target=="recentMenu") Page.RECENT else Page.HOME;catalog.channels.firstOrNull{it.id==if(target=="channelMenu") "c15" else "c2"}?.let{overlay=Overlay.Menu(it)}}
            "deleteSource" -> {page=Page.SOURCES;catalog.sources.firstOrNull {it.kind==SourceKind.SUBSCRIPTION}?.let {delete(it)}}
            "deleteChannel" -> {page=Page.HOME;catalog.channels.firstOrNull {it.id=="c15"}?.let {delete(it)}}
            "editManual" -> catalog.channels.firstOrNull {it.id=="c15"}?.let {edit(it)}
            "editSourceOnline","editSourceLocal" -> catalog.sources.firstOrNull {it.kind==if(target=="editSourceLocal") SourceKind.LOCAL_FILE else SourceKind.SUBSCRIPTION}?.let{editSource(it)}
            "duplicate","refreshFail","importFail","downloadFail","addressMissing","accessDenied" -> {
                page=if(target=="refreshFail") Page.HOME else if(target=="importFail") Page.LOCAL else Page.SUBSCRIBE
                val pair=when(target){"duplicate"->"该订阅已存在" to "这个链接已经添加，可以刷新已有的订阅。";"refreshFail"->"刷新失败" to "暂时无法连接服务器，原有频道列表已保留。";"importFail"->"无法识别此文件" to "未找到有效的频道信息，请检查文件格式。";"addressMissing"->"找不到此地址" to "请确认订阅链接是否正确。";"accessDenied"->"无法访问此订阅" to "服务器拒绝了本次访问，请检查链接权限。";else->"下载失败" to "暂时无法下载此订阅，请检查网络后重试。"}
                overlay=Overlay.Error(pair.first,pair.second)
            }
            else -> navigate(when(target){"favorites"->Page.FAVORITES;"recent"->Page.RECENT;"sources"->Page.SOURCES;"add"->Page.ADD;"subscribe"->Page.SUBSCRIBE;"local"->Page.LOCAL;"manual"->Page.MANUAL;"management"->Page.MANAGEMENT;"about"->Page.ABOUT;else->Page.HOME})
        }
    }
    fun refreshPlayingSource() {
        val channel = currentChannel ?: return
        operation({ services.catalog.refreshSource(channel.sourceId) }, "订阅已更新") {
            services.catalog.snapshot.value.channels.firstOrNull { it.id == channel.id }?.let {
                if (foreground) play(it, true) else pendingRefresh = navigationGeneration to it
            }
                ?: back()
        }
    }
    override fun onCleared() { try { services.playback.release() } finally { services.closeCatalog() } }
}
