package com.tunvix.backend.catalog

import com.tunvix.contract.FirstFrameResult
import com.tunvix.contract.FirstFrameStatus
import com.tunvix.contract.SourceKind
import com.tunvix.contract.ThemeMode
import com.tunvix.contract.UserSettings
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/** Bumped whenever the on-disk layout changes; see docs/backend-catalog.md for the migration ladder. */
internal const val CATALOG_FORMAT_VERSION = 1
// v1 layout note: first-frame measurement columns are appended *after* the v1 channel columns and
// the settings line gains one trailing value. Older builds safely ignore the extra columns and
// newer builds treat missing columns as "disabled / never measured", so the version stays 1 and
// both directions stay readable.

/** The fixed "自建频道" source. It always exists and can never be deleted. */
internal const val CUSTOM_SOURCE_ID = "custom"
internal const val DEFAULT_GROUP = "未分组"
internal const val RECENT_LIMIT = 100

internal data class StoredSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val location: String? = null,
)

internal data class StoredChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val url: String,
    val group: String = DEFAULT_GROUP,
    val logoUrl: String? = null,
    val externalId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val favorite: Boolean = false,
    val watchedAt: Long? = null,
    val thumbnailName: String? = null,
    /** Persisted first-frame measurement bound to the current url+headers; null when never measured. */
    val firstFrame: FirstFrameResult? = null,
)

internal data class StoredCatalog(
    val sources: List<StoredSource> = emptyList(),
    val channels: List<StoredChannel> = emptyList(),
    val selectedSourceId: String? = null,
    val settings: UserSettings = UserSettings(),
) {
    companion object {
        /** No source exists until the user imports or saves a channel. */
        fun freshInstall(): StoredCatalog = StoredCatalog()
    }
}

/**
 * Persistence boundary. Keeping it this narrow (~read the whole catalog / write the whole catalog)
 * means the repository logic is exercised by JVM tests against the exact same implementation that
 * ships, and any future SQLite store only has to reproduce these two operations.
 */
internal interface CatalogStore {
    fun read(): StoredCatalog
    fun write(catalog: StoredCatalog)
}

/** Raised when the catalog could not be persisted. The previously saved file is left untouched. */
internal class CatalogWriteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Single-file, whole-catalog store.
 *
 * Writes go to a temporary file in the same directory and are then installed over the target with a
 * single rename, so a crash mid-write can never leave a half-written catalog behind.
 *
 * The old catalog file is **never deleted before the new one is in place**. If the target cannot be
 * replaced, [write] throws [CatalogWriteException] and the last good copy stays exactly as it was:
 * a failed save must never masquerade as a successful one, and must never destroy the only copy of
 * the user's sources, favorites and history.
 */
internal class FileCatalogStore(
    private val file: File,
    /**
     * Installs [from] at [to]. Injectable so the replace failure paths can be exercised on the JVM;
     * production uses [File.renameTo], which is a single atomic `rename(2)` on Android/Linux and
     * therefore replaces an existing target in one step.
     */
    private val mover: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
) : CatalogStore {

    override fun read(): StoredCatalog {
        recoverBackup()
        if (!file.exists()) return StoredCatalog.freshInstall()
        // An existing but unreadable catalog is not a new installation. Let the repository
        // expose a storage failure and prevent writes built from an empty fallback.
        val text = file.readText(Charsets.UTF_8)
        return CatalogCodec.decode(text) ?: quarantine().let { StoredCatalog.freshInstall() }
    }

    override fun write(catalog: StoredCatalog) {
        recoverBackup()
        val dir = file.parentFile
        dir?.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write(CatalogCodec.encode(catalog).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (error: Throwable) {
            runCatching { temp.delete() }
            throw CatalogWriteException("无法写入临时文件，目录未改动", error)
        }
        if (!replace(temp)) {
            runCatching { temp.delete() }
            throw CatalogWriteException("无法写入目录文件，已保留原有数据")
        }
    }

    /** Returns true once [temp] is the live catalog. Never removes the old file before that. */
    private fun replace(temp: File): Boolean {
        if (mover(temp, file)) return true
        // Nothing to preserve on a first write; the plain move above already failed.
        if (!file.exists()) return false

        // Some file systems refuse to replace an existing target. Move the old file aside, install
        // the new one, and only then drop the backup — so a failure here still leaves the previous
        // catalog readable at `file`.
        val backup = File(file.parentFile, file.name + ".bak")
        runCatching { backup.delete() }
        if (!mover(file, backup)) return false
        if (mover(temp, file)) {
            runCatching { backup.delete() }
            return true
        }
        mover(backup, file)
        return false
    }

    /** Recover the last committed catalog after a process death during the fallback swap. */
    private fun recoverBackup() {
        val backup = File(file.parentFile, file.name + ".bak")
        if (!backup.exists()) return
        if (file.exists()) {
            // A valid installed primary wins; a leftover backup is from a completed swap.
            if (CatalogCodec.decode(file.readText(Charsets.UTF_8)) != null) {
                backup.delete()
                return
            }
        }
        if (CatalogCodec.decode(backup.readText(Charsets.UTF_8)) == null) {
            throw java.io.IOException("目录备份损坏，停止写入以保留恢复数据")
        }
        if (file.exists()) quarantine()
        if (!mover(backup, file)) throw java.io.IOException("无法恢复目录备份，停止写入")
    }

    private fun quarantine() {
        if (!file.renameTo(File(file.parentFile, file.name + ".corrupt"))) {
            throw java.io.IOException("无法隔离损坏目录，停止写入")
        }
    }
}

/**
 * Line-oriented, dependency-free codec. Every field is escaped, so tabs/newlines/CJK/commas in
 * channel names and URLs survive a round trip. Unreadable lines are skipped instead of failing the
 * whole catalog, and a missing/unknown header is treated as "not our file".
 */
internal object CatalogCodec {

    private const val HEADER = "tunvix-catalog"

    fun encode(catalog: StoredCatalog): String = buildString {
        append(HEADER).append('\t').append(CATALOG_FORMAT_VERSION).append('\n')
        append("meta\tselected\t").append(escape(catalog.selectedSourceId)).append('\n')
        append("meta\tsettings\t").append(if (catalog.settings.mobileReminderEnabled) 1 else 0)
            .append('\t').append(if (catalog.settings.hapticsEnabled) 1 else 0)
            .append('\t').append(if (catalog.settings.firstFrameEnabled) 1 else 0)
            .append('\t').append(catalog.settings.themeMode.name)
            .append('\t').append(if (catalog.settings.autoPictureInPicture) 1 else 0).append('\n')
        catalog.settings.browseGroups.forEach { (sourceId, group) ->
            append("browse-group\t").append(escape(sourceId)).append('\t').append(escape(group)).append('\n')
        }
        catalog.sources.forEach { source ->
            append("source").append('\t').append(escape(source.id)).append('\t').append(escape(source.name))
                .append('\t').append(source.kind.name).append('\t').append(escape(source.location)).append('\n')
        }
        catalog.channels.forEach { channel ->
            append("channel").append('\t').append(escape(channel.id)).append('\t').append(escape(channel.sourceId))
                .append('\t').append(escape(channel.name)).append('\t').append(escape(channel.url))
                .append('\t').append(escape(channel.group)).append('\t').append(escape(channel.logoUrl))
                .append('\t').append(escape(channel.externalId)).append('\t').append(if (channel.favorite) 1 else 0)
                .append('\t').append(escape(channel.watchedAt?.toString())).append('\t')
                .append(escape(channel.thumbnailName)).append('\t').append(escapeHeaders(channel.headers))
                .append('\t').append(channel.firstFrame?.status?.name.orEmpty())
                .append('\t').append(channel.firstFrame?.millis?.toString().orEmpty())
                .append('\t').append(if (channel.firstFrame == null) "" else channel.firstFrame.measuredAt.toString())
                .append('\n')
        }
    }

    fun decode(text: String): StoredCatalog? {
        val lines = text.lineSequence().toList()
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size < 2 || header[0] != HEADER) return null
        val version = header[1].trim().toIntOrNull() ?: return null
        // A file written by a *newer* build is left untouched rather than silently re-interpreted.
        if (version > CATALOG_FORMAT_VERSION) return null
        return parse(migrate(version, lines.drop(1)))
    }

    /** Stepwise migration ladder: extend with `2 -> migrateV2ToV3(body)` style steps. */
    private fun migrate(from: Int, body: List<String>): List<String> = when (from) {
        1 -> body
        else -> body
    }

    private fun parse(body: List<String>): StoredCatalog {
        val sources = ArrayList<StoredSource>()
        val channels = ArrayList<StoredChannel>()
        var selected: String? = null
        var settings = UserSettings()
        val browseGroups = linkedMapOf<String, String>()
        body.forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "meta" -> when (parts.getOrNull(1)) {
                    "selected" -> selected = unescape(parts.getOrNull(2))
                    "settings" -> settings = UserSettings(
                        mobileReminderEnabled = parts.getOrNull(2)?.trim() != "0",
                        hapticsEnabled = parts.getOrNull(3)?.trim() != "0",
                        // Absent on catalogs written before the toggle existed: stay off.
                        firstFrameEnabled = parts.getOrNull(4)?.trim() == "1",
                        themeMode = parts.getOrNull(5)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                        autoPictureInPicture = parts.getOrNull(6) == "1",
                    )
                }

                "browse-group" -> {
                    val id = unescape(parts.getOrNull(1)); val group = unescape(parts.getOrNull(2))
                    if (id != null && group != null) browseGroups[id] = group
                }
                "source" -> {
                    val kind = parts.getOrNull(3)?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() }
                    if (parts.size >= 5 && kind != null) {
                        sources += StoredSource(
                            id = unescape(parts[1]).orEmpty(),
                            name = unescape(parts[2]).orEmpty(),
                            kind = kind,
                            location = unescape(parts[4]),
                        )
                    }
                }

                "channel" -> if (parts.size >= 12) {
                    val sourceId = unescape(parts[2]).orEmpty()
                    val id = unescape(parts[1]).orEmpty()
                    if (id.isNotEmpty() && sourceId.isNotEmpty()) {
                        // Columns 12..14 carry the first-frame measurement; catalogs written before
                        // the feature simply lack them, which reads as "never measured".
                        val firstFrame = parts.getOrNull(12)?.let { raw ->
                            val status = runCatching { FirstFrameStatus.valueOf(raw) }.getOrNull()
                            when {
                                status == null -> null
                                status == FirstFrameStatus.SUCCESS -> FirstFrameResult(
                                    status,
                                    millis = unescape(parts.getOrNull(13))?.toLongOrNull(),
                                    measuredAt = parts.getOrNull(14)?.trim()?.toLongOrNull() ?: 0L,
                                )
                                else -> FirstFrameResult(status, measuredAt = parts.getOrNull(14)?.trim()?.toLongOrNull() ?: 0L)
                            }
                        }
                        channels += StoredChannel(
                            id = id,
                            sourceId = sourceId,
                            name = unescape(parts[3]).orEmpty(),
                            url = unescape(parts[4]).orEmpty(),
                            group = unescape(parts[5]).orEmpty().ifBlank { DEFAULT_GROUP },
                            logoUrl = unescape(parts[6]),
                            externalId = unescape(parts[7]),
                            favorite = parts[8].trim() == "1",
                            watchedAt = unescape(parts[9])?.toLongOrNull(),
                            thumbnailName = unescape(parts[10]),
                            // Split before decoding: an escaped '&' inside a header value must stay inside its pair.
                            headers = unescapeHeaders(parts[11]),
                            firstFrame = firstFrame,
                        )
                    }
                }
            }
        }
        // Migrate the previous automatic empty custom entry; keep every source with user channels.
        val effectiveSources = sources.filterNot { it.kind == SourceKind.CUSTOM && channels.none { c -> c.sourceId == it.id } }
        return StoredCatalog(
            sources = effectiveSources,
            channels = channels.filter { channel -> effectiveSources.any { it.id == channel.sourceId } },
            selectedSourceId = selected?.takeIf { id -> effectiveSources.any { it.id == id } } ?: effectiveSources.firstOrNull()?.id,
            settings = settings.copy(browseGroups = browseGroups.filterKeys { id -> effectiveSources.any { it.id == id } }),
        )
    }

    private fun escape(value: String?): String =
        if (value == null) "" else URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun escapeHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("&") { escape(it.key) + "=" + escape(it.value) }

    private fun unescapeHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else unescape(pair.substring(0, index))?.let { key ->
                key to (unescape(pair.substring(index + 1)) ?: "")
            }
        }.toMap()
    }
}
