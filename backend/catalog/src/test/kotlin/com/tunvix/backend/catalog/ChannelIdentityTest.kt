package com.tunvix.backend.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChannelIdentityTest {

    private fun stored(
        id: String,
        name: String,
        url: String,
        group: String = DEFAULT_GROUP,
        externalId: String? = null,
    ) = StoredChannel(id = id, sourceId = "s", name = name, url = url, group = group, externalId = externalId)

    @Test
    fun providerIdWinsEvenWhenNameAndUrlBothChange() {
        val existing = listOf(stored("c1", "旧名称", "https://p/old.m3u8", "新闻", externalId = "cctv1"))
        val ids = ChannelIdentity.matchIds(
            listOf(draft("CCTV-1 综合", "https://p/new.m3u8", "综合", externalId = "cctv1")),
            existing,
        )
        assertEquals(listOf("c1"), ids)
    }

    @Test
    fun rotatedAccessTokenFallsBackToAStableUrlKey() {
        val existing = listOf(stored("c1", "频道一", "https://p/live/1?token=aaa&e=111"))
        val ids = ChannelIdentity.matchIds(
            listOf(draft("频道一 高清", "https://p/live/1?e=222&token=bbb")),
            existing,
        )
        assertEquals(listOf("c1"), ids)
    }

    @Test
    fun volatileQueryKeysAreIgnoredButPathAndHostAreNot() {
        assertEquals(
            ChannelIdentity.volatileFreeUrl("https://p/live/1?token=a&e=1&ts=9"),
            ChannelIdentity.volatileFreeUrl("https://p/live/1?e=2&ts=8&token=b"),
        )
        assertEquals(
            ChannelIdentity.volatileFreeUrl("https://P.example:8080/live/1"),
            ChannelIdentity.volatileFreeUrl("https://p.example:8080/live/1?token=zzz"),
        )
        assertNotEquals(
            ChannelIdentity.volatileFreeUrl("https://p/live/1?token=a"),
            ChannelIdentity.volatileFreeUrl("https://p/live/2?token=a"),
        )
        assertNotEquals(
            ChannelIdentity.volatileFreeUrl("https://p/live/1"),
            ChannelIdentity.volatileFreeUrl("https://other/live/1"),
        )
    }

    @Test
    fun eachExistingChannelIsClaimedAtMostOnce() {
        val existing = listOf(stored("c1", "同名", "https://p/1", "新闻"))
        val ids = ChannelIdentity.matchIds(
            listOf(draft("同名", "https://p/1", "新闻"), draft("同名", "https://p/1", "新闻")),
            existing,
        )
        assertEquals(listOf("c1", null), ids)
    }

    @Test
    fun genuinelyNewChannelsGetNoMatch() {
        val existing = listOf(stored("c1", "频道一", "https://p/1", "新闻"))
        val ids = ChannelIdentity.matchIds(listOf(draft("频道二", "https://p/2", "新闻")), existing)
        assertEquals(listOf(null), ids)
    }

    @Test
    fun stillFingerprintFollowsUrlAndHeaders() {
        val base = ChannelIdentity.fingerprint("https://p/1.m3u8?token=a", mapOf("Referer" to "https://r/"))
        assertEquals(base, ChannelIdentity.fingerprint("https://p/1.m3u8?token=a", mapOf("Referer" to "https://r/")))
        assertNotEquals(base, ChannelIdentity.fingerprint("https://p/1.m3u8?token=b", mapOf("Referer" to "https://r/")))
        assertNotEquals(base, ChannelIdentity.fingerprint("https://p/1.m3u8?token=a", mapOf("Referer" to "https://other/")))
    }

    @Test
    fun blankNamesFallBackToTheDefaultGroup() {
        assertEquals(DEFAULT_GROUP, ChannelIdentity.normalizedGroup("   "))
        assertEquals("体育", ChannelIdentity.normalizedGroup(" 体育 "))
        assertEquals(
            ChannelIdentity.nameGroupKey("cctv1", "未分组"),
            ChannelIdentity.nameGroupKey("  CCTV1 ", "  未分组  "),
        )
    }
}
