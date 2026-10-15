package com.tunvix.backend.catalog

import android.content.Context
import com.tunvix.contract.CatalogRepository
import com.tunvix.contract.CatalogSnapshot
import com.tunvix.contract.Channel
import com.tunvix.contract.ChannelDraft
import com.tunvix.contract.FirstFrameResult
import com.tunvix.contract.FirstFrameStatus
import com.tunvix.contract.ImportReport
import com.tunvix.contract.ImportRequest
import com.tunvix.contract.LiveSource
import com.tunvix.contract.Outcome
import com.tunvix.contract.SourceImporter
import com.tunvix.contract.SourceKind
import com.tunvix.contract.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local catalog: sources, channels, favorites, watch history, settings and the still-image cache.
 *
 * Everything is persisted on the device — no account, no server. The public snapshot is always an
 * immutable [CatalogSnapshot]; mutations are serialized, written in one atomic step and only then
 * published, so a failed refresh can never leave the UI with half of the old list and half of the new.
 *
 * It only ever talks to the importer through the [SourceImporter] contract and never reaches into the
 * importer implementation.
 */
class LocalCatalogRepository internal constructor(
    private val store: CatalogStore,
    private val importer: SourceImporter,
    private val thumbnails: ThumbnailStore,
    private val thumbnailSource: ThumbnailSource,
    private val uriAccess: UriAccess = UriAccess.AlwaysGranted,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * When true the on-disk catalog is read on [io] *after* construction instead of on the calling
     * thread. `snapshot` then starts out as an empty fresh install and publishes the real catalog
     * once [ready] flips to true, so assembling the repository never blocks the main thread.
     * The [CatalogRepository] contract is unchanged — consumers still only ever observe a
     * [CatalogSnapshot]; [ready] is just an extra signal on this class for the UI to show a
     * loading state. Every mutation waits for the read to finish, so nothing can be written (and
     * thus overwrite the not-yet-read catalog) before it has been loaded.
     */
    asyncInit: Boolean = false,
) : CatalogRepository {

    constructor(context: Context, importer: SourceImporter) : this(
        store = FileCatalogStore(File(context.applicationContext.filesDir, "catalog/catalog.tsv")),
        importer = importer,
        thumbnails = ThumbnailStore(File(context.applicationContext.filesDir, "thumbnails"),
            legacyDirectory = File(context.applicationContext.cacheDir, "thumbnails")),
        thumbnailSource = AndroidThumbnailSource(context.applicationContext),
        uriAccess = AndroidUriAccess(context.applicationContext),
        // Startup must not do file IO on the main thread.
        asyncInit = true,
    )

    private val workScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + io)
    private val ownsScope = scope == null
    private val writeLock = Mutex()
    private val extractionLock = Mutex()
    private val refreshLock = Mutex()
    private val automaticWorkers = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var manualJob: Job? = null
    @Volatile private var manualIds: Set<String> = emptySet()
    private val extractionSlots = Semaphore(ThumbnailLimits.MAX_CONCURRENT)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    /** Channels whose running job is a detection-only measurement; surfaced as [CatalogSnapshot.detectingIds]. */
    private val pureDetectionJobs = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var requestedThumbnailIds: List<String> = emptyList()
    @Volatile private var requestedThumbnailChannels: List<Channel> = emptyList()
    @Volatile private var automaticThumbnailsAllowed = false
    @Volatile private var manualThumbnailsRunning = false
    private val attemptedInBatch = ConcurrentHashMap<String,String>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    /**
     * Bumped whenever a source's identity changes: its address/name is edited, it is re-imported,
     * or a new refresh of it starts. A refresh remembers the generation it claimed and refuses to
     * commit once the generation has moved on, so a slow refresh can never write back the address
     * or channel list the user has already replaced, and an older refresh can never overwrite the
     * result of a newer one.
     */
    private val generations = ConcurrentHashMap<String, Long>()

    /** Completed once the on-disk catalog has been read; gates every mutation. */
    private val initGate = CompletableDeferred<Unit>()
    private val readyState = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)
    /** Initialization has finished, but failed reads must never permit an empty overwrite. */
    val initializationError: StateFlow<String?> = loadError.asStateFlow()

    @Volatile
    private var state: StoredCatalog

    private val mutable: MutableStateFlow<CatalogSnapshot>

    init {
        if (asyncInit) {
            state = StoredCatalog.freshInstall()
            mutable = MutableStateFlow(state.toSnapshot())
            workScope.launch { loadPersistedState() }
        } else {
            thumbnails.initialize()
            state = store.read()
            thumbnails.retain(state.channels.mapNotNull { it.thumbnailName }.toSet())
            mutable = MutableStateFlow(state.toSnapshot())
            readyState.value = true
            initGate.complete(Unit)
        }
    }

    /** True once [snapshot] reflects what is on disk. Always true when init is synchronous. */
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    override val snapshot: StateFlow<CatalogSnapshot> = mutable.asStateFlow()

    /** Suspends until [ready]; useful for tests and for one-shot readers. */
    suspend fun awaitReady() = initGate.await()

    private suspend fun loadPersistedState() {
        try {
            val loaded = withContext(io) {
                val catalog = store.read()
                thumbnails.initialize()
                thumbnails.retain(catalog.channels.mapNotNull { it.thumbnailName }.toSet())
                catalog
            }
            writeLock.withLock {
                state = loaded
                mutable.value = loaded.toSnapshot()
            }
            readyState.value = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError.value = "本地数据读取失败，已停止保存以保护原有数据；请重启后重试"
            readyState.value = true
        } finally {
            initGate.complete(Unit)
        }
    }

    /** Cancels background work; only the internally created scope is shut down. */
    fun close() {
        automaticThumbnailsAllowed=false
        manualJob?.cancel()
        cancelExtractions()
        if (ownsScope) workScope.cancel()
    }

    override suspend fun selectSource(id: String): Outcome<Unit> = mutate { current ->
        if (current.sources.none { it.id == id }) Outcome.Failure("直播源不存在", "missing")
        else Outcome.Success(current.copy(selectedSourceId = id))
    }

    override suspend fun commitImport(
        name: String,
        request: ImportRequest,
        report: ImportReport,
        replaceSourceId: String?,
    ): Outcome<Unit> = mutate { current ->
        val cleanName = name.trim()
        val location = when (request) {
            is ImportRequest.Subscription -> request.url.trim()
            is ImportRequest.LocalFile -> request.uri.trim()
        }
        val kind = when (request) {
            is ImportRequest.Subscription -> SourceKind.SUBSCRIPTION
            is ImportRequest.LocalFile -> SourceKind.LOCAL_FILE
        }
        val drafts = report.channels.deduplicated()
        when {
            cleanName.isEmpty() -> Outcome.Failure("请填写直播源名称", "name")
            location.isEmpty() -> Outcome.Failure("请填写直播源地址", "location")
            drafts.isEmpty() -> Outcome.Failure("没有可导入的有效频道", "empty")
            else -> {
                val target = replaceSourceId?.let { id -> current.sources.firstOrNull { it.id == id } }
                when {
                    replaceSourceId != null && target == null -> Outcome.Failure("直播源不存在", "missing")
                    target?.kind == SourceKind.CUSTOM -> Outcome.Failure("自建频道源不能通过导入更新", "custom")
                    current.sources.any { it.id != replaceSourceId && it.kind != SourceKind.CUSTOM && it.location == location } ->
                        Outcome.Failure("该订阅已存在，请刷新已有直播源", "duplicate")

                    else -> {
                        val id = target?.id ?: UUID.randomUUID().toString()
                        // Re-importing over an existing source changes its identity: any refresh
                        // still in flight for it must be discarded rather than write the old list back.
                        if (target != null) nextGeneration(id)
                        val previous = current.channels.filter { it.sourceId == id }
                        val (channels, _) = buildChannels(id, drafts, previous)
                        val source = StoredSource(id, cleanName, kind, location, clock())
                        Outcome.Success(
                            current.copy(
                                sources = if (target == null) current.sources + source
                                else current.sources.map { if (it.id == id) source else it },
                                channels = replaceChannels(current.channels, id, channels),
                                selectedSourceId = id,
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun refreshSource(id: String): Outcome<Unit> {
        initGate.await()
        if (loadError.value != null) return Outcome.Failure(loadError.value!!, "storage")
        // Reading the source and claiming its generation must be one atomic operation.
        val attempt = writeLock.withLock {
            state.sources.firstOrNull { it.id == id }?.let { it to nextGeneration(id) }
        } ?: return Outcome.Failure("直播源不存在", "missing")
        val (source, generation) = attempt
        if (source.kind == SourceKind.CUSTOM) {
            return Outcome.Failure("自建频道请逐条编辑，无需刷新", "custom")
        }
        val location = source.location?.trim().orEmpty()
        if (location.isEmpty()) return Outcome.Failure("该直播源没有可刷新的地址", "location")

        if (source.kind == SourceKind.LOCAL_FILE && !uriAccess.canRead(location)) {
            return Outcome.Failure("本地文件授权已失效，请重新导入该文件", "uri_permission")
        }
        val request = if (source.kind == SourceKind.SUBSCRIPTION) {
            ImportRequest.Subscription(location)
        } else {
            ImportRequest.LocalFile(location)
        }

        // Network work happens outside the write lock; a slow host cannot block other operations.
        val report = when (val inspected = importer.inspect(request)) {
            is Outcome.Failure -> return Outcome.Failure(inspected.message, inspected.code)
            is Outcome.Success -> inspected.value
        }
        val drafts = report.channels.deduplicated()
        if (drafts.isEmpty()) return Outcome.Failure("刷新没有返回有效频道，已保留原有列表", "empty")

        return mutate { current ->
            when {
                current.sources.none { it.id == id } -> Outcome.Failure("直播源不存在", "missing")
                generationOf(id) != generation ->
                    Outcome.Failure("刷新期间直播源已被修改，本次结果已丢弃", "stale")

                else -> {
                    val previous = current.channels.filter { it.sourceId == id }
                    val (channels, _) = buildChannels(id, drafts, previous)
                    Outcome.Success(
                        current.copy(
                            sources = current.sources.map { if (it.id == id) it.copy(location = location, updatedAt = clock()) else it },
                            channels = replaceChannels(current.channels, id, channels),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun updateSource(id: String, name: String, location: String?): Outcome<Unit> = mutate { current ->
        val source = current.sources.firstOrNull { it.id == id }
        val cleanName = name.trim()
        when {
            source == null -> Outcome.Failure("直播源不存在", "missing")
            cleanName.isEmpty() -> Outcome.Failure("请填写直播源名称", "name")
            source.kind == SourceKind.CUSTOM ->
                Outcome.Success(current.copy(sources = current.sources.map { if (it.id == id) it.copy(name = cleanName) else it }))

            else -> {
                val cleanLocation = location?.trim().orEmpty()
                when {
                    cleanLocation.isEmpty() -> Outcome.Failure("请填写订阅地址", "location")
                    current.sources.any { it.id != id && it.kind != SourceKind.CUSTOM && it.location == cleanLocation } ->
                        Outcome.Failure("该订阅已存在，请刷新已有直播源", "duplicate")

                    else -> {
                        // A new address invalidates any refresh already in flight for this source.
                        nextGeneration(id)
                        Outcome.Success(
                            current.copy(
                                sources = current.sources.map {
                                    if (it.id == id) it.copy(name = cleanName, location = cleanLocation) else it
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun deleteSource(id: String): Outcome<Unit> = mutate { current ->
        val source = current.sources.firstOrNull { it.id == id }
        when {
            source == null -> Outcome.Failure("直播源不存在", "missing")
            else -> {
                val sources = current.sources.filterNot { it.id == id }
                val channels = current.channels.filterNot { it.sourceId == id }
                val selected = if (current.selectedSourceId == id) pickSource(sources, channels) else current.selectedSourceId
                Outcome.Success(current.copy(sources = sources, channels = channels, selectedSourceId = selected))
            }
        }
    }

    override suspend fun saveCustomChannel(channelId: String?, draft: ChannelDraft): Outcome<Unit> = mutate { current ->
        val name = draft.name.trim()
        val url = draft.url.trim()
        when {
            name.isEmpty() -> Outcome.Failure("请填写频道名称", "name")
            !isHttpAddress(url) -> Outcome.Failure("请填写以 http:// 或 https:// 开头的播放地址", "url")
            channelId != null && current.channels.none { it.id == channelId && it.sourceId == CUSTOM_SOURCE_ID } ->
                Outcome.Failure("只能编辑自建频道", "not_custom")

            else -> {
                val channel = StoredChannel(
                    id = channelId ?: UUID.randomUUID().toString(),
                    sourceId = CUSTOM_SOURCE_ID,
                    name = name,
                    url = url,
                    group = ChannelIdentity.normalizedGroup(draft.group),
                    logoUrl = draft.logoUrl,
                    externalId = draft.externalId,
                    headers = draft.headers,
                )
                val previous = current.channels.firstOrNull { it.id == channel.id }
                val merged = if (previous == null) channel else {
                val sameMedia = ChannelIdentity.fingerprint(previous.url, previous.headers) ==
                    ChannelIdentity.fingerprint(channel.url, channel.headers)
                channel.copy(
                    favorite = previous.favorite,
                    watchedAt = previous.watchedAt,
                    thumbnailName = if (sameMedia) previous.thumbnailName else null,
                    // A new address or headers invalidate the measurement along with the still.
                    firstFrame = if (sameMedia) previous.firstFrame else null,
                )
                }
                Outcome.Success(
                    current.copy(
                        sources = if (current.sources.any { it.id == CUSTOM_SOURCE_ID }) current.sources.map { if (it.id == CUSTOM_SOURCE_ID) it.copy(updatedAt = clock()) else it }
                        else current.sources + StoredSource(CUSTOM_SOURCE_ID, "自建频道", SourceKind.CUSTOM, null, clock()),
                        channels = if (previous == null) current.channels + merged
                        else current.channels.map { if (it.id == merged.id) merged else it },
                        selectedSourceId = CUSTOM_SOURCE_ID,
                    ),
                )
            }
        }
    }

    override suspend fun deleteCustomChannel(id: String): Outcome<Unit> = mutate { current ->
        val channel = current.channels.firstOrNull { it.id == id }
        when {
            channel == null -> Outcome.Failure("频道不存在", "missing")
            channel.sourceId != CUSTOM_SOURCE_ID -> Outcome.Failure("只能删除自建频道", "not_custom")
            else -> {
                Outcome.Success(current.copy(channels = current.channels.filterNot { it.id == id }))
            }
        }
    }

    override suspend fun addCustomGroup(name: String): Outcome<Unit> = mutate { current ->
        val group = name.trim()
        when {
            group.isEmpty() -> Outcome.Failure("请输入分组名称")
            group == DEFAULT_GROUP || group in current.settings.customGroups -> Outcome.Failure("分组已存在")
            else -> Outcome.Success(current.copy(settings = current.settings.copy(customGroups = current.settings.customGroups + group)))
        }
    }

    override suspend fun deleteCustomGroup(name: String): Outcome<Unit> = mutate { current ->
        if (name == DEFAULT_GROUP) Outcome.Failure("未分组不能删除")
        else Outcome.Success(current.copy(
            channels = current.channels.map { if (it.sourceId == CUSTOM_SOURCE_ID && it.group == name) it.copy(group = DEFAULT_GROUP) else it },
            settings = current.settings.copy(customGroups = current.settings.customGroups.filterNot { it == name }),
        ))
    }

    override suspend fun setFavorite(id: String, favorite: Boolean): Outcome<Unit> = mutate { current ->
        if (current.channels.none { it.id == id }) Outcome.Failure("频道不存在", "missing")
        else Outcome.Success(current.copy(channels = current.channels.map { if (it.id == id) it.copy(favorite = favorite) else it }))
    }

    override suspend fun recordWatched(id: String): Outcome<Unit> = mutate { current ->
        if (current.channels.none { it.id == id }) return@mutate Outcome.Failure("频道不存在", "missing")
        val now = clock()
        var channels = current.channels.map { if (it.id == id) it.copy(watchedAt = now) else it }
        val watched = channels.filter { it.watchedAt != null }.sortedByDescending { it.watchedAt }
        if (watched.size > RECENT_LIMIT) {
            val expired = watched.drop(RECENT_LIMIT).map { it.id }.toSet()
            channels = channels.map { if (it.id in expired) it.copy(watchedAt = null) else it }
        }
        Outcome.Success(current.copy(channels = channels))
    }

    override suspend fun removeRecent(id: String): Outcome<Unit> = mutate { current ->
        if (current.channels.none { it.id == id }) Outcome.Failure("频道不存在", "missing")
        else Outcome.Success(current.copy(channels = current.channels.map { if (it.id == id) it.copy(watchedAt = null) else it }))
    }

    override suspend fun clearRecents(): Outcome<Unit> = mutate { current ->
        Outcome.Success(current.copy(channels = current.channels.map { it.copy(watchedAt = null) }))
    }

    override suspend fun updateSettings(settings: UserSettings): Outcome<Unit> = mutate { current ->
        Outcome.Success(current.copy(settings = settings))
    }

    override suspend fun clearThumbnailCache(): Outcome<Unit> {
        automaticThumbnailsAllowed=false
        requestedThumbnailChannels=emptyList()
        attemptedInBatch.clear()
        manualJob?.let { it.cancel(); it.join() }
        cancelExtractionsAndJoin()
        cooldowns.clear()
        val result=mutate { current ->
            Outcome.Success(current.copy(channels = current.channels.map { it.copy(thumbnailName = null) }))
        }
        if(result is Outcome.Success)writeLock.withLock {
            thumbnails.retain(state.channels.mapNotNull { it.thumbnailName }.toSet())
            mutable.value=state.toSnapshot()
        }
        return result
    }

    override suspend fun rememberBrowseGroup(sourceId: String, group: String): Outcome<Unit> = mutate { current ->
        if (current.sources.none { it.id == sourceId } || (group != "全部" && current.channels.none { it.sourceId == sourceId && it.group == group })) Outcome.Failure("分组不存在")
        else Outcome.Success(current.copy(settings = current.settings.copy(browseGroups = current.settings.browseGroups + (sourceId to group))))
    }

    override suspend fun setThemeMode(mode: com.tunvix.contract.ThemeMode): Outcome<Unit> = mutate { current ->
        Outcome.Success(current.copy(settings = current.settings.copy(themeMode = mode)))
    }

    override suspend fun savePlaybackThumbnail(channel: Channel, jpeg: ByteArray): Outcome<Unit> = withContext(io) {
        if (jpeg.isEmpty()) return@withContext Outcome.Failure("截图为空")
        val fingerprint = ChannelIdentity.fingerprint(channel.url, channel.headers)
        val name = ThumbnailStore.fileName(channel.id, fingerprint + "-play-" + UUID.randomUUID().toString().take(8))
        try {
            val outcome = mutate { current ->
                val existing = current.channels.firstOrNull { it.id == channel.id }
                when {
                    existing == null || ChannelIdentity.fingerprint(existing.url, existing.headers) != fingerprint -> Outcome.Failure("频道已变化")
                    existing.thumbnailName != null -> Outcome.Success(current)
                    else -> {
                        thumbnails.write(name, jpeg)
                        Outcome.Success(current.copy(channels = current.channels.map { if (it.id == channel.id) it.copy(thumbnailName = name) else it }))
                    }
                }
            }
            if (state.channels.none { it.thumbnailName == name }) thumbnails.delete(name)
            outcome
        } catch (cancelled: CancellationException) {
            if (state.channels.none { it.thumbnailName == name }) thumbnails.delete(name)
            throw cancelled
        } catch (_: Exception) {
            thumbnails.delete(name)
            Outcome.Failure("截图保存失败", "storage")
        }
    }

    override suspend fun requestThumbnails(channelIds: List<String>, allowNetwork: Boolean): Outcome<Unit> = withContext(io) {
        initGate.await()
        if (loadError.value != null) return@withContext Outcome.Failure(loadError.value!!, "storage")
        requestedThumbnailIds = channelIds.distinct()
        automaticThumbnailsAllowed = allowNetwork
        val visible = if(allowNetwork) requestedThumbnailIds.toSet() else emptySet()
        val byId = mutable.value.channels.associateBy { it.id }
        val nextTargets=visible.mapNotNull { byId[it] }
        if(nextTargets.map { it.id to ChannelIdentity.fingerprint(it.url,it.headers) } != requestedThumbnailChannels.map { it.id to ChannelIdentity.fingerprint(it.url,it.headers) })attemptedInBatch.clear()
        else nextTargets.filter { it.thumbnailUri==null && !isCoolingDown(it,clock()) }.forEach { attemptedInBatch.remove(it.id) }
        requestedThumbnailChannels = nextTargets

        if(!allowNetwork || !visible.containsAll(manualIds))manualJob?.cancel()

        // Turning detection off cancels only detection-only jobs; genuine still capture continues.
        if(!mutable.value.settings.firstFrameEnabled)pureDetectionJobs.toList().forEach { id -> activeJobs[id]?.cancel() }

        // Switching the target group cancels only work no longer requested.
        activeJobs.keys.filterNot { it in visible }.forEach { stale -> activeJobs[stale]?.cancel() }

        pumpThumbnails()
        Outcome.Success(Unit)
    }

    /**
     * Next claimable work item for the shared extraction budget: a missing-still capture first,
     * then a detection-only first-frame measurement for channels that already have a still.
     */
    private fun nextWork(): Pair<Channel, Boolean>? {
        val now=clock()
        val latest=mutable.value.channels.associateBy { it.id }
        val detectEnabled=mutable.value.settings.firstFrameEnabled
        requestedThumbnailChannels.forEach { requested ->
            val channel=latest[requested.id] ?: return@forEach
            val fingerprint=ChannelIdentity.fingerprint(channel.url,channel.headers)
            if(fingerprint!=ChannelIdentity.fingerprint(requested.url,requested.headers))return@forEach
            if(attemptedInBatch[channel.id]==fingerprint)return@forEach
            if(isCoolingDown(channel,now) || activeJobs.containsKey(channel.id))return@forEach
            if(channel.thumbnailUri==null)return channel to false
        }
        if (detectEnabled) requestedThumbnailChannels.forEach { requested ->
            val channel = latest[requested.id] ?: return@forEach
            val fingerprint = ChannelIdentity.fingerprint(channel.url, channel.headers)
            if (fingerprint == ChannelIdentity.fingerprint(requested.url, requested.headers) &&
                channel.thumbnailUri != null && channel.firstFrame == null &&
                attemptedInBatch[channel.id] != fingerprint && !isCoolingDown(channel, now) && !activeJobs.containsKey(channel.id)) return channel to true
        }
        return null
    }

    private suspend fun pumpThumbnails() {
        extractionLock.withLock {
            if(!automaticThumbnailsAllowed || manualThumbnailsRunning || nextWork()==null)return
            while(automaticWorkers.size<ThumbnailLimits.MAX_CONCURRENT) {
                val worker=workScope.launch(start=CoroutineStart.LAZY) {
                    val self=currentCoroutineContext()[Job]!!
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val claim=extractionLock.withLock {
                            if(!automaticThumbnailsAllowed || manualThumbnailsRunning)null
                            else nextWork()?.also {
                                activeJobs[it.first.id]=self
                                if(it.second)pureDetectionJobs.add(it.first.id)
                            }
                        } ?: break
                        publishTransient()
                        try { extract(claim.first, replaceImage=!claim.second, manual=false) }
                        finally {
                            pureDetectionJobs.remove(claim.first.id)
                            activeJobs.remove(claim.first.id, self)
                            publishTransient()
                        }
                    }
                }
                automaticWorkers.add(worker)
                // Completion also runs if cancellation happened before the body ever started.
                worker.invokeOnCompletion {
                    automaticWorkers.remove(worker)
                    activeJobs.entries.removeAll { it.value===worker }
                    if(automaticThumbnailsAllowed && !manualThumbnailsRunning)workScope.launch { pumpThumbnails() }
                }
                worker.start()
            }
        }
    }

    override suspend fun refreshThumbnails(channelIds: List<String>): Outcome<Unit> = withContext(io) {
        initGate.await()
        if(loadError.value!=null)return@withContext Outcome.Failure(loadError.value!!,"storage")
        refreshLock.withLock {
            coroutineScope {
                manualThumbnailsRunning=true
                manualJob=currentCoroutineContext()[Job]
                manualIds=channelIds.toSet()
                try {
                    cancelExtractionsAndJoin()
                    val byId=mutable.value.channels.associateBy { it.id }
                    val requested=channelIds.distinct()
                    val targets=requested.mapNotNull { byId[it] }
                    if(targets.isEmpty())return@coroutineScope Outcome.Failure("当前分组没有可刷新频道")
                    val index=AtomicInteger()
                    val successes=AtomicInteger()
                    List(minOf(ThumbnailLimits.MAX_CONCURRENT,targets.size)) {
                        async {
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val position=index.getAndIncrement()
                                if(position>=targets.size)break
                                val channel=targets[position]
                                cooldowns.remove(cooldownKey(channel))
                                if(extract(channel, replaceImage=true, manual=true))successes.incrementAndGet()
                            }
                        }
                    }.awaitAll()
                    if(successes.get()==requested.size)Outcome.Success(Unit)
                    else Outcome.Failure("已更新 ${successes.get()}/${requested.size} 个画面，失败 ${requested.size-successes.get()} 个，旧画面已保留","capture_partial")
                } finally {
                    manualJob=null
                    manualIds=emptySet()
                    manualThumbnailsRunning=false
                    if(automaticThumbnailsAllowed)workScope.launch { pumpThumbnails() }
                }
            }
        }
    }

    /**
     * One shared extraction budget slot. [replaceImage]=false runs a detection-only measurement:
     * no still is written and no existing image is touched. [manual]=true (group refresh)
     * re-measures even when a result already exists; automatic work only fills a missing one.
     */
    private suspend fun extract(channel: Channel, replaceImage: Boolean, manual: Boolean): Boolean {
        // The still is bound to the exact media this request was made for.
        val fingerprint = ChannelIdentity.fingerprint(channel.url, channel.headers)
        val name = ThumbnailStore.fileName(channel.id, fingerprint + "-" + UUID.randomUUID().toString().take(8))
        // Even a very large failed group is attempted once per batch, never spun into a retry loop.
        attemptedInBatch[channel.id]=fingerprint
        // Monotonic fallback timing, used only if the source itself reports no measurement.
        val startedAt=System.nanoTime()
        try {
            val result = extractionSlots.withPermit {
                withTimeoutOrNull(ThumbnailLimits.EXTRACT_TIMEOUT_MS) { thumbnailSource.fetch(channel) }
            }
            val captured = result?.bytes?.takeIf { it.isNotEmpty() }
            // The 5-second budget expiring without a frame is a timeout; every other capture
            // failure is a failure. Neither may ever surface as 0.
            if (captured == null) coolDown(channel)
            val measurement = when {
                captured != null -> FirstFrameResult(
                    FirstFrameStatus.SUCCESS,
                    result!!.firstFrameMillis ?: ((System.nanoTime()-startedAt)/1_000_000L),
                    clock(),
                )
                result == null -> FirstFrameResult(FirstFrameStatus.TIMEOUT, measuredAt = clock())
                else -> FirstFrameResult(FirstFrameStatus.FAILED, measuredAt = clock())
            }
            if (captured != null && replaceImage) {
                currentCoroutineContext().ensureActive()
                thumbnails.write(name, captured)
                cooldowns.remove(cooldownKey(channel))
            }
            val outcome = mutate { current ->
                val previous = current.channels.firstOrNull { it.id == channel.id }
                when {
                    previous == null -> { if (captured != null && replaceImage) thumbnails.delete(name); Outcome.Success(current) }
                    ChannelIdentity.fingerprint(previous.url, previous.headers) != fingerprint -> {
                        // The channel was refreshed or edited while we were fetching, so this still
                        // belongs to the previous media. Drop it instead of attaching it to the new.
                        if (captured != null && replaceImage) thumbnails.delete(name)
                        Outcome.Success(current)
                    }

                    else -> {
                        val nextFrame = if (manual || previous.firstFrame == null || captured != null && replaceImage) measurement else previous.firstFrame
                        val channels = current.channels.map {
                            if (it.id != channel.id) it
                            else if (captured != null && replaceImage) it.copy(thumbnailName = name, firstFrame = nextFrame)
                            else it.copy(firstFrame = nextFrame)
                        }
                        Outcome.Success(current.copy(channels = channels))
                    }
                }
            }
            // The catalog could not be saved, so nothing references the new still: don't leak it.
            if (outcome is Outcome.Failure) thumbnails.delete(name)
            // Manual refresh counts an image update as success; a timeout or capture failure is
            // never one, even though its measurement is still recorded for the label.
            val committed = outcome is Outcome.Success && if (replaceImage) {
                captured != null && state.channels.any { it.id==channel.id && it.thumbnailName==name }
            } else {
                state.channels.firstOrNull { it.id==channel.id }?.firstFrame == measurement
            }
            if(committed)attemptedInBatch[channel.id]=fingerprint
            return committed
        } catch (cancelled: CancellationException) {
            attemptedInBatch.remove(channel.id,fingerprint)
            if(state.channels.none { it.thumbnailName==name })thumbnails.delete(name)
            throw cancelled
        } catch (error: Throwable) {
            // A missing still must never surface as a user-visible failure; browsing keeps working.
            thumbnails.delete(name)
            coolDown(channel)
            return false
        } finally {
            activeJobs.remove(channel.id, currentCoroutineContext()[Job])
        }
    }

    private fun cancelExtractions() {
        automaticWorkers.toList().forEach { it.cancel() }
    }

    /**
     * Republishes the snapshot so transient state (detection-in-flight IDs) reaches the UI.
     * Runs under the write lock so it can never interleave with a catalog mutation, and is
     * non-cancellable so a cancelled worker still clears its own in-flight marker.
     */
    private suspend fun publishTransient() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            writeLock.withLock { mutable.value = state.toSnapshot() }
        }
    }

    private suspend fun cancelExtractionsAndJoin() {
        val workers=automaticWorkers.toList()
        workers.forEach { it.cancel() }
        workers.joinAll()
    }

    private fun coolDown(channel: Channel) {
        if (cooldowns.size > ThumbnailLimits.MAX_COOLDOWN_ENTRIES) cooldowns.clear()
        cooldowns[cooldownKey(channel)] = clock()
    }

    private fun isCoolingDown(channel: Channel, now: Long): Boolean {
        val failedAt = cooldowns[cooldownKey(channel)] ?: return false
        return now - failedAt < ThumbnailLimits.RETRY_COOLDOWN_MS
    }

    private fun cooldownKey(channel: Channel) =
        channel.id + "@" + ChannelIdentity.fingerprint(channel.url, channel.headers)

    private suspend fun mutate(transform: (StoredCatalog) -> Outcome<StoredCatalog>): Outcome<Unit> = withContext(io) {
        initGate.await()
        if (loadError.value != null) return@withContext Outcome.Failure(loadError.value!!, "storage")
        writeLock.withLock {
            when (val outcome = transform(state)) {
                is Outcome.Failure -> outcome
                is Outcome.Success -> {
                    val next = outcome.value.let { next -> next.copy(settings = next.settings.copy(browseGroups = next.settings.browseGroups.filterKeys { id -> next.sources.any { it.id == id } })) }
                    try {
                        store.write(next)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // Nothing is published: the on-disk copy and the snapshot both stay on the
                        // previous state, so the UI can only ever see data that really persisted.
                        return@withLock Outcome.Failure("保存失败，已保留原有数据", "storage")
                    }
                    val previousNames=state.channels.mapNotNull { it.thumbnailName }.toSet()
                    val retainedNames=next.channels.mapNotNull { it.thumbnailName }.toSet()
                    state = next
                    // Image removal follows catalog commit; failed edits/deletes preserve old stills.
                    (previousNames-retainedNames).forEach(thumbnails::delete)
                    mutable.value = next.toSnapshot()
                    Outcome.Success(Unit)
                }
            }
        }
    }

    /** Claims the next generation for [id]; see [generations]. */
    private fun nextGeneration(id: String): Long =
        generations.compute(id) { _, current -> (current ?: 0L) + 1L }!!

    private fun generationOf(id: String): Long = generations[id] ?: 0L

    /** Aligns the imported drafts with the stored channels of the same source, keeping ids stable. */
    private fun buildChannels(
        sourceId: String,
        drafts: List<ChannelDraft>,
        previous: List<StoredChannel>,
    ): Pair<List<StoredChannel>, List<String>> {
        val matchedIds = ChannelIdentity.matchIds(drafts, previous)
        val byId = previous.associateBy { it.id }
        val stale = ArrayList<String>()
        val reused = HashSet<String>()
        val channels = drafts.mapIndexed { index, draft ->
            val name = draft.name.trim()
            val url = draft.url.trim()
            val group = ChannelIdentity.normalizedGroup(draft.group)
            val stored = matchedIds[index]?.let { byId[it] }
            if (stored == null) {
                StoredChannel(
                    id = UUID.randomUUID().toString(), sourceId = sourceId, name = name, url = url,
                    group = group, logoUrl = draft.logoUrl, externalId = draft.externalId, headers = draft.headers,
                )
            } else {
                reused += stored.id
                val sameMedia = stored.url == url && stored.headers == draft.headers
                if (!sameMedia) stored.thumbnailName?.let(stale::add)
                stored.copy(
                    name = name,
                    url = url,
                    group = group,
                    logoUrl = draft.logoUrl ?: stored.logoUrl,
                    externalId = draft.externalId ?: stored.externalId,
                    headers = draft.headers,
                    thumbnailName = if (sameMedia) stored.thumbnailName else null,
                    firstFrame = if (sameMedia) stored.firstFrame else null,
                )
            }
        }
        previous.filterNot { it.id in reused }.forEach { it.thumbnailName?.let(stale::add) }
        return channels to stale
    }

    /** Spliced replacement: other sources keep their position, and so does this source's channel block. */
    private fun replaceChannels(
        all: List<StoredChannel>,
        sourceId: String,
        next: List<StoredChannel>,
    ): List<StoredChannel> {
        val result = ArrayList<StoredChannel>(all.size + next.size)
        var inserted = false
        all.forEach { channel ->
            if (channel.sourceId == sourceId) {
                if (!inserted) {
                    result += next
                    inserted = true
                }
            } else {
                result += channel
            }
        }
        if (!inserted) result += next
        return result
    }

    private fun pickSource(sources: List<StoredSource>, channels: List<StoredChannel>): String? =
        sources.firstOrNull { source -> channels.any { it.sourceId == source.id } }?.id
            ?: sources.firstOrNull { it.kind == SourceKind.CUSTOM }?.id
            ?: sources.firstOrNull()?.id

    private fun List<ChannelDraft>.deduplicated(): List<ChannelDraft> {
        val seen = HashSet<String>()
        return filter { seen.add(it.name.trim() + '\u0000' + it.url.trim()) }
    }

    private fun isHttpAddress(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun StoredCatalog.toSnapshot(): CatalogSnapshot = CatalogSnapshot(
        sources = sources.map { LiveSource(it.id, it.name, it.kind, it.location, it.updatedAt) },
        channels = channels.map { channel ->
            Channel(
                id = channel.id,
                sourceId = channel.sourceId,
                name = channel.name,
                url = channel.url,
                group = channel.group,
                logoUrl = channel.logoUrl,
                thumbnailUri = thumbnails.uriOf(channel.thumbnailName),
                headers = channel.headers,
                externalId = channel.externalId,
                firstFrame = channel.firstFrame,
                watchedAt = channel.watchedAt,
            )
        },
        selectedSourceId = selectedSourceId?.takeIf { id -> sources.any { it.id == id } },
        favoriteIds = channels.filter { it.favorite }.map { it.id }.toSet(),
        recentIds = channels.filter { it.watchedAt != null }.sortedByDescending { it.watchedAt }.map { it.id },
        settings = settings,
        cacheBytes = thumbnails.sizeBytes(),
        detectingIds = pureDetectionJobs.toSet(),
        capturingIds = activeJobs.keys.filterNot { it in pureDetectionJobs }.toSet(),
    )
}
