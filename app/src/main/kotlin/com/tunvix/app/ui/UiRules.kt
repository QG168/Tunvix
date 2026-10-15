package com.tunvix.app.ui
import com.tunvix.contract.*
import java.net.URI

/** 第一版体验分档：用于比较打开速度，不代表网络 Ping 或直播延迟。 */
enum class FrameTier { FAST, MEDIUM, SLOW, TIMEOUT, FAILED, PENDING }

object UiRules {
    fun restoredGroup(snapshot: CatalogSnapshot): String {
        val groups = snapshot.channels.filter { it.sourceId == snapshot.selectedSourceId }.map { it.group }.distinct()
        return snapshot.settings.browseGroups[snapshot.selectedSourceId]?.takeIf { it in groups } ?: groups.firstOrNull() ?: "未分组"
    }

    fun thumbnailWindow(channels: List<Channel>, visibleIndices: List<Int>): List<String> {
        val visible = visibleIndices.distinct().sorted().filter { it in channels.indices }
        if (visible.isEmpty()) return emptyList()
        val nextStart = visible.last() + 1
        return (visible + (nextStart until minOf(channels.size, nextStart + visible.size.coerceAtLeast(2))))
            .map { channels[it].id }
    }

    fun controlsAutoHide(page: Page, phase: PlaybackPhase, foreground: Boolean, obscured: Boolean): Boolean =
        page == Page.PLAYER && phase == PlaybackPhase.PLAYING && foreground && !obscured

    fun darkAppearance(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun keepScreenAwake(page: Page, phase: PlaybackPhase, foreground: Boolean, showIndex: Boolean): Boolean =
        foreground && page == Page.PLAYER && phase == PlaybackPhase.PLAYING && !showIndex

    /**
     * 分档使用测量的毫秒值，不先四舍五入成秒：1000 ms 属于绿色，3000 ms 属于黄色。
     * 超时/失败依照任务结果判断，不能仅因数值变红就标为失败，也不能把失败显示成 0。
     */
    fun firstFrameTier(result: FirstFrameResult?, detecting: Boolean = false): FrameTier {
        if (detecting) return FrameTier.PENDING
        if (result == null) return FrameTier.PENDING
        val millis = result.millis
        return when {
            result.status == FirstFrameStatus.TIMEOUT -> FrameTier.TIMEOUT
            result.status == FirstFrameStatus.FAILED -> FrameTier.FAILED
            result.status != FirstFrameStatus.SUCCESS || millis == null -> FrameTier.PENDING
            millis <= 1000 -> FrameTier.FAST
            millis <= 3000 -> FrameTier.MEDIUM
            else -> FrameTier.SLOW
        }
    }

    /** 标签文字：未检测为「—」，正在检测为「…」，超时为「超时」，其他失败为「失败」。 */
    fun firstFrameLabel(result: FirstFrameResult?, detecting: Boolean = false): String = when {
        detecting -> "…"
        result == null -> "—"
        result.status == FirstFrameStatus.TIMEOUT -> "超时"
        result.status == FirstFrameStatus.FAILED -> "失败"
        result.millis != null -> "${result.millis}ms"
        else -> "—"
    }

    fun nextChannel(channels: List<Channel>, current: Channel, delta: Int): Channel {
        val group = channels.filter { it.sourceId == current.sourceId && it.group == current.group }
        if (group.isEmpty()) return current
        val index = group.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        return group[Math.floorMod(index + delta, group.size)]
    }
    fun visibleChannels(snapshot: CatalogSnapshot, group: String, query: String): List<Channel> = snapshot.channels.filter {
        it.sourceId == snapshot.selectedSourceId && if (query.isNotBlank()) it.name.contains(query.trim(), ignoreCase = true) else it.group == group
    }
    fun validHttpAddress(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
