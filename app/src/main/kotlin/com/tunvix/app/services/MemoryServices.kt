package com.tunvix.app.services

import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import com.tunvix.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.math.roundToInt

/** UI-only adapter. Backend modules replace this; live starts empty and never seeds demo sources. */
class MemoryCatalog(initial: CatalogSnapshot = CatalogSnapshot()) : CatalogRepository {
    private val data = MutableStateFlow(initial)
    override val snapshot: StateFlow<CatalogSnapshot> = data
    private fun change(transform: (CatalogSnapshot) -> CatalogSnapshot): Outcome<Unit> { data.value = transform(data.value); return Outcome.Success(Unit) }
    private fun customSource(state: CatalogSnapshot) = state.sources.firstOrNull { it.kind == SourceKind.CUSTOM }
    override suspend fun selectSource(id: String) = if (data.value.sources.any { it.id == id }) change { it.copy(selectedSourceId = id) } else Outcome.Failure("直播源不存在")
    override suspend fun commitImport(name: String, request: ImportRequest, report: ImportReport, replaceSourceId: String?): Outcome<Unit> {
        if (report.channels.isEmpty()) return Outcome.Failure("没有有效频道")
        val location = when(request) { is ImportRequest.Subscription -> request.url; is ImportRequest.LocalFile -> request.uri }
        if (replaceSourceId == null && data.value.sources.any { it.location == location }) return Outcome.Failure("该订阅已存在，请刷新已有直播源", "duplicate")
        val id = replaceSourceId ?: UUID.randomUUID().toString()
        val source = LiveSource(id, name, if (request is ImportRequest.Subscription) SourceKind.SUBSCRIPTION else SourceKind.LOCAL_FILE, location)
        val channels = report.channels.map { d -> Channel(UUID.randomUUID().toString(), id, d.name, d.url, d.group, d.logoUrl, headers = d.headers, externalId = d.externalId) }
        return change { it.copy(sources = it.sources.filterNot { x -> x.id == id } + source, channels = it.channels.filterNot { c -> c.sourceId == id } + channels, selectedSourceId = id) }
    }
    override suspend fun refreshSource(id: String): Outcome<Unit> = Outcome.Failure("当前 UI 版本尚未接入订阅刷新")
    override suspend fun updateSource(id: String, name: String, location: String?) = change { it.copy(sources = it.sources.map { s -> if (s.id == id) s.copy(name = name, location = location) else s }) }
    override suspend fun deleteSource(id: String): Outcome<Unit> {
        val removed = data.value.channels.filter { it.sourceId == id }.map { it.id }.toSet()
        return change { s -> val sources = s.sources.filterNot { it.id == id }; s.copy(sources = sources, channels = s.channels.filterNot { it.id in removed }, favoriteIds = s.favoriteIds - removed, recentIds = s.recentIds.filterNot { it in removed }, selectedSourceId = if (s.selectedSourceId == id) sources.firstOrNull()?.id else s.selectedSourceId) }
    }
    override suspend fun saveCustomChannel(channelId: String?, draft: ChannelDraft): Outcome<Unit> {
        if (channelId != null && data.value.channels.none { it.id == channelId && it.sourceId == customSource(data.value)?.id }) return Outcome.Failure("只能编辑自建频道")
        return change { s ->
            val source = customSource(s) ?: LiveSource("custom", "自建频道", SourceKind.CUSTOM)
            val channel = Channel(channelId ?: UUID.randomUUID().toString(), source.id, draft.name, draft.url, draft.group, draft.logoUrl, headers = draft.headers)
            s.copy(sources = if (source in s.sources) s.sources else s.sources + source, channels = s.channels.filterNot { it.id == channel.id } + channel, selectedSourceId = source.id)
        }
    }
    override suspend fun deleteCustomChannel(id: String): Outcome<Unit> {
        if (data.value.channels.none { it.id == id && it.sourceId == customSource(data.value)?.id }) return Outcome.Failure("只能删除自建频道")
        return change { it.copy(channels = it.channels.filterNot { c -> c.id == id }, favoriteIds = it.favoriteIds - id, recentIds = it.recentIds - id) }
    }
    override suspend fun setFavorite(id: String, favorite: Boolean) = change { it.copy(favoriteIds = if (favorite) it.favoriteIds + id else it.favoriteIds - id) }
    override suspend fun recordWatched(id: String) = change { it.copy(recentIds = listOf(id) + it.recentIds.filterNot { x -> x == id }) }
    override suspend fun removeRecent(id: String) = change { it.copy(recentIds = it.recentIds - id) }
    override suspend fun clearRecents() = change { it.copy(recentIds = emptyList()) }
    override suspend fun updateSettings(settings: UserSettings) = change { it.copy(settings = settings) }
    override suspend fun rememberBrowseGroup(sourceId: String, group: String) = change { it.copy(settings = it.settings.copy(browseGroups = it.settings.browseGroups + (sourceId to group))) }
    override suspend fun setThemeMode(mode: ThemeMode) = change { it.copy(settings = it.settings.copy(themeMode = mode)) }
    override suspend fun clearThumbnailCache() = change { it.copy(cacheBytes = 0, channels = it.channels.map { c -> c.copy(thumbnailUri = null) }) }
    override suspend fun requestThumbnails(channelIds: List<String>, allowNetwork: Boolean): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun refreshThumbnails(channelIds: List<String>): Outcome<Unit> = Outcome.Success(Unit)
}

/** Shows supplied stills, while volume controls use actual Android media volume. Does not stream. */
class PreviewPlayback(context: Context) : PlaybackController {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val data = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = data
    private var explicitPause = false
    override fun attachSurface(surface: SurfaceView) = Unit
    override fun detachSurface(surface: SurfaceView) = Unit
    override fun play(channel: Channel) { explicitPause = false; data.value = data.value.copy(channelId = channel.id, phase = PlaybackPhase.PLAYING, errorMessage = null); refreshVolume() }
    override fun pause() { explicitPause = true; data.value = data.value.copy(phase = PlaybackPhase.PAUSED) }
    override fun resume() { explicitPause = false; data.value = data.value.copy(phase = PlaybackPhase.PLAYING) }
    override fun retry() { resume() }
    override fun stop() { data.value = data.value.copy(channelId = null, phase = PlaybackPhase.IDLE, audioOnly = false); explicitPause = false }
    override fun setAudioOnly(enabled: Boolean) { data.value=data.value.copy(audioOnly=enabled) }
    override fun setVolume(percent: Int) { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0,100) * max / 100f).roundToInt(), 0); refreshVolume() }
    override fun refreshVolume() { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1); data.value = data.value.copy(volumePercent = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max) }
    override fun onBackground() { if (data.value.channelId != null) data.value = data.value.copy(phase = PlaybackPhase.PAUSED) }
    override fun onForeground() { if (data.value.channelId != null && !explicitPause) data.value = data.value.copy(phase = PlaybackPhase.PLAYING); refreshVolume() }
    override fun release() = stop()
}
