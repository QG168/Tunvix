package com.tunvix.contract

import android.view.SurfaceView
import kotlinx.coroutines.flow.StateFlow

/** v1 integration contract: no Compose dependencies. IDs remain stable across refreshes. */
enum class SourceKind { SUBSCRIPTION, LOCAL_FILE, CUSTOM }
data class LiveSource(val id: String, val name: String, val kind: SourceKind, val location: String? = null, val updatedAt: Long? = null)
data class Channel(
    val id: String, val sourceId: String, val name: String, val url: String,
    val group: String = "未分组", val logoUrl: String? = null, val thumbnailUri: String? = null,
    val headers: Map<String, String> = emptyMap(), val externalId: String? = null,
    /** Latest persisted first-frame measurement for this exact media; null when never measured. */
    val firstFrame: FirstFrameResult? = null,
    val watchedAt: Long? = null,
)

/** First-frame latency: media request issued until the first decodable frame, excluding queueing and JPEG compression. */
enum class FirstFrameStatus { SUCCESS, TIMEOUT, FAILED }
data class FirstFrameResult(val status: FirstFrameStatus, val millis: Long? = null, val measuredAt: Long = 0)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val mobileReminderEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** When false no detection-only requests are made; stored results are kept for reuse. */
    val firstFrameEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val browseGroups: Map<String, String> = emptyMap(),
    val autoPictureInPicture: Boolean = false,
    val previewDetectionEnabled: Boolean = true,
    val autoEpgUpdateEnabled: Boolean = true,
    val customGroups: List<String> = emptyList(),
    val mergeSameNameChannels: Boolean = true,
    val defaultPlayerPanel: String = "channels",
    val softViewingEnabled: Boolean = true,
)
data class CatalogSnapshot(
    val sources: List<LiveSource> = emptyList(), val channels: List<Channel> = emptyList(),
    val selectedSourceId: String? = null, val favoriteIds: Set<String> = emptySet(),
    val recentIds: List<String> = emptyList(), val settings: UserSettings = UserSettings(),
    val cacheBytes: Long = 0,
    /** Channels with a detection-only measurement in flight right now; never persisted. */
    val detectingIds: Set<String> = emptySet(),
    val capturingIds: Set<String> = emptySet(),
)
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val message: String, val code: String? = null) : Outcome<Nothing>
}
sealed interface ImportRequest {
    data class Subscription(val url: String) : ImportRequest
    data class LocalFile(val uri: String) : ImportRequest
}
data class ChannelDraft(
    val name: String, val url: String, val group: String = "未分组", val logoUrl: String? = null,
    val headers: Map<String, String> = emptyMap(), val externalId: String? = null,
)
data class ImportReport(val channels: List<ChannelDraft>, val skippedCount: Int = 0) {
    val groupCount: Int get() = channels.map { it.group }.distinct().size
}
interface SourceImporter { suspend fun inspect(request: ImportRequest): Outcome<ImportReport> }

interface CatalogRepository {
    val snapshot: StateFlow<CatalogSnapshot>
    suspend fun selectSource(id: String): Outcome<Unit>
    suspend fun commitImport(name: String, request: ImportRequest, report: ImportReport, replaceSourceId: String? = null): Outcome<Unit>
    suspend fun refreshSource(id: String): Outcome<Unit>
    suspend fun updateSource(id: String, name: String, location: String?): Outcome<Unit>
    suspend fun deleteSource(id: String): Outcome<Unit>
    suspend fun saveCustomChannel(channelId: String?, draft: ChannelDraft): Outcome<Unit>
    suspend fun deleteCustomChannel(id: String): Outcome<Unit>
    suspend fun addCustomGroup(name: String): Outcome<Unit> = Outcome.Failure("分组管理暂不可用")
    suspend fun deleteCustomGroup(name: String): Outcome<Unit> = Outcome.Failure("分组管理暂不可用")
    suspend fun setFavorite(id: String, favorite: Boolean): Outcome<Unit>
    suspend fun recordWatched(id: String): Outcome<Unit>
    suspend fun removeRecent(id: String): Outcome<Unit>
    suspend fun clearRecents(): Outcome<Unit>
    suspend fun updateSettings(settings: UserSettings): Outcome<Unit>
    suspend fun clearThumbnailCache(): Outcome<Unit>
    suspend fun rememberBrowseGroup(sourceId: String, group: String): Outcome<Unit> = Outcome.Failure("分组保存暂不可用")
    suspend fun setThemeMode(mode: ThemeMode): Outcome<Unit> = updateSettings(snapshot.value.settings.copy(themeMode = mode))
    /** Best-effort playback still; identity must match and existing images are retained. */
    suspend fun savePlaybackThumbnail(channel: Channel, jpeg: ByteArray): Outcome<Unit> = Outcome.Failure("播放截图暂不可用")
    /** Ordered viewport + nearby IDs; cached frames are retained. False cancels capture. */
    suspend fun requestThumbnails(channelIds: List<String>, allowNetwork: Boolean): Outcome<Unit>
    /** Three bounded workers recapture channels; failed capture/commit retains the old frame. */
    suspend fun refreshThumbnails(channelIds: List<String>): Outcome<Unit> = Outcome.Failure("截图刷新暂不可用")
}

enum class PlaybackPhase { IDLE, CONNECTING, PLAYING, PAUSED, FAILED }
data class PlaybackSnapshot(
    val channelId: String? = null, val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val volumePercent: Int = 50, val errorMessage: String? = null, val mayBeExpired: Boolean = false,
    val videoAspectRatio: Float = 16f / 9f,
    /** True only after a frame has been rendered for the current prepare cycle. */
    val videoFrameReady: Boolean = false,
    /** Session-only; never stored in the catalog. */
    val audioOnly: Boolean = false,
    val sleepTimerDeadlineElapsed: Long? = null,
    val sleepTimerRemainingSeconds: Long = 0,
    val sleepTimerMinutes: Int = 0,
)
interface PlaybackController {
    val snapshot: StateFlow<PlaybackSnapshot>
    fun attachSurface(surface: SurfaceView)
    fun detachSurface(surface: SurfaceView)
    fun attachTexture(surface: android.view.TextureView) {}
    fun detachTexture(surface: android.view.TextureView) {}
    fun play(channel: Channel)
    fun pause()
    fun resume()
    fun retry()
    fun stop()
    fun setVolume(percent: Int)
    fun refreshVolume()
    fun setAudioOnly(enabled: Boolean) {}
    fun setSleepTimer(minutes: Int) {}
    /** Current browse group, already consolidated according to the merge setting. */
    fun setChannelQueue(channels: List<Channel>) {}
    fun setBackgroundPlayback(enabled: Boolean) {}
    /** Suspend data pulling on background entry without forgetting an explicit user pause. */
    fun onBackground()
    fun onForeground()
    fun release()
}
