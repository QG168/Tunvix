package com.tunvix.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.*
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tunvix.app.BuildConfig
import com.tunvix.contract.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable fun HtmlApp(model: TunvixModel, dark: Boolean, pickFile: () -> Unit, modifier: Modifier = Modifier) {
    var host by remember { mutableStateOf<HtmlUiView?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val files = model.catalog.sources.filter { it.kind == SourceKind.LOCAL_FILE }.map { it.id to it.location }
    LaunchedEffect(files) {
        model.sourceFileNames = withContext(Dispatchers.IO) {
            files.mapNotNull { (id, location) -> runCatching {
                location?.let { uri -> context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) id to it.getString(0) else null
                } }
            }.getOrNull() }.toMap()
        }
    }
    // Reading these values in composition connects the original repositories to the HTML UI.
    val snapshotCache = remember { HtmlSnapshotCache() }
    val payload = htmlSnapshot(model, dark, snapshotCache)
    AndroidView(factory = { context -> HtmlUiView(context, model, pickFile, scope, dark).also { host = it } },
        modifier = modifier, update = { it.publish(payload) }, onRelease = { it.close(); host = null })
    SideEffect { host?.setPictureInPicture(model.pictureInPicture) }
    LaunchedEffect(model.toast) { val message = model.toast; if (message != null) { delay(2500); if (model.toast == message) model.toast = null } }
}

private fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
private fun array(values: Iterable<*>): JSONArray = JSONArray().apply { values.forEach { put(it) } }
private fun dateLabel(time: Long?): String = if (time == null) "尚未记录" else Instant.ofEpochMilli(time)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
private const val ORIGIN = "https://appassets.androidplatform.net"
private const val APP_URL = "$ORIGIN/assets/webui/index.html"

internal class HtmlSnapshotCache {
    private val sections = mutableMapOf<String, Pair<List<Any?>, Any>>()
    private val encoded = mutableMapOf<String, Pair<Any, String>>()
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> section(name: String, vararg inputs: Any?, build: () -> T): T {
        val keys = inputs.toList()
        val previous = sections[name]
        if (previous?.first == keys) return previous.second as T
        return build().also { sections[name] = keys to it }
    }
    fun encode(root: JSONObject): Map<String, String> = root.keys().asSequence().associateWith { key ->
        val value = root.get(key)
        val previous = encoded[key]
        if (previous?.first == value) previous.second else {
            val text = JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
            encoded[key] = value to text
            text
        }
    }
}

internal fun htmlSnapshot(m: TunvixModel, dark: Boolean, cache: HtmlSnapshotCache): Map<String, String> {
    val catalog = m.catalog
    val current = m.currentChannel
    val schedule = cache.section("schedule", current?.id, current?.name, current?.externalId, m.epg.sources, m.epg.documents, m.epg.matches, System.currentTimeMillis() / 30_000) {
        listOf(current?.let(m.epg::schedule))
    }.firstOrNull()
    val match = current?.let { m.epg.matches[it.id] }
    val matchedStation = cache.section("matchedStation", match, m.epg.documents) {
        listOf(match?.let { m.epg.documents[it.first]?.stations?.firstOrNull { station -> station.id == it.second } })
    }.firstOrNull()
    val settings = catalog.settings
    val channels = cache.section("channels", catalog.channels, catalog.sources, catalog.detectingIds) { array(catalog.channels.map { c ->
        val source = catalog.sources.firstOrNull { it.id == c.sourceId }
        val logo = BuiltInChannelLogos.uriFor(c) ?: c.logoUrl
        val revision = "${c.thumbnailUri?.hashCode()}:${logo?.hashCode()}:${c.firstFrame?.measuredAt}"
        val prefix = "$ORIGIN/media/${Uri.encode(c.id)}"
        val tier = UiRules.firstFrameTier(c.firstFrame, c.id in catalog.detectingIds)
        val color = when (tier) { FrameTier.FAST -> "#49cf88"; FrameTier.MEDIUM -> "#ffc34d"; FrameTier.SLOW -> "#ff7777"; FrameTier.TIMEOUT, FrameTier.FAILED -> "#ff6868"; FrameTier.PENDING -> "#9aa4b8" }
        json("id" to c.id, "name" to c.name, "nameKey" to ChannelLines.nameKey(c.name),
            "lineMs" to c.firstFrame?.takeIf { it.status == FirstFrameStatus.SUCCESS }?.millis, "sourceId" to c.sourceId, "source" to source?.name,
            "url" to if (source?.kind == SourceKind.CUSTOM) c.url else "",
            "group" to c.group, "detail" to c.group, "custom" to (source?.kind == SourceKind.CUSTOM),
            "preview" to "$prefix/preview?v=${Uri.encode(revision)}", "hasPreview" to (c.thumbnailUri != null),
            "logo" to if (logo == null) "" else "$prefix/logo?v=${logo.hashCode()}",
            "logoClass" to when { c.name.startsWith("贵州") -> "guizhou"; c.name.contains("体育") -> "sports"; c.name.contains("湖南") -> "hunan"; c.name.contains("东方") -> "dongfang"; else -> "" },
            "msLabel" to UiRules.firstFrameLabel(c.firstFrame, c.id in catalog.detectingIds),
            "msColor" to color, "watchedText" to dateLabel(c.watchedAt))
    }) }
    val sources = cache.section("sources", catalog.sources, catalog.channels, m.sourceFileNames, m.refreshingSourceIds) { array(catalog.sources.map { source ->
        val items = catalog.channels.filter { it.sourceId == source.id }
        json("id" to source.id, "name" to source.name, "type" to when (source.kind) { SourceKind.SUBSCRIPTION -> "在线订阅"; SourceKind.LOCAL_FILE -> "本地文件"; SourceKind.CUSTOM -> "手动频道" },
            "channels" to items.size, "groups" to items.map { it.group }.distinct().size,
            "url" to if (source.kind == SourceKind.SUBSCRIPTION) source.location else "",
            "file" to if (source.kind == SourceKind.LOCAL_FILE) m.sourceFileNames[source.id] ?: "已导入本地文件" else "",
            "updated" to dateLabel(source.updatedAt), "refreshing" to (source.id in m.refreshingSourceIds))
    }) }
    val epgSources = cache.section("epgSources", m.epg.sources, m.epg.documents, m.epg.errors, m.epg.updated) { array(m.epg.sources.map { source ->
        json("id" to source.id, "name" to source.name, "url" to source.url,
            "type" to if (source.builtin) "内置" else "自定义",
            "priority" to (!source.builtin && m.epg.sources.firstOrNull()?.id == source.id),
            "count" to (m.epg.documents[source.id]?.stations?.size ?: 0),
            "updated" to (m.epg.errors[source.id]?.let { "更新失败：$it${if (m.epg.documents[source.id] != null) "，使用缓存" else ""}" } ?: m.epg.updated[source.id]?.let(::dateLabel) ?: "尚未获取"))
    }) }
    val epgStations = cache.section("epgStations", m.epg.sources, m.epg.documents) { array(m.epg.sources.flatMap { source -> m.epg.documents[source.id]?.stations.orEmpty().map { station ->
        json("id" to station.id, "names" to array(station.names), "sourceId" to source.id, "sourceName" to source.name)
    } }) }
    val overlay = when (val o = m.overlay) {
        is Overlay.Confirm -> json("title" to o.title, "message" to o.message, "button" to o.button, "danger" to o.danger)
        is Overlay.Error -> json("title" to o.title, "message" to o.message, "button" to "知道了", "error" to true)
        is Overlay.Network -> json("title" to "当前正在使用移动网络", "message" to "直播视频可能消耗较多流量，是否继续播放？确认后不再提醒，可在设置中重新开启。", "button" to "继续播放")
        else -> null
    }
    return cache.encode(json("route" to m.webRoute, "draft" to m.webDraft, "ready" to m.catalogReady, "catalogError" to m.catalogError,
        "channels" to channels, "sources" to sources, "sourceId" to catalog.selectedSourceId,
        "source" to m.currentSource?.name.orEmpty(), "current" to current?.id, "phase" to m.playback.phase.name,
        "channelUnavailable" to (m.playback.channelId != null && current == null),
        "playing" to (m.playback.phase == PlaybackPhase.PLAYING), "audioOn" to m.playback.audioOnly,
        "sleepMinutes" to m.playback.sleepTimerMinutes, "sleepRemaining" to m.playback.sleepTimerRemainingSeconds, "sleepTimerOn" to (m.playback.sleepTimerDeadlineElapsed != null),
        "foreground" to m.foreground, "pictureInPicture" to m.pictureInPicture, "videoRatio" to m.playback.videoAspectRatio, "frameReady" to m.playback.videoFrameReady, "controlsVisible" to m.controlsVisible,
        "showPlayingError" to (m.playback.phase == PlaybackPhase.FAILED), "playbackError" to m.playback.errorMessage,
        "favorites" to array(catalog.favoriteIds), "recent" to array(catalog.recentIds),
        "groups" to array((listOf("全部") + catalog.channels.filter { it.sourceId == catalog.selectedSourceId }.map { it.group }).distinct()),
        "playerGroups" to array((listOf("全部") + catalog.channels.filter { it.sourceId == current?.sourceId }.map { it.group }).distinct()),
        "restoredGroup" to UiRules.restoredGroup(catalog),
        "cGroups" to array((settings.customGroups + catalog.channels.filter { c -> catalog.sources.any { it.id == c.sourceId && it.kind == SourceKind.CUSTOM } }.map { it.group }).distinct().filterNot { it == "未分组" }),
        "brightness" to m.systemBrightness, "volume" to m.playback.volumePercent, "dark" to dark, "theme" to settings.themeMode.name.lowercase(), "version" to BuildConfig.VERSION_NAME,
        "cache" to catalog.cacheBytes / 1048576.0, "busy" to m.busy, "screenshotRefreshing" to m.screenshotRefreshing,
        "fileName" to m.fileName, "fileReplacementSource" to m.editingSourceId, "formName" to m.formName,
        "importType" to if (m.importRequest is ImportRequest.LocalFile) "本地文件" else "在线订阅",
        "report" to m.report?.let { json("channels" to it.channels.size, "groups" to it.groupCount, "skipped" to it.skippedCount) },
        "settings" to json("autoPip" to settings.autoPictureInPicture, "mobileReminder" to settings.mobileReminderEnabled,
            "haptic" to settings.hapticsEnabled, "latency" to settings.firstFrameEnabled,
            "detection" to settings.previewDetectionEnabled, "autoEpg" to settings.autoEpgUpdateEnabled, "mergeChannels" to settings.mergeSameNameChannels, "defaultPlayerPanel" to settings.defaultPlayerPanel, "softViewing" to settings.softViewingEnabled),
        "epgSources" to epgSources, "epgStations" to epgStations, "epgRefreshing" to m.epg.refreshing,
        "epgSourceName" to schedule?.first, "epgMatched" to (matchedStation?.names?.firstOrNull() ?: if (schedule != null) current?.name else "未匹配"),
        "matchSource" to match?.first, "matchId" to match?.second,
        "programmes" to array(schedule?.second.orEmpty().map { json("start" to it.start, "end" to it.end, "title" to it.title) }),
        "playerGroup" to m.playerGroup, "overlay" to overlay, "toast" to m.toast))
}

/** One local page, one native video output. Remote documents never receive a native bridge. */
@SuppressLint("SetJavaScriptEnabled")
internal class HtmlUiView(context: Context, private val m: TunvixModel, private val pickFile: () -> Unit, private val scope: CoroutineScope, initialDark: Boolean) : FrameLayout(context) {
    private val videoMatte = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val video = TextureView(context)
    private val web = WebView(context)
    private val bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var pendingBrightness: Int? = null
    private var brightnessPrompted = false
    private var awaitingBrightnessGrant = false
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { readBrightness() }
    }
    private var loaded = false
    private var closed = false
    private var pip = false
    private var pending: Map<String, String> = emptyMap()
    private var lastSent: Map<String, String>? = null
    private var mediaChannels: List<Channel>? = null
    private var captureJob: Job? = null
    private var capturedSession = -1L
    private var pendingAudioReplay = false
    private var pendingPlayGroup: Pair<String, String>? = null
    private var pictureMode = "fit"
    private var fittedVideo: List<Any>? = null
    private var safeArea = "{\"top\":0,\"bottom\":0,\"left\":0,\"right\":0}"
    private var lastSafeArea: String? = null
    private var importSeen: ImportReport? = null
    private val backAction: () -> Unit = { back() }
    @Volatile private var media: Map<String, Channel> = emptyMap()

    init {
        setBackgroundColor(Color.parseColor(if (initialDark) "#0b1b33" else "#eaf3ff"))
        video.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, if (pip || m.fullscreen) 0f else 24 * resources.displayMetrics.density)
            }
        }
        videoMatte.outlineProvider = video.outlineProvider
        videoMatte.clipToOutline = true
        videoMatte.visibility = INVISIBLE
        addView(videoMatte, LayoutParams(1, 1))
        video.clipToOutline = true
        video.visibility = View.INVISIBLE
        addView(video, LayoutParams(1, 1))
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        web.visibility = INVISIBLE
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportZoom(false)
            useWideViewPort = true; textZoom = (resources.configuration.fontScale * 100).roundToInt()
            mediaPlaybackRequiresUserGesture = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            safeArea = json("top" to bars.top / density, "bottom" to if (insets.isVisible(WindowInsetsCompat.Type.ime())) 0 else bars.bottom / density,
                "left" to bars.left / density, "right" to bars.right / density).toString()
            sendSafeArea()
            insets
        }
        ViewCompat.requestApplyInsets(this)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/media/", WebViewAssetLoader.PathHandler { path -> imageResponse(path) }).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString() != APP_URL
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val response = loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                response.responseHeaders = (response.responseHeaders ?: emptyMap()) + mapOf(
                    "Cache-Control" to (response.responseHeaders?.get("Cache-Control") ?: if (request.url.path?.startsWith("/media/") == true) "private, max-age=86400, immutable" else "no-store"), "X-Content-Type-Options" to "nosniff",
                    "Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
                return response
            }
            override fun onPageFinished(view: WebView, url: String) { if (url == APP_URL) { loaded = true; send(); sendSafeArea() } }
        }
        if (bridgeSupported) {
            WebViewCompat.addWebMessageListener(web, "Tunvix", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == ORIGIN) {
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.length <= 128 * 1024) web.post { if (!closed) dispatch(raw) }
                }
            }
            web.loadUrl(APP_URL)
        } else {
            web.visibility = VISIBLE
            web.loadData("<p>请更新手机的 Android System WebView 后重新打开 Tunvix。</p>", "text/html", "UTF-8")
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver)
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessObserver)
        post { if (!closed) readBrightness() }
        m.services.playback.attachTexture(video)
        m.webBack = backAction
    }

    private fun readBrightness() {
        if (closed) return
        val level = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
        m.systemBrightness = ((level - 1) * 100f / 254f).roundToInt()
    }

    private fun writeBrightness(percent: Int) {
        if (!Settings.System.canWrite(context)) return
        val level = (1 + percent.coerceIn(0, 100) * 254f / 100f).roundToInt()
        val result = runCatching {
            val mode = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val value = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            mode && value
        }
        if (result.getOrDefault(false)) readBrightness()
        else { readBrightness(); m.overlay = Overlay.Error("亮度调节未完成", "系统未允许修改亮度，请检查修改系统设置权限后重试。") }
    }

    private fun changeBrightness(percent: Int) {
        if (Settings.System.canWrite(context)) { writeBrightness(percent); return }
        pendingBrightness = percent.coerceIn(0, 100)
        if (brightnessPrompted || awaitingBrightnessGrant) return
        brightnessPrompted = true
        m.overlay = Overlay.Confirm("允许调节系统亮度", "请开启修改系统设置权限。调节后将使用系统手动亮度，离开应用后仍会保留。", "去开启") {
            brightnessPrompted = false
            awaitingBrightnessGrant = true
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { awaitingBrightnessGrant = false; pendingBrightness = null; m.overlay = Overlay.Error("无法打开权限设置", "请在系统应用设置中允许 Tunvix 修改系统设置。") }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || closed) return
        if (awaitingBrightnessGrant) {
            awaitingBrightnessGrant = false
            val requested = pendingBrightness; pendingBrightness = null
            if (requested != null && Settings.System.canWrite(context)) writeBrightness(requested)
        }
        readBrightness()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    fun publish(payload: Map<String, String>) {
        if (mediaChannels !== m.catalog.channels) {
            mediaChannels = m.catalog.channels
            media = m.catalog.channels.associateBy { it.id }
        }
        if (m.page == Page.RESULT && m.report != null && importSeen !== m.report) {
            importSeen = m.report; m.webRoute = "import-result"
        }
        pending = payload
        if (!pip) {
            setBackgroundColor(Color.parseColor(if (payload["dark"] == "true") "#0b1b33" else "#eaf3ff"))
            if (bridgeSupported && payload["ready"] != "true") web.visibility = INVISIBLE
        }
        send()
        video.post { fitVideo() }
        captureStill()
    }

    private fun send() {
        if (closed || !loaded || pending == lastSent) return
        val changes = pending.filter { (key, value) -> lastSent?.get(key) != value }
        if (changes.isEmpty()) return
        lastSent = pending
        val patch = changes.entries.joinToString(",", "{", "}") { (key, value) -> "${JSONObject.quote(key)}:$value" }
        web.evaluateJavascript("window.receiveNative($patch);") { if (!closed && !pip && pending["ready"] == "true") web.visibility = VISIBLE }
    }

    private fun sendSafeArea() {
        if (!loaded || closed || safeArea == lastSafeArea) return
        lastSafeArea = safeArea
        web.evaluateJavascript("window.receiveSafeArea($safeArea);", null)
    }

    fun back() { web.evaluateJavascript("window.nativeBack();", null) }

    fun setPictureInPicture(enabled: Boolean) {
        if (pip == enabled) return
        pip = enabled; web.visibility = if (pip) GONE else VISIBLE
        if (pip) {
            video.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            videoMatte.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            video.visibility = VISIBLE; videoMatte.visibility = VISIBLE
            video.invalidateOutline(); videoMatte.invalidateOutline(); fittedVideo = null; video.post { fitVideo() }
        }
        else web.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
    }

    private fun frame(data: JSONObject) {
        if (pip) return
        val f = data.optJSONObject("frame")
        if (f == null || m.playback.audioOnly) { video.visibility = INVISIBLE; videoMatte.visibility = INVISIBLE; m.videoBounds = null; return }
        val viewport = f.optDouble("viewportWidth", 0.0)
        if (viewport <= 0 || width <= 0) return
        val scale = width / viewport
        val x = f.optDouble("x"); val y = f.optDouble("y"); val w = f.optDouble("w"); val h = f.optDouble("h")
        if (!listOf(x, y, w, h).all { it.isFinite() } || w <= 0 || h <= 0 || w > viewport * 2 || h > 10000) return
        video.layoutParams = LayoutParams((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)).apply {
            leftMargin = (x * scale).roundToInt(); topMargin = (y * scale).roundToInt()
        }
        videoMatte.layoutParams = LayoutParams(video.layoutParams as LayoutParams)
        // The texture must exist before the first frame can be rendered; HTML keeps its poster
        // above it until videoFrameReady, avoiding a surface/first-frame dependency cycle.
        video.visibility = if (m.playback.phase != PlaybackPhase.IDLE && y < height / scale && y + h > 0) VISIBLE else INVISIBLE
        videoMatte.visibility = video.visibility
        video.invalidateOutline(); videoMatte.invalidateOutline()
        val position = IntArray(2); getLocationInWindow(position)
        m.videoBounds = Rect(position[0] + (x * scale).roundToInt(), position[1] + (y * scale).roundToInt(),
            position[0] + ((x + w) * scale).roundToInt(), position[1] + ((y + h) * scale).roundToInt())
        video.post { fitVideo() }
    }

    private fun fitVideo() {
        if (video.width <= 0 || video.height <= 0) return
        val desired = m.playback.videoAspectRatio.takeIf { it > 0 && it.isFinite() } ?: (16f / 9f)
        val mode = if (m.fullscreen && !pip) pictureMode else "fit"
        val key = listOf(video.width, video.height, desired, mode)
        if (fittedVideo == key) return
        fittedVideo = key
        val actual = video.width.toFloat() / video.height
        val matrix = Matrix()
        when (mode) {
            "fill" -> if (actual > desired) matrix.setScale(1f, actual / desired, video.width / 2f, video.height / 2f)
                else matrix.setScale(desired / actual, 1f, video.width / 2f, video.height / 2f)
            "stretch" -> Unit
            else -> if (actual > desired) matrix.setScale(desired / actual, 1f, video.width / 2f, video.height / 2f)
                else matrix.setScale(1f, actual / desired, video.width / 2f, video.height / 2f)
        }
        video.setTransform(matrix)
    }

    private fun imageResponse(path: String): WebResourceResponse? {
        val parts = path.split('/')
        if (parts.size != 2) return null
        val c = media[Uri.decode(parts[0])] ?: return null
        val logo = BuiltInChannelLogos.uriFor(c) ?: c.logoUrl
        val uri = when (parts[1]) { "preview" -> c.thumbnailUri ?: logo; "logo" -> logo; else -> return null }
        val bitmap = uri?.let { runBlocking(Dispatchers.IO) { runCatching { loadChannelBitmap(context, it, true) }.getOrNull() } }
        if (bitmap != null) {
            val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            return WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))
        }
        val placeholder = "<svg xmlns='http://www.w3.org/2000/svg' width='80' height='50' viewBox='0 0 80 50'><rect x='14' y='11' width='52' height='33' rx='8' fill='none' stroke='#8398bd' stroke-width='3'/><path d='M29 4L40 11L51 4' fill='none' stroke='#8398bd' stroke-width='3'/></svg>"
        return WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream(placeholder.toByteArray())).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }

    private fun captureStill() {
        val c = m.currentChannel ?: return
        val session = m.playSession
        if (capturedSession == session || !m.playback.videoFrameReady || c.thumbnailUri != null || m.playback.audioOnly || !m.foreground || m.webRoute !in setOf("player", "fullscreen") || video.width < 32 || video.height < 32 || captureJob?.isActive == true) return
        captureJob = scope.launch {
            delay(300)
            if (!video.isAvailable || !m.needsPlayingThumbnail(session) || m.playSession != session) return@launch
            val bitmap = video.getBitmap(320, 180) ?: return@launch
            try {
                val bytes = withContext(Dispatchers.IO) { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 65, it); it.toByteArray() } }
                m.savePlayingThumbnail(c, bytes, session); capturedSession = session
            } finally { bitmap.recycle() }
        }
    }

    private fun navigate(route: String) {
        if (route !in ROUTES) return
        val mediaRoute = route in MEDIA_ROUTES
        if (m.webRoute != route) m.prepareWebNavigation()
        if (!mediaRoute && m.page == Page.PLAYER && !m.playback.audioOnly) m.services.playback.stop()
        if (route != "fullscreen") { pictureMode = "fit"; fittedVideo = null }
        m.webRoute = route; m.changeFullscreen(route == "fullscreen")
        m.page = when (route) {
            "home", "search" -> Page.HOME; "favorites" -> Page.FAVORITES; "recent" -> Page.RECENT
            "settings", "appearance", "permissions", "pip-info" -> if (m.currentChannel != null && mediaRoute) Page.PLAYER else Page.MANAGEMENT
            "sources", "source-detail", "group-edit" -> Page.SOURCES
            "source-online" -> Page.SUBSCRIBE; "source-file" -> Page.LOCAL
            "source-manual", "channel-edit" -> Page.MANUAL; "import-result" -> Page.RESULT
            "source-add" -> Page.ADD; "about" -> Page.ABOUT; "epg-sources", "epg-edit" -> Page.EPG
            else -> Page.PLAYER
        }
        if (route == "audio" && m.currentChannel != null) { m.lastAudioChannelId = m.currentChannel?.id; m.services.playback.setAudioOnly(true) }
        if (route in setOf("player", "fullscreen") && m.playback.audioOnly) m.services.playback.setAudioOnly(false)
        if (route in listOf("epg", "epg-match") && m.catalog.settings.autoEpgUpdateEnabled) epg { it.refresh(false) }
        m.showControls()
    }

    private fun epg(action: suspend (com.tunvix.app.services.EpgRepository) -> Unit) {
        val repo = m.services.epg ?: return
        scope.launch { try { action(repo) } catch (e: Exception) { if (e is CancellationException) throw e; m.overlay = Overlay.Error("节目单操作未完成", e.message ?: "请重试") } }
    }

    private fun operation(success: String? = null, route: String? = null, block: suspend () -> Outcome<Unit>) {
        m.operation(block, success) { if (route != null) navigate(route) }
    }
    private fun confirm(title: String, message: String, button: String, action: () -> Unit) {
        m.overlay = Overlay.Confirm(title, message, button, true, action)
    }

    private fun dispatch(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val action = message.optString("action"); val data = message.optJSONObject("data") ?: JSONObject()
        val id = data.optString("id").takeIf { it.isNotBlank() && it != "null" }
        val fields = data.optJSONObject("fields") ?: JSONObject()
        val channel = id?.let { media[it] }
        val source = m.catalog.sources.firstOrNull { it.id == id }
        when (action) {
            "ready" -> { loaded = true; lastSent = null; lastSafeArea = null; send(); sendSafeArea() }
            "route" -> { data.optJSONObject("draft")?.let { m.webDraft = it.toString() }; navigate(data.optString("route")) }
            "draft" -> data.optJSONObject("draft")?.let { m.webDraft = it.toString() }
            "video-frame" -> frame(data)
            "picture-mode" -> {
                pictureMode = data.optString("mode").takeIf { m.fullscreen && it in setOf("fit", "fill", "stretch") } ?: "fit"
                fittedVideo = null; fitVideo()
            }
            "visible" -> { val ids = data.optJSONArray("ids") ?: JSONArray(); m.setVisibleChannels((0 until ids.length()).map { ids.optString(it) }.filter(media::containsKey)) }
            "browse-group" -> m.group = data.optString("group")
            "player-group" -> { m.playerGroup = data.optString("group"); m.showControls() }
            "play", "select-line" -> if (channel != null) {
                val target = if (action == "select-line" || !m.catalog.settings.mergeSameNameChannels) channel
                    else ChannelLines.fastest(m.catalog.channels.filter { ChannelLines.sameChannel(it, channel) }) ?: channel
                data.optJSONObject("draft")?.let { m.webDraft = it.toString() }
                if (data.optBoolean("fromHome")) {
                    if (target.sourceId == m.catalog.selectedSourceId) m.group = data.optString("group")
                }
                val requested = data.optString("group").takeIf { it.isNotBlank() } ?: target.group
                val group = requested.takeIf { it in m.groups(target.sourceId) } ?: "全部"
                val listen = m.webRoute == "audio"
                pendingAudioReplay = false
                if (action == "play" && m.webRoute != "audio" && m.playback.audioOnly) m.services.playback.setAudioOnly(false)
                m.play(target)
                if (m.overlay is Overlay.Network) { pendingPlayGroup = target.id to group; pendingAudioReplay = listen }
                else {
                    pendingPlayGroup = null
                    if (listen) m.services.playback.setAudioOnly(true)
                    m.playerGroup = group
                    m.webRoute = if (listen) "audio" else "player"
                }
            }
            "toggle-play", "audio-play" -> {
                if (m.playback.phase == PlaybackPhase.IDLE) m.currentChannel?.let { c ->
                    val group = m.playerGroup
                    m.play(c)
                    val waiting = m.overlay is Overlay.Network
                    pendingAudioReplay = waiting && action == "audio-play"
                    if (waiting) pendingPlayGroup = c.id to group
                    else {
                        m.playerGroup = group
                        if (action == "audio-play") m.services.playback.setAudioOnly(true)
                    }
                } else m.pauseToggle()
            }
            "sleep-timer" -> m.services.playback.setSleepTimer(data.optInt("minutes"))
            "exit-audio" -> { m.services.playback.setSleepTimer(0); m.services.playback.setAudioOnly(false); navigate("player") }
            "toggle-controls" -> m.toggleControls()
            "prev-channel", "next-channel" -> {
                haptic()
                val current = m.currentChannel
                val group = m.playerGroup.takeIf { it in m.groups(current?.sourceId) } ?: current?.group ?: "全部"
                val raw = m.catalog.channels.filter { it.sourceId == current?.sourceId && (group == "全部" || it.group == group) }
                val list = if (m.catalog.settings.mergeSameNameChannels) ChannelLines.representatives(raw) else raw
                if (list.isNotEmpty()) {
                    val index = list.indexOfFirst { it.id == current?.id || (current != null && m.catalog.settings.mergeSameNameChannels && ChannelLines.sameChannel(it, current)) }.coerceAtLeast(0)
                    val target = list[(index + (if (action == "prev-channel") -1 else 1) + list.size) % list.size]
                    val listen = m.webRoute == "audio"
                    m.play(target)
                    if (m.overlay is Overlay.Network) { pendingPlayGroup = target.id to group; pendingAudioReplay = listen }
                    else {
                        pendingPlayGroup = null; pendingAudioReplay = false
                        if (listen) m.services.playback.setAudioOnly(true)
                        m.playerGroup = group
                    }
                }
            }
            "brightness" -> changeBrightness(data.optInt("value"))
            "volume" -> m.volume(data.optInt("value"))
            "volume-up", "volume-down" -> { haptic(); m.volume(m.playback.volumePercent + if (action == "volume-up") 5 else -5) }
            "toggle-fav" -> if (channel != null) m.favorite(channel)
            "remove-recent" -> if (channel != null) operation { m.services.catalog.removeRecent(channel.id) }
            "clear-recent" -> m.confirmClearHistory()
            "clear-cache" -> confirm("清理截图缓存", "确定清理已保存的频道截图吗？", "清理缓存") { operation("缓存清理完成") { m.services.catalog.clearThumbnailCache() } }
            "refresh-previews" -> m.refreshGroupScreenshots()
            "select-source" -> if (source != null) operation("已切换至 ${source.name}", "home") { m.services.catalog.selectSource(source.id) }
            "refresh-source" -> if (source != null) {
                m.refreshSubscription(source)
            }
            "delete-source" -> if (source != null) confirm("删除直播源", "删除后将移除该来源的频道、缓存、收藏和观看记录。", "确认删除") { operation("已删除直播源", "sources") { m.services.catalog.deleteSource(source.id) } }
            "pick-file" -> { m.fileUri = null; m.editingSourceId = if (data.optBoolean("replace")) id else null; pickFile() }
            "parse-online", "parse-file" -> {
                m.formName = fields.optString(if (action == "parse-online") "online-name" else "file-source-name").ifBlank { m.fileName.substringBeforeLast('.') }
                m.formLink = fields.optString("online-url"); m.editingSourceId = null
                m.page = if (action == "parse-online") Page.SUBSCRIBE else Page.LOCAL
                m.parse(action == "parse-file")
            }
            "confirm-import" -> {
                val request = m.importRequest; val report = m.report
                if (request != null && report != null) operation("直播源已成功导入", "sources") { m.services.catalog.commitImport(m.formName, request, report, m.editingSourceId) }
            }
            "save-source" -> if (source != null) {
                m.formName = fields.optString("edit-source-name")
                if (source.kind == SourceKind.LOCAL_FILE && m.fileUri != null && m.editingSourceId == source.id) {
                    m.editingSourceId = source.id; m.page = Page.EDIT_SOURCE; m.parse(true)
                } else operation("已保存修改") { m.services.catalog.updateSource(source.id, m.formName, if (source.kind == SourceKind.SUBSCRIPTION) fields.optString("edit-source-url") else source.location) }
            }
            "save-channel" -> operation("自建频道已保存", "sources") {
                m.services.catalog.saveCustomChannel(id, ChannelDraft(fields.optString("manual-name"), fields.optString("manual-url"), fields.optString("manual-group").ifBlank { "未分组" }))
            }
            "delete-channel" -> if (channel != null) confirm("删除自建频道", "此操作无法撤销。", "删除") { operation("频道已删除", "sources") { m.services.catalog.deleteCustomChannel(channel.id) } }
            "add-group" -> operation("已新增分组") { m.services.catalog.addCustomGroup(data.optString("name")) }
            "delete-group" -> { val group = data.optString("name"); confirm("删除分组", "此分组中的自建频道将归入未分组。", "删除") { operation("已删除分组") { m.services.catalog.deleteCustomGroup(group) } } }
            "setting" -> {
                val enabled = data.optBoolean("value"); val settings = m.catalog.settings
                if (data.optString("key") == "autoPip") m.setAutomaticSmallWindow(enabled)
                else m.operation({ m.services.catalog.updateSettings(when (data.optString("key")) {
                    "mobileReminder" -> settings.copy(mobileReminderEnabled = enabled)
                    "haptic" -> settings.copy(hapticsEnabled = enabled)
                    "latency" -> settings.copy(firstFrameEnabled = enabled)
                    "detection" -> settings.copy(previewDetectionEnabled = enabled)
                    "autoEpg" -> settings.copy(autoEpgUpdateEnabled = enabled)
                    "mergeChannels" -> settings.copy(mergeSameNameChannels = enabled)
                    "softViewing" -> settings.copy(softViewingEnabled = enabled)
                    "defaultPlayerPanel" -> settings.copy(defaultPlayerPanel = data.optString("value").takeIf { it in setOf("channels", "programmes", "remote") } ?: "channels")
                    else -> settings
                }) }, onSuccess = { if (data.optString("key") == "autoEpg" && enabled) epg { it.refresh(false) } })
            }
            "theme" -> operation { m.services.catalog.setThemeMode(when (data.optString("mode")) { "light" -> ThemeMode.LIGHT; "dark" -> ThemeMode.DARK; else -> ThemeMode.SYSTEM }) }
            "join-qq-group" -> {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/4SNuKqjnBC"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    m.overlay = Overlay.Confirm("暂时无法打开入群链接", "请在 QQ 中搜索群号 984253683，加入 Tunvix 播放器。", "复制群号") {
                        runCatching { context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Tunvix 播放器 QQ 群", "984253683")) }
                            .onSuccess { m.toast = "群号已复制" }
                            .onFailure { m.toast = "复制失败，请手动输入群号 984253683" }
                    }
                }
            }
            "open-player-epg" -> if (m.catalog.settings.autoEpgUpdateEnabled) epg { it.refresh(false) }
            "refresh-epg" -> epg { it.refresh(sourceId = id) }
            "priority-epg" -> if (id != null) epg { it.prioritize(id) }
            "delete-epg" -> m.epg.sources.firstOrNull { it.id == id }?.let(m::removeEpg)
            "save-epg" -> epg { it.save(id, fields.optString("epg-name"), fields.optString("epg-url")); navigate("epg-sources") }
            "match" -> m.currentChannel?.let { c -> epg { it.match(c.id, data.optString("sourceId"), data.optString("stationId")) } }
            "restore-match" -> m.currentChannel?.let { c -> epg { it.match(c.id, null, null) } }
            "enter-pip" -> m.enterSmallWindow()
            "mock-settings" -> m.requestPictureInPictureSettings?.invoke()
            "retry" -> { m.services.playback.retry(); navigate("player") }
            "refresh-current-source" -> m.refreshPlayingSource()
            "dismiss-overlay" -> { m.overlay = null; pendingPlayGroup = null; pendingAudioReplay = false; brightnessPrompted = false; pendingBrightness = null }
            "accept-overlay" -> { val overlay = m.overlay; m.overlay = null; when (overlay) {
                is Overlay.Confirm -> overlay.action()
                is Overlay.Network -> {
                    val group = pendingPlayGroup?.takeIf { it.first == overlay.channel.id }?.second ?: m.playerGroup
                    pendingPlayGroup = null
                    val listen = pendingAudioReplay
                    pendingAudioReplay = false
                    m.webRoute = if (listen) "audio" else "player"; m.consent(overlay.channel)
                    if (listen) m.services.playback.setAudioOnly(true)
                    m.playerGroup = group.takeIf { it in m.groups(overlay.channel.sourceId) } ?: "全部"
                }
                else -> Unit
            } }
        }
    }

    fun close() {
        closed = true; context.contentResolver.unregisterContentObserver(brightnessObserver); captureJob?.cancel(); m.services.playback.detachTexture(video)
        if (m.webBack === backAction) m.webBack = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "Tunvix")
        web.stopLoading(); web.destroy()
    }

    private fun haptic() { if (m.catalog.settings.hapticsEnabled) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }

    companion object {
        private val MEDIA_ROUTES = setOf("player", "fullscreen", "epg", "epg-match", "audio", "playback-error", "permissions", "pip-info")
        private val ROUTES = MEDIA_ROUTES + setOf("home", "favorites", "recent", "search", "sources", "source-detail", "source-add", "source-online", "source-file", "import-result", "source-manual", "channel-edit", "group-edit", "epg-sources", "epg-edit", "settings", "appearance", "about")
    }
}
