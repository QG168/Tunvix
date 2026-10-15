package com.tunvix.backend.catalog

import com.tunvix.contract.ChannelDraft
import java.net.URI
import java.security.MessageDigest

/**
 * Channel identity rules used when a source is refreshed or re-imported.
 *
 * IDs must survive a refresh (favorites, recents and the player all key off them), but a plain URL
 * comparison is not enough: many IPTV providers rotate a token in the query string on every
 * response while the channel itself is unchanged.
 *
 * Matching is always scoped to a single source by the caller, so identical names in two different
 * sources stay separate channels.
 */
internal object ChannelIdentity {

    /**
     * Query parameters that providers rotate without changing the underlying stream. Only used as the
     * last-resort match key, after tvg-id and name+group have failed.
     */
    private val VOLATILE_QUERY_KEYS = setOf(
        "token", "tokenid", "accesstoken", "accesskey", "sign", "signature", "signaturev2",
        "expire", "expires", "expiry", "expiretime", "auth", "authorization", "key", "keyid",
        "apikey", "nonce", "rand", "random", "salt", "timestamp", "time", "ts", "st", "t", "e",
        "txsecret", "txtime", "session", "sid", "playback", "start", "end", "starttime", "endtime",
    )

    fun normalizedName(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    fun normalizedGroup(raw: String): String = raw.trim().ifBlank { DEFAULT_GROUP }

    fun nameGroupKey(name: String, group: String): String =
        normalizedName(name) + '\u0001' + normalizedName(group)

    /** URL with volatile query parameters removed and the remaining ones order-independent. */
    fun volatileFreeUrl(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            val uri = URI(trimmed)
            val query = uri.rawQuery
                ?.split('&')
                ?.filterNot { it.substringBefore('=').lowercase() in VOLATILE_QUERY_KEYS }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            URI(uri.scheme?.lowercase(), uri.rawAuthority?.lowercase(), uri.rawPath, query, null).toString()
        }.getOrDefault(trimmed.lowercase())
    }

    /** Short stable hash of everything that makes two cached stills interchangeable. */
    fun fingerprint(url: String, headers: Map<String, String>): String {
        val material = buildString {
            append(url.trim())
            headers.entries.sortedBy { it.key.lowercase() }.forEach { append('\n').append(it.key).append(':').append(it.value) }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Aligns freshly imported drafts with the channels already stored for the same source.
     * Returns, per draft, the id of the channel it should keep — or null when it is genuinely new.
     * Every existing channel is claimed at most once.
     */
    fun matchIds(drafts: List<ChannelDraft>, existing: List<StoredChannel>): List<String?> {
        val result = arrayOfNulls<String>(drafts.size)
        if (drafts.isEmpty() || existing.isEmpty()) return result.toList()
        val claimed = HashSet<String>()

        fun pass(keyOf: (StoredChannel) -> String?, draftKeyOf: (ChannelDraft) -> String?) {
            if (claimed.size >= drafts.size) return
            val buckets = HashMap<String, ArrayDeque<StoredChannel>>()
            existing.forEach { channel ->
                if (channel.id in claimed) return@forEach
                val key = keyOf(channel) ?: return@forEach
                buckets.getOrPut(key) { ArrayDeque() }.addLast(channel)
            }
            drafts.forEachIndexed { index, draft ->
                if (result[index] != null) return@forEachIndexed
                val key = draftKeyOf(draft) ?: return@forEachIndexed
                val bucket = buckets[key] ?: return@forEachIndexed
                while (bucket.isNotEmpty()) {
                    val candidate = bucket.removeFirst()
                    if (candidate.id in claimed) continue
                    claimed += candidate.id
                    result[index] = candidate.id
                    return@forEachIndexed
                }
            }
        }

        val externalId: (String?) -> String? = { raw -> raw?.trim()?.takeIf { it.isNotEmpty() } }
        pass({ externalId(it.externalId) }, { externalId(it.externalId) })
        pass({ nameGroupKey(it.name, it.group) }, { nameGroupKey(it.name, it.group) })
        pass({ volatileFreeUrl(it.url) }, { volatileFreeUrl(it.url) })
        return result.toList()
    }
}
