package com.tunvix.contract

import java.util.Locale

/** Presentation grouping only: source, original group and significant name remain separate. */
object ChannelLines {
    private val separators = Regex("[\\s\\p{Z}\\p{Pd}]+")
    fun nameKey(name: String): String = name.lowercase(Locale.ROOT).replace(separators, "")
    fun sameChannel(a: Channel, b: Channel): Boolean = a.sourceId == b.sourceId && a.group == b.group && nameKey(a.name) == nameKey(b.name)
    fun fastest(lines: List<Channel>): Channel? = lines.minByOrNull {
        it.firstFrame?.takeIf { result -> result.status == FirstFrameStatus.SUCCESS }?.millis ?: Long.MAX_VALUE
    }
    fun representatives(channels: List<Channel>): List<Channel> = channels
        .groupBy { Triple(it.sourceId, it.group, nameKey(it.name)) }.values.mapNotNull(::fastest)
}
