@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tunvix.backend.catalog

import com.tunvix.contract.Outcome
import com.tunvix.contract.SourceKind
import com.tunvix.contract.UserSettings
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPersistenceTest {

    @Test fun legacyEmptyCustomSourceIsRemovedAndSelectionMovesToImportedSource() {
        val legacy=StoredCatalog(
            sources=listOf(StoredSource(CUSTOM_SOURCE_ID,"自建频道",SourceKind.CUSTOM,null),
                StoredSource("imported","订阅",SourceKind.SUBSCRIPTION,"https://p/list")),
            channels=listOf(StoredChannel("c","imported","电视","https://p/live")),
            selectedSourceId=CUSTOM_SOURCE_ID)
        val migrated=CatalogCodec.decode(CatalogCodec.encode(legacy))!!
        assertEquals(listOf("imported"),migrated.sources.map{it.id})
        assertEquals("imported",migrated.selectedSourceId)
        assertEquals(legacy.channels,migrated.channels)
    }
    @Test
    fun everythingSurvivesARestart() = runTest {
        val h = harness()
        val repo = h.newRepository()
        val headers = mapOf("User-Agent" to "Tunvix/1.0", "Referer" to "https://p/?a=1&b=2")
        repo.commitImport(
            "订阅, 甲",
            h.request(),
            report(
                draft(
                    "频道, 一",
                    "https://p/1.m3u8?token=abc",
                    "新闻",
                    externalId = "1",
                    logoUrl = "https://p/logo.png",
                    headers = headers,
                ),
                draft("频道\t二", "https://p/2.m3u8", "体育"),
            ),
        )
        val sourceId = repo.snapshot.value.sources.last().id
        val first = repo.snapshot.value.channels.first { it.name == "频道, 一" }
        repo.setFavorite(first.id, true)
        h.now += 5_000
        repo.recordWatched(first.id)
        repo.updateSettings(UserSettings(mobileReminderEnabled = false, hapticsEnabled = true))
        repo.saveCustomChannel(null, draft("自建1", "https://custom/1.m3u8", "自建分组"))
        val customId = repo.snapshot.value.channels.first { it.name == "自建1" }.id
        repo.selectSource(sourceId)
        repo.requestThumbnails(listOf(first.id), allowNetwork = true)
        advanceUntilIdle()

        val before = repo.snapshot.value
        assertNotNull(before.channels.first { it.id == first.id }.thumbnailUri)
        assertTrue(before.cacheBytes > 0)

        val after = TestHarness(this, h.dir).newRepository().snapshot.value

        assertEquals(before, after)
        assertEquals(headers, after.channels.first { it.id == first.id }.headers)
        assertEquals(UserSettings(false, true), after.settings)
        assertEquals(setOf(first.id), after.favoriteIds)
        assertEquals(listOf(first.id), after.recentIds)
        assertEquals(sourceId, after.selectedSourceId)
        assertEquals(sourceId, after.sources.first { it.id==sourceId }.id)
        assertEquals(SourceKind.SUBSCRIPTION, after.sources.first { it.id==sourceId }.kind)
        assertTrue(after.channels.any { it.id == customId && it.sourceId == CUSTOM_SOURCE_ID })
        assertEquals("新闻", after.channels.first { it.id == first.id }.group)
        assertEquals("1", after.channels.first { it.id == first.id }.externalId)
        assertEquals("https://p/logo.png", after.channels.first { it.id == first.id }.logoUrl)
        assertEquals("频道\t二", after.channels.first { it.name.startsWith("频道") && it.name.endsWith("二") }.name)
    }

    @Test
    fun editingTheSameStillTwiceKeepsOnlyOneFilePerChannel() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8")))
        val id = repo.snapshot.value.channels.single().id

        repeat(3) {
            repo.requestThumbnails(listOf(id), allowNetwork = true)
            advanceUntilIdle()
        }
        val files = File(h.dir, "thumbnails").listFiles().orEmpty().filter { it.name.startsWith(id) }
        assertEquals(1, files.size)
        assertEquals(files.single().length(), repo.snapshot.value.cacheBytes)
    }

    @Test
    fun aRotatedTokenInvalidatesTheStoredStillWithoutLosingTheChannel() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport(
            "A源",
            h.request(),
            report(draft("新闻", "https://p/1.m3u8?token=aaa", "新闻", externalId = "1")),
        )
        val sourceId = repo.snapshot.value.sources.last().id
        val id = repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id), allowNetwork = true)
        advanceUntilIdle()
        assertNotNull(repo.snapshot.value.channels.single().thumbnailUri)

        h.importer.next = Outcome.Success(report(draft("新闻", "https://p/1.m3u8?token=bbb", "新闻", externalId = "1")))
        assertTrue(repo.refreshSource(sourceId) is Outcome.Success)

        val after = repo.snapshot.value
        assertEquals(id, after.channels.single().id)
        assertNull(after.channels.single().thumbnailUri)
        assertEquals(0L, after.cacheBytes)
        assertTrue(File(h.dir, "thumbnails").listFiles().orEmpty().isEmpty())

        repo.requestThumbnails(listOf(id), allowNetwork = true)
        advanceUntilIdle()
        assertNotNull(repo.snapshot.value.channels.single().thumbnailUri)
    }

    @Test
    fun cacheClearingKeepsSourcesFavoritesHistoryAndSettings() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8"), draft("B", "https://p/b.m3u8")))
        val id = repo.snapshot.value.channels.first().id
        repo.setFavorite(id, true)
        repo.recordWatched(id)
        repo.updateSettings(UserSettings(mobileReminderEnabled = false, hapticsEnabled = false))
        repo.requestThumbnails(listOf(id), allowNetwork = true)
        advanceUntilIdle()
        assertTrue(repo.snapshot.value.cacheBytes > 0)

        assertTrue(repo.clearThumbnailCache() is Outcome.Success)
        val after = repo.snapshot.value
        assertEquals(0L, after.cacheBytes)
        assertEquals(2, after.channels.size)
        assertEquals(setOf(id), after.favoriteIds)
        assertEquals(listOf(id), after.recentIds)
        assertEquals(UserSettings(false, false), after.settings)
        assertEquals(1, after.sources.size)
        assertTrue(File(h.dir, "thumbnails").listFiles().orEmpty().isEmpty())

        // and the cleared state is what a restart reads back
        val restarted = TestHarness(this, h.dir).newRepository().snapshot.value
        assertEquals(after, restarted)
    }

    @Test
    fun codecRoundTripsTrickyNamesUrlsAndHeaders() {
        val catalog = StoredCatalog(
            sources = listOf(
                StoredSource(CUSTOM_SOURCE_ID, "自建频道", SourceKind.CUSTOM, null),
                StoredSource("s1", "订阅\t带制表符", SourceKind.LOCAL_FILE, "content://a/b?x=1&y=2"),
            ),
            channels = listOf(
                StoredChannel(
                    id = "c1",
                    sourceId = "s1",
                    name = "频道, 一\n换行",
                    url = "https://p/1.m3u8?token=a&b=c",
                    group = "体育",
                    logoUrl = "https://p/l.png",
                    externalId = "id,1",
                    headers = mapOf("User-Agent" to "Tunvix & Co", "Referer" to "https://r/?a=1&b=2"),
                    favorite = true,
                    watchedAt = 12345L,
                    thumbnailName = "c1-abcdef0123456789.jpg",
                ),
            ),
            selectedSourceId = "s1",
            settings = UserSettings(mobileReminderEnabled = false, hapticsEnabled = true),
        )

        val decoded = CatalogCodec.decode(CatalogCodec.encode(catalog))
        assertEquals(catalog.copy(sources=catalog.sources.filterNot{it.kind==SourceKind.CUSTOM}), decoded)
    }

    @Test
    fun aCatalogWrittenByANewerBuildIsNotSilentlyReinterpreted() {
        assertNull(CatalogCodec.decode("tunvix-catalog\t99\nmeta\tselected\tcustom\n"))
        assertNull(CatalogCodec.decode("some-other-file\n"))
        assertNull(CatalogCodec.decode(""))
    }

    @Test
    fun unreadableLinesAreSkippedInsteadOfLosingTheWholeCatalog() {
        val text = buildString {
            append("tunvix-catalog\t1\n")
            append("meta\tselected\tcustom\n")
            append("source\tcustom\t自建频道\tCUSTOM\t\n")
            append("channel\tbroken\n")
            append("channel\tc1\tcustom\t频道一\thttps://x/1\t体育\t\t\t0\t\t\t\n")
        }
        val decoded = CatalogCodec.decode(text)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.channels.size)
        assertEquals("频道一", decoded.channels.single().name)
        assertEquals("体育", decoded.channels.single().group)
    }

    @Test
    fun aCorruptFileIsQuarantinedAndReplacedByAFreshInstall() {
        val dir = tempDir()
        val file = File(dir, "catalog.tsv")
        file.writeText("garbage that is not a catalog")

        val store = FileCatalogStore(file)
        val catalog = store.read()
        assertTrue(catalog.sources.isEmpty())

        assertTrue(File(dir, "catalog.tsv.corrupt").exists())
        assertFalse(file.exists())

        store.write(catalog)
        assertNull(FileCatalogStore(file).read().selectedSourceId)
    }

    @Test
    fun writingIsAtomicAndLeavesNoTemporaryFileBehind() {
        val dir = tempDir()
        val file = File(dir, "catalog.tsv")
        val store = FileCatalogStore(file)
        store.write(StoredCatalog(sources = listOf(StoredSource("s1", "甲", SourceKind.SUBSCRIPTION, "https://p/l")), selectedSourceId = "s1"))

        assertFalse(File(dir, "catalog.tsv.tmp").exists())
        assertEquals("s1", FileCatalogStore(file).read().selectedSourceId)
    }

    @Test
    fun twoFailedReplacementsKeepTheLastGoodCatalogAndReportTheFailure() {
        val dir = tempDir()
        val file = File(dir, "catalog.tsv")
        FileCatalogStore(file).write(
            StoredCatalog(sources = listOf(StoredSource("s1", "甲", SourceKind.SUBSCRIPTION, "https://p/l")), selectedSourceId = "s1"),
        )

        // Both the direct replace and the backup swap fail.
        var attempts = 0
        val failing = FileCatalogStore(file, mover = { from, to -> if (attempts++ < 2) false else from.renameTo(to) })
        val replacement = StoredCatalog(
            sources = listOf(StoredSource("s2", "乙", SourceKind.SUBSCRIPTION, "https://p/m")),
            selectedSourceId = "s2",
        )
        assertThrows(CatalogWriteException::class.java) { failing.write(replacement) }

        // The failure is reported, the old catalog is exactly what a restart reads, no debris is left.
        val restarted = FileCatalogStore(file).read()
        assertEquals("s1", restarted.selectedSourceId)
        assertEquals("甲", restarted.sources.single { it.id == "s1" }.name)
        assertFalse(File(dir, "catalog.tsv.tmp").exists())
        assertFalse(File(dir, "catalog.tsv.bak").exists())
    }

    @Test
    fun aFailedDirectRenameFallsBackToASwapThatOnlyDropsTheOldFileOnceTheNewOneIsInPlace() {
        val dir = tempDir()
        val file = File(dir, "catalog.tsv")
        FileCatalogStore(file).write(
            StoredCatalog(sources = listOf(StoredSource("s1", "甲", SourceKind.SUBSCRIPTION, "https://p/l")), selectedSourceId = "s1"),
        )

        // Only the first attempt fails; the backup swap then succeeds.
        var attempts = 0
        val swapping = FileCatalogStore(file, mover = { from, to -> if (attempts++ < 1) false else from.renameTo(to) })
        swapping.write(
            StoredCatalog(sources = listOf(StoredSource("s2", "乙", SourceKind.SUBSCRIPTION, "https://p/m")), selectedSourceId = "s2"),
        )

        assertEquals("s2", FileCatalogStore(file).read().selectedSourceId)
        assertFalse(File(dir, "catalog.tsv.tmp").exists())
        assertFalse(File(dir, "catalog.tsv.bak").exists())
    }
    @Test fun interruptedSwapRecoversBackupOnRestart() {
        val dir = tempDir(); val file = File(dir, "catalog.tsv")
        val old = StoredCatalog.freshInstall().copy(settings = UserSettings(hapticsEnabled = false))
        FileCatalogStore(file).write(old)
        assertTrue(file.renameTo(File(dir, "catalog.tsv.bak")))
        assertEquals(old, FileCatalogStore(file).read())
        assertTrue(file.exists())
    }

    @Test fun failedRollbackStillRecoversLastGoodBackup() {
        val dir = tempDir(); val file = File(dir, "catalog.tsv")
        val old = StoredCatalog.freshInstall().copy(settings = UserSettings(hapticsEnabled = false))
        FileCatalogStore(file).write(old)
        val failing = FileCatalogStore(file, mover = { from, to ->
            if (to.name.endsWith(".bak")) from.renameTo(to) else false
        })
        assertThrows(CatalogWriteException::class.java) { failing.write(StoredCatalog.freshInstall()) }
        assertEquals(old, FileCatalogStore(file).read())
    }

    @Test fun existingUnreadableCatalogIsNotTreatedAsFreshInstall() {
        val dir = tempDir(); val file = File(dir, "catalog.tsv")
        assertTrue(file.mkdir()) // deterministic read failure: the catalog entry is a directory
        assertThrows(java.io.IOException::class.java) { FileCatalogStore(file).read() }
        assertTrue(file.isDirectory)
    }

}
