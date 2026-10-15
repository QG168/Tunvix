package com.tunvix.app.services

import android.content.Context
import android.util.AtomicFile
import android.util.Xml
import com.tunvix.contract.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.GZIPInputStream

data class EpgSource(val id: String, val name: String, val url: String, val builtin: Boolean = false)
data class Programme(val start: Long, val end: Long, val title: String)
data class EpgStation(val id: String, val names: List<String>)
data class EpgDocument(val stations: List<EpgStation>, val programmes: Map<String, List<Programme>>)
data class EpgState(
    val sources: List<EpgSource> = listOf(EpgRepository.BUILTIN),
    val documents: Map<String, EpgDocument> = emptyMap(),
    val updated: Map<String, Long> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val refreshing: Boolean = false,
    val matches: Map<String, Pair<String, String>> = emptyMap(),
) {
    fun schedule(channel: Channel): Pair<String, List<Programme>>? {
        val manual = matches[channel.id]
        for (source in sources) {
            val doc = documents[source.id] ?: continue
            val station = if (manual != null) {
                if (source.id != manual.first) continue
                doc.stations.firstOrNull { it.id == manual.second }
            } else {
                channel.externalId?.let { id -> doc.stations.firstOrNull { it.id == id } }
                    ?: doc.stations.firstOrNull { station -> station.names.any { epgName(it) == epgName(channel.name) } }
            } ?: continue
            val programmes = doc.programmes[station.id].orEmpty()
            if (programmes.any { it.end > System.currentTimeMillis() }) return source.name to programmes
        }
        return null
    }
}

/** XMLTV subscriptions and cache are independent of the live-source catalogue. */
class EpgRepository(context: Context) {
    private val directory = File(context.filesDir, "epg").apply { mkdirs() }
    private val config = AtomicFile(File(directory, "sources.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(EpgState())
    val state = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = runCatching { JSONObject(config.readFully().toString(Charsets.UTF_8)) }.getOrNull()
            val custom = root?.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until custom.length()).mapNotNull { i -> runCatching {
                val item = custom.getJSONObject(i)
                val id = item.getString("id")
                require(id.matches(Regex("[a-f0-9-]{36}")))
                EpgSource(id, item.getString("name"), checkedUrl(item.getString("url")))
            }.getOrNull() }.distinctBy { it.id } + BUILTIN
            val matches = mutableMapOf<String, Pair<String, String>>()
            root?.optJSONObject("matches")?.let { saved -> saved.keys().forEach { key ->
                val pair = saved.optJSONArray(key)
                if (pair != null && pair.length() == 2) matches[key] = pair.getString(0) to pair.getString(1)
            } }
            val documents = mutableMapOf<String, EpgDocument>()
            val updated = mutableMapOf<String, Long>()
            for (source in sources) {
                val cache = cache(source.id)
                runCatching { parse(AtomicFile(cache).readFully()) }.getOrNull()?.let {
                    documents[source.id] = it; updated[source.id] = cache.lastModified()
                }
            }
            mutable.value = EpgState(sources, documents, updated, matches = matches)
        }
        refresh(false)
    }

    suspend fun save(id: String?, name: String, url: String) = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty()) { "请输入订阅名称" }
        val checked = checkedUrl(url)
        mutex.withLock {
            require(id != BUILTIN.id) { "内置节目单不能修改" }
            val old = mutable.value
            val existing = old.sources.firstOrNull { it.id == id }
            val source = EpgSource(existing?.id ?: UUID.randomUUID().toString(), name.trim(), checked)
            val sources = if (existing == null) listOf(source) + old.sources else old.sources.map { if (it.id == id) source else it }
            val changedUrl = existing?.url != checked
            val next = old.copy(sources = sources,
                documents = if (changedUrl) old.documents - source.id else old.documents,
                updated = if (changedUrl) old.updated - source.id else old.updated,
                errors = old.errors - source.id)
            persist(next)
            if (changedUrl) AtomicFile(cache(source.id)).delete()
            mutable.value = next
        }
        refresh(false)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(id != BUILTIN.id) { "内置节目单不能删除" }
            val old = mutable.value
            val next = old.copy(sources = old.sources.filterNot { it.id == id }, documents = old.documents - id,
                updated = old.updated - id, errors = old.errors - id, matches = old.matches.filterValues { it.first != id })
            persist(next); AtomicFile(cache(id)).delete(); mutable.value = next
        }
    }

    suspend fun prioritize(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = mutable.value
            val source = old.sources.firstOrNull { it.id == id && !it.builtin } ?: return@withLock
            val next = old.copy(sources = listOf(source) + old.sources.filterNot { it.id == id })
            persist(next); mutable.value = next
        }
    }

    suspend fun match(channelId: String, sourceId: String?, stationId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = mutable.value
            val matches = if (sourceId == null || stationId == null) old.matches - channelId
                else old.matches + (channelId to (sourceId to stationId))
            val next = old.copy(matches = matches); persist(next); mutable.value = next
        }
    }

    suspend fun refresh(force: Boolean = true) = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value = mutable.value.copy(refreshing = true)
            try {
                for (source in mutable.value.sources) {
                    val old = mutable.value
                    if (!force && System.currentTimeMillis() - (old.updated[source.id] ?: 0L) < REFRESH_INTERVAL) continue
                    try {
                        val bytes = download(source.url)
                        val document = parse(bytes)
                        val atomic = AtomicFile(cache(source.id))
                        val output = atomic.startWrite()
                        try { output.write(bytes); atomic.finishWrite(output) }
                        catch (error: Exception) { atomic.failWrite(output); throw error }
                        mutable.value = old.copy(documents = old.documents + (source.id to document),
                            updated = old.updated + (source.id to System.currentTimeMillis()), errors = old.errors - source.id)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        mutable.value = old.copy(errors = old.errors + (source.id to (error.message ?: "更新失败")))
                    }
                }
            } finally { mutable.value = mutable.value.copy(refreshing = false) }
        }
    }

    private fun persist(state: EpgState) {
        val sources = JSONArray()
        state.sources.filterNot { it.builtin }.forEach {
            sources.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url))
        }
        val matches = JSONObject()
        state.matches.forEach { (id, pair) -> matches.put(id, JSONArray().put(pair.first).put(pair.second)) }
        val root = JSONObject().put("sources", sources).put("matches", matches)
        val output = config.startWrite()
        try { output.write(root.toString().toByteArray()); config.finishWrite(output) }
        catch (error: Exception) { config.failWrite(output); throw error }
    }

    private fun cache(id: String) = File(directory, "$id.xml")
    private fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "Tunvix/EPG")
        try {
            require(connection.responseCode in 200..299) { "服务器返回 ${connection.responseCode}" }
            var input: java.io.InputStream = connection.inputStream.buffered()
            repeat(2) {
                val probe = PushbackInputStream(input, 2)
                val first = probe.read(); val second = probe.read()
                if (second >= 0) probe.unread(second)
                if (first >= 0) probe.unread(first)
                input = if (first == 0x1f && second == 0x8b) GZIPInputStream(probe) else probe
            }
            input.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = it.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= MAX_XML_BYTES) { "节目单文件超过 32MB" }
                    output.write(buffer, 0, size)
                }
                return output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun parse(bytes: ByteArray): EpgDocument {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val stations = mutableListOf<EpgStation>()
        val programmes = mutableMapOf<String, MutableList<Programme>>()
        var stationId: String? = null
        var names = mutableListOf<String>()
        var programmeChannel: String? = null
        var start: Long? = null; var end: Long? = null; var title = ""
        var count = 0; var rootSeen = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("不支持含外部声明的节目单")
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tv" -> rootSeen = true
                    "channel" -> { stationId = parser.getAttributeValue(null, "id"); names = mutableListOf() }
                    "display-name" -> if (stationId != null) names.add(parser.nextText().take(256))
                    "programme" -> {
                        programmeChannel = parser.getAttributeValue(null, "channel")
                        start = time(parser.getAttributeValue(null, "start")); end = time(parser.getAttributeValue(null, "stop")); title = ""
                    }
                    "title" -> if (programmeChannel != null) { val text = parser.nextText().take(512); if (title.isEmpty()) title = text }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> { stationId?.takeIf { it.isNotBlank() }?.let { stations.add(EpgStation(it, names.toList())) }; stationId = null }
                    "programme" -> {
                        val from = start; val to = end; val id = programmeChannel
                        if (id != null && from != null && to != null && to > from && title.isNotBlank()) {
                            require(++count <= 200_000) { "节目数量超过限制" }
                            programmes.getOrPut(id) { mutableListOf() }.add(Programme(from, to, title))
                        }
                        programmeChannel = null
                    }
                }
            }
            parser.nextToken()
        }
        require(rootSeen && stations.isNotEmpty() && programmes.isNotEmpty()) { "没有读到有效的 XMLTV 节目单" }
        return EpgDocument(stations, programmes.mapValues { (_, values) -> values.distinct().sortedBy { it.start } })
    }

    private fun time(value: String?): Long? = runCatching {
        val parts = requireNotNull(value).trim().split(Regex("\\s+"))
        val stamp = parts[0]
        require(stamp.length in listOf(12, 14))
        val local = LocalDateTime.parse(if (stamp.length == 12) stamp + "00" else stamp, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        local.toInstant(ZoneOffset.of(parts.getOrElse(1) { "+0000" })).toEpochMilli()
    }.getOrNull()

    companion object {
        val BUILTIN = EpgSource("builtin-ccsh", "内置节目单 · CCSH", "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/e.xml.gz", true)
        private const val REFRESH_INTERVAL = 6 * 60 * 60 * 1000L
        private const val MAX_XML_BYTES = 32 * 1024 * 1024
        private fun checkedUrl(raw: String): String {
            val url = raw.trim()
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的 HTTP 或 HTTPS 节目单地址" }
            return url
        }
    }
}

private fun epgName(raw: String): String {
    val name = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
        .uppercase(java.util.Locale.ROOT).replace(Regex("[\\s_·\\-（）()]"), "")
        .replace("＋", "+").replace(Regex("(高清|超清|标清|频道|HD|SD)$"), "")
    if (name.startsWith("CCTV4") && name.contains("欧洲")) return "CCTV4欧洲"
    if (name.startsWith("CCTV4") && name.contains("美洲")) return "CCTV4美洲"
    Regex("^CCTV(\\d+)(\\+|PLUS|K|欧洲|美洲)?").find(name)?.let { return "CCTV${it.groupValues[1]}${if (it.groupValues[2] == "PLUS") "+" else it.groupValues[2]}" }
    val guizhou = listOf("贵州卫视", "贵州公共", "贵州影视", "贵州大众", "贵州生态", "贵州科教", "贵州经济")
    val local = name.replace("贵州影视文艺", "贵州影视").replace("贵州大众生活", "贵州大众")
        .replace("贵州生态乡村", "贵州生态").replace("贵州科教健康", "贵州科教")
    Regex("^(?:贵州(?:电视台)?|GZTV|GTV)([1-7一二三四五六七])(?:台|套)?$").matchEntire(local)?.let {
        val index = "1234567".indexOf(it.groupValues[1]).takeIf { n -> n >= 0 } ?: "一二三四五六七".indexOf(it.groupValues[1])
        return guizhou[index]
    }
    return local
}
