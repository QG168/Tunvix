@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tunvix.backend.catalog

import com.tunvix.contract.ImportRequest
import com.tunvix.contract.Outcome
import com.tunvix.contract.SourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogRepositoryTest {

    @Test fun threeWorkersSkipTimedOutChannelAndKeepFillingSlots() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(*(0..5).map { draft("$it","https://p/$it") }.toTypedArray()))
        val ids=repo.snapshot.value.channels.map{it.id}
        ids.take(3).forEach { h.thumbnailSource.gates[it]=CompletableDeferred() }
        repo.requestThumbnails(ids,true);runCurrent()
        assertEquals(ids.take(3).toSet(),h.thumbnailSource.requested.toSet())
        advanceTimeBy(7999);runCurrent()
        assertEquals(3,h.thumbnailSource.requested.size)
        advanceTimeBy(1);runCurrent();advanceUntilIdle()
        assertEquals(ids.toSet(),h.thumbnailSource.requested.toSet())
        assertEquals(3,h.thumbnailSource.peak.get());assertEquals(0,h.thumbnailSource.active.get())
        assertTrue(ids.take(3).all { it in h.thumbnailSource.cancelledIds })
        assertTrue(repo.snapshot.value.channels.drop(3).all { it.thumbnailUri!=null })
    }

    @Test fun manualRefreshUsesThreeWorkersAndReturnsPartialResult() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(*(0..4).map { draft("$it","https://p/$it") }.toTypedArray()))
        val ids=repo.snapshot.value.channels.map{it.id}
        ids.take(3).forEach { h.thumbnailSource.gates[it]=CompletableDeferred() }
        val refresh=async { repo.refreshThumbnails(ids) };runCurrent()
        assertEquals(ids.take(3).toSet(),h.thumbnailSource.requested.toSet())
        advanceUntilIdle()
        val result=refresh.await() as Outcome.Failure
        assertTrue(result.message.contains("2/5"))
        assertTrue(repo.snapshot.value.channels.drop(3).all { it.thumbnailUri!=null })
    }

    @Test fun cancellingBeforeWorkersStartDoesNotLeakSlots() = runTest {
        val h=harness()
        // Make requests inline while worker launches remain queued on the test scope.
        val repo=LocalCatalogRepository(FileCatalogStore(File(h.dir,"catalog.tsv")),h.importer,
            ThumbnailStore(File(h.dir,"thumbnails")),h.thumbnailSource,
            io=kotlinx.coroutines.Dispatchers.Unconfined,scope=this)
        repo.commitImport("组",h.request(),report(*(0..3).map{draft("$it","https://p/$it")}.toTypedArray()))
        val ids=repo.snapshot.value.channels.map{it.id}
        repo.requestThumbnails(ids,true)
        repo.requestThumbnails(emptyList(),false)
        runCurrent()
        assertTrue(h.thumbnailSource.requested.isEmpty())
        repo.requestThumbnails(ids,true);advanceUntilIdle()
        assertTrue(repo.snapshot.value.channels.all { it.thumbnailUri!=null })
    }

    @Test fun manualRefreshCancelsAutomaticWorkersBeforeStartingAndCanBeStopped() = runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(*(0..4).map{draft("$it","https://p/$it")}.toTypedArray()))
        val ids=repo.snapshot.value.channels.map{it.id}
        ids.forEach { h.thumbnailSource.gates[it]=CompletableDeferred() }
        repo.requestThumbnails(ids,true);runCurrent()
        assertEquals(3,h.thumbnailSource.requested.size)
        val manual=async { repo.refreshThumbnails(ids) };runCurrent()
        assertEquals(ids.take(3).toSet(),h.thumbnailSource.cancelledIds.toSet())
        assertEquals(6,h.thumbnailSource.requested.size)
        assertEquals(3,h.thumbnailSource.peak.get())
        repo.requestThumbnails(emptyList(),false);runCurrent()
        assertTrue(manual.isCancelled)
        val stoppedRequests=h.thumbnailSource.requested.size
        advanceUntilIdle();assertEquals(stoppedRequests,h.thumbnailSource.requested.size)
        h.thumbnailSource.gates.clear()
        repo.requestThumbnails(ids,true);advanceUntilIdle()
        assertTrue(repo.snapshot.value.channels.all { it.thumbnailUri!=null })
    }

    @Test fun restartAndUnchangedSubscriptionKeepStillsWithoutRecapture() = runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("源",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val old=repo.snapshot.value.channels.single().thumbnailUri
        val restarted=h.newRepository()
        restarted.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertEquals(old,restarted.snapshot.value.channels.single().thumbnailUri)
        assertEquals(1,h.thumbnailSource.requested.size)
        h.importer.next=Outcome.Success(report(draft("A","https://p/a")))
        restarted.refreshSource(restarted.snapshot.value.sources.single().id)
        assertEquals(old,restarted.snapshot.value.channels.single().thumbnailUri)
        h.importer.next=Outcome.Success(report(draft("A","https://p/new")))
        restarted.refreshSource(restarted.snapshot.value.sources.single().id)
        assertNull(restarted.snapshot.value.channels.single().thumbnailUri)
        assertFalse(File(java.net.URI(old!!)).exists())
    }

    @Test fun failedCatalogWriteDoesNotDeletePersistedStills() = runTest {
        val h=harness();var reject=false
        val disk=FileCatalogStore(File(h.dir,"catalog.tsv"))
        val store=object:CatalogStore {
            override fun read()=disk.read()
            override fun write(catalog:StoredCatalog) {
                if(reject)throw java.io.IOException("disk full")
                disk.write(catalog)
            }
        }
        val repo=h.newRepository(store=store)
        repo.saveCustomChannel(null,draft("A","https://p/a"))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val old=repo.snapshot.value.channels.single().thumbnailUri!!
        reject=true
        assertTrue(repo.saveCustomChannel(id,draft("A","https://p/new")) is Outcome.Failure)
        assertTrue(repo.clearThumbnailCache() is Outcome.Failure)
        assertTrue(repo.deleteSource(CUSTOM_SOURCE_ID) is Outcome.Failure)
        assertTrue(repo.refreshThumbnails(listOf(id)) is Outcome.Failure)
        assertEquals(old,repo.snapshot.value.channels.single().thumbnailUri)
        assertTrue(File(java.net.URI(old)).exists())
        assertEquals(1,File(h.dir,"thumbnails").listFiles()!!.size)
    }

    @Test fun largeFailedGroupDoesNotLoopWhenCooldownIndexFillsUp() = runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(*(0..599).map { draft("$it","https://p/$it") }.toTypedArray()))
        val ids=repo.snapshot.value.channels.map { it.id }
        h.thumbnailSource.error=java.io.IOException("unreachable")
        repo.requestThumbnails(ids,true);advanceUntilIdle()
        assertEquals(600,h.thumbnailSource.requested.size)
        assertTrue(repo.snapshot.value.channels.all { it.thumbnailUri==null })
    }

    @Test fun forcedScreenshotRefreshReplacesUriButKeepsOldStillOnFailure() = runTest {
        val h=harness();val repo=h.newRepository()
        repo.saveCustomChannel(null,draft("A","https://example.com/a"))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val old=repo.snapshot.value.channels.single().thumbnailUri
        assertNotNull(old)
        assertTrue(repo.refreshThumbnails(listOf(id)) is Outcome.Success)
        val updated=repo.snapshot.value.channels.single().thumbnailUri
        assertNotEquals(old,updated)
        h.thumbnailSource.result=null
        assertTrue(repo.refreshThumbnails(listOf(id)) is Outcome.Failure)
        assertEquals(updated,repo.snapshot.value.channels.single().thumbnailUri)
    }
    @Test fun entireGroupOverQueueLimitIsCaptured() = runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("组",ImportRequest.Subscription("https://example.com/list"),
            com.tunvix.contract.ImportReport((0..65).map { draft("$it","https://example.com/$it") }))
        val ids=repo.snapshot.value.channels.map{it.id}
        repo.requestThumbnails(ids,true);advanceUntilIdle()
        assertEquals(ids.toSet(),h.thumbnailSource.requested.toSet())
        assertTrue(repo.snapshot.value.channels.all{it.thumbnailUri!=null})
    }

    @Test fun deletingCustomSourceRemovesRecordsAndCanBeRecreated() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.saveCustomChannel(null,draft("自建", "https://example.com/a"))
        val id=repo.snapshot.value.channels.single().id
        repo.setFavorite(id,true); repo.recordWatched(id)
        assertTrue(repo.deleteSource(CUSTOM_SOURCE_ID) is Outcome.Success)
        assertTrue(repo.snapshot.value.sources.isEmpty())
        assertTrue(repo.snapshot.value.favoriteIds.isEmpty())
        assertTrue(repo.snapshot.value.recentIds.isEmpty())
        assertTrue(h.newRepository().snapshot.value.sources.isEmpty())
        repo.saveCustomChannel(null,draft("重新创建", "https://example.com/b"))
        assertEquals(CUSTOM_SOURCE_ID,repo.snapshot.value.sources.single().id)
    }

    @Test
    fun freshInstallHasNoSourcesUntilUserCreatesOne() = runTest {
        val repo = harness().newRepository()
        val snapshot = repo.snapshot.value
        assertTrue(snapshot.sources.isEmpty())
        assertTrue(snapshot.channels.isEmpty())
        assertNull(snapshot.selectedSourceId)
        assertTrue(snapshot.favoriteIds.isEmpty())
        assertTrue(snapshot.recentIds.isEmpty())
        assertEquals(0L, snapshot.cacheBytes)
    }

    @Test
    fun committingAnImportCreatesOneSourceAndKeepsTheImportedOrder() = runTest {
        val h = harness()
        val repo = h.newRepository()
        val outcome = repo.commitImport(
            "供应商A",
            h.request(),
            report(draft("CCTV-1", "https://p/1.m3u8", "央视"), draft("CCTV-2", "https://p/2.m3u8", "央视")),
        )
        assertTrue(outcome is Outcome.Success)

        val snapshot = repo.snapshot.value
        assertEquals(1, snapshot.sources.size)
        val source = snapshot.sources.last()
        assertEquals("供应商A", source.name)
        assertEquals(SourceKind.SUBSCRIPTION, source.kind)
        assertEquals("https://provider.example/list.m3u", source.location)
        assertEquals(source.id, snapshot.selectedSourceId)

        val channels = snapshot.channels.filter { it.sourceId == source.id }
        assertEquals(listOf("CCTV-1", "CCTV-2"), channels.map { it.name })
        assertEquals(listOf("央视", "央视"), channels.map { it.group })
        assertEquals(2, channels.map { it.id }.distinct().size)
    }

    @Test
    fun importingAnAlreadyKnownSubscriptionIsRejectedAndReImportReplacesInsteadOfDuplicating() = runTest {
        val h = harness()
        val repo = h.newRepository()
        val original = report(draft("A", "https://p/a.m3u8"))
        assertTrue(repo.commitImport("A源", h.request(), original) is Outcome.Success)

        val duplicate = repo.commitImport("A源副本", h.request(), original)
        assertEquals("duplicate", failureCode(duplicate))
        assertEquals(1, repo.snapshot.value.sources.size)
        assertEquals(1, repo.snapshot.value.channels.size)

        val sourceId = repo.snapshot.value.sources.last().id
        val replaced = repo.commitImport(
            "A源",
            h.request(),
            report(draft("A", "https://p/a.m3u8"), draft("B", "https://p/b.m3u8")),
            replaceSourceId = sourceId,
        )
        assertTrue(replaced is Outcome.Success)
        val snapshot = repo.snapshot.value
        assertEquals(1, snapshot.sources.size)
        assertEquals(sourceId, snapshot.sources.last().id)
        assertEquals(2, snapshot.channels.count { it.sourceId == sourceId })
    }

    @Test
    fun failedRefreshLeavesTheWholeSnapshotUntouched() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8", "新闻")))
        val sourceId = repo.snapshot.value.sources.last().id
        val before = repo.snapshot.value

        h.importer.next = Outcome.Failure("连接超时", "timeout")
        val failed = repo.refreshSource(sourceId)
        assertEquals("timeout", failureCode(failed))
        assertEquals(before, repo.snapshot.value)

        h.importer.next = Outcome.Success(report(skipped = 12))
        assertEquals("empty", failureCode(repo.refreshSource(sourceId)))
        assertEquals(before, repo.snapshot.value)
        assertEquals(1, repo.snapshot.value.channels.size)
    }

    @Test
    fun successfulRefreshReplacesTheListAndKeepsIdentityOfMatchedChannels() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport(
            "A源",
            h.request(),
            report(
                draft("新闻1", "https://p/1.m3u8", "新闻", externalId = "1"),
                draft("新闻2", "https://p/2.m3u8", "新闻", externalId = "2"),
                draft("体育1", "https://p/3.m3u8", "体育", externalId = "3"),
            ),
        )
        val sourceId = repo.snapshot.value.sources.last().id
        val before = repo.snapshot.value.channels
        val news1 = before.first { it.name == "新闻1" }
        val news2 = before.first { it.name == "新闻2" }
        val sports1 = before.first { it.name == "体育1" }
        repo.setFavorite(news2.id, true)
        h.now += 1_000
        repo.recordWatched(sports1.id)

        h.importer.next = Outcome.Success(
            report(
                draft("新闻1", "https://p/1.m3u8?token=rotated&e=99", "新闻", externalId = "1"),
                draft("体育1 高清", "https://p/3.m3u8", "体育", externalId = "3"),
                draft("新频道", "https://p/9.m3u8", "新闻", externalId = "9"),
            ),
        )
        assertTrue(repo.refreshSource(sourceId) is Outcome.Success)

        val after = repo.snapshot.value
        val refreshed = after.channels.filter { it.sourceId == sourceId }
        assertEquals(listOf("新闻1", "体育1 高清", "新频道"), refreshed.map { it.name })
        assertEquals(news1.id, refreshed[0].id)
        assertEquals(sports1.id, refreshed[1].id)
        assertEquals("https://p/1.m3u8?token=rotated&e=99", refreshed[0].url)
        assertFalse(news2.id in after.channels.map { it.id })
        // the dropped channel takes its favorite with it, the surviving channel keeps its history
        assertTrue(after.favoriteIds.isEmpty())
        assertEquals(listOf(sports1.id), after.recentIds)
        assertEquals(sourceId, after.sources.first().id)
    }

    @Test
    fun identicalChannelNamesInTwoSourcesStayIndependent() = runTest {
        val h = harness()
        val repo = h.newRepository()
        val shared = draft("同名频道", "https://cdn/same.m3u8", "综合")
        repo.commitImport("甲源", h.request("https://a.example/list"), report(shared))
        repo.commitImport("乙源", h.request("https://b.example/list"), report(shared))

        val snapshot = repo.snapshot.value
        val sourceA = snapshot.sources.first { it.name == "甲源" }
        val sourceB = snapshot.sources.first { it.name == "乙源" }
        val channelA = snapshot.channels.single { it.sourceId == sourceA.id }
        val channelB = snapshot.channels.single { it.sourceId == sourceB.id }
        assertNotEquals(channelA.id, channelB.id)

        repo.setFavorite(channelA.id, true)
        assertEquals(setOf(channelA.id), repo.snapshot.value.favoriteIds)

        h.importer.next = Outcome.Success(report(shared))
        assertTrue(repo.refreshSource(sourceA.id) is Outcome.Success)
        val after = repo.snapshot.value
        assertEquals(channelA.id, after.channels.single { it.sourceId == sourceA.id }.id)
        assertEquals(channelB.id, after.channels.single { it.sourceId == sourceB.id }.id)
        assertEquals(setOf(channelA.id), after.favoriteIds)
    }

    @Test
    fun deletingASourceRemovesOnlyItsOwnDataAndNeverTheCustomEntry() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport(
            "甲源",
            h.request("https://a.example/list"),
            report(draft("A1", "https://a/1.m3u8"), draft("A2", "https://a/2.m3u8")),
        )
        repo.commitImport("乙源", h.request("https://b.example/list"), report(draft("B1", "https://b/1.m3u8")))

        val snapshot = repo.snapshot.value
        val sourceA = snapshot.sources.first { it.name == "甲源" }
        val sourceB = snapshot.sources.first { it.name == "乙源" }
        val a1 = snapshot.channels.first { it.name == "A1" }
        val a2 = snapshot.channels.first { it.name == "A2" }
        val b1 = snapshot.channels.first { it.name == "B1" }
        repo.setFavorite(a1.id, true)
        repo.setFavorite(b1.id, true)
        repo.recordWatched(a2.id)
        h.now += 1_000
        repo.recordWatched(b1.id)
        repo.selectSource(sourceA.id)
        repo.requestThumbnails(listOf(a1.id, b1.id), allowNetwork = true)
        advanceUntilIdle()
        assertTrue(h.thumbnailSource.requested.containsAll(listOf(a1.id, b1.id)))
        val cacheBefore = repo.snapshot.value.cacheBytes
        assertTrue(cacheBefore > 0)

        assertTrue(repo.deleteSource(sourceA.id) is Outcome.Success)
        val after = repo.snapshot.value
        assertEquals(listOf(sourceB.id), after.sources.map { it.id })
        assertEquals(listOf(b1.id), after.channels.map { it.id })
        assertEquals(setOf(b1.id), after.favoriteIds)
        assertEquals(listOf(b1.id), after.recentIds)
        assertEquals(sourceB.id, after.selectedSourceId)
        // only 甲源's still was removed; 乙源's is still cached
        assertEquals(b1.id, after.channels.single().id)
        assertTrue(after.cacheBytes < cacheBefore)
        assertFalse(File(h.dir, "thumbnails").listFiles().orEmpty().any { it.name.startsWith(a1.id) })
        assertTrue(File(h.dir, "thumbnails").listFiles().orEmpty().any { it.name.startsWith(b1.id) })

        assertEquals("missing", failureCode(repo.deleteSource(CUSTOM_SOURCE_ID)))
        assertEquals("missing", failureCode(repo.deleteSource("nope")))
    }

    @Test
    fun customChannelsAreValidatedEditedInPlaceAndDeleted() = runTest {
        val h = harness()
        val repo = h.newRepository()
        assertEquals("name", failureCode(repo.saveCustomChannel(null, draft("   ", "https://x/1.m3u8"))))
        assertEquals("url", failureCode(repo.saveCustomChannel(null, draft("频道", "ftp://x/1"))))
        assertEquals("url", failureCode(repo.saveCustomChannel(null, draft("频道", "not a url"))))

        assertTrue(repo.saveCustomChannel(null, draft("  我的频道  ", "https://x/1.m3u8", "  ")) is Outcome.Success)
        val created = repo.snapshot.value.channels.single()
        assertEquals("我的频道", created.name)
        assertEquals(DEFAULT_GROUP, created.group)
        assertEquals(CUSTOM_SOURCE_ID, created.sourceId)
        assertEquals(CUSTOM_SOURCE_ID, repo.snapshot.value.selectedSourceId)
        assertEquals(1, repo.snapshot.value.sources.count { it.kind == SourceKind.CUSTOM })

        assertTrue(repo.saveCustomChannel(created.id, draft("我的频道2", "https://x/2.m3u8", "体育")) is Outcome.Success)
        val edited = repo.snapshot.value.channels.single()
        assertEquals(created.id, edited.id)
        assertEquals("我的频道2", edited.name)
        assertEquals("体育", edited.group)
        assertEquals("https://x/2.m3u8", edited.url)

        assertTrue(repo.deleteCustomChannel(created.id) is Outcome.Success)
        assertTrue(repo.snapshot.value.channels.isEmpty())
        assertTrue(repo.snapshot.value.sources.any { it.id == CUSTOM_SOURCE_ID })
    }

    @Test
    fun importedChannelsCannotBeEditedOrDeletedThroughTheCustomChannelApi() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8")))
        val imported = repo.snapshot.value.channels.single()

        assertEquals("not_custom", failureCode(repo.saveCustomChannel(imported.id, draft("改名", "https://x/9"))))
        assertEquals("not_custom", failureCode(repo.deleteCustomChannel(imported.id)))
        assertEquals(
            "missing",
            failureCode(repo.commitImport("x", h.request(), report(draft("B", "https://p/b")), replaceSourceId = CUSTOM_SOURCE_ID)),
        )
        assertEquals("missing", failureCode(repo.refreshSource(CUSTOM_SOURCE_ID)))
        assertEquals(1, repo.snapshot.value.channels.size)
    }

    @Test
    fun recentsAreDeduplicatedNewestFirstAndCapped() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport(
            "A源",
            h.request(),
            report(*(1..105).map { draft("C$it", "https://p/$it.m3u8") }.toTypedArray()),
        )
        val ids = repo.snapshot.value.channels.map { it.id }
        ids.forEach { channelId ->
            h.now += 1_000
            assertTrue(repo.recordWatched(channelId) is Outcome.Success)
        }
        val recent = repo.snapshot.value.recentIds
        assertEquals(RECENT_LIMIT, recent.size)
        assertEquals(ids.last(), recent.first())
        assertEquals(ids.drop(5).reversed(), recent)

        h.now += 1_000
        repo.recordWatched(ids[10])
        assertEquals(ids[10], repo.snapshot.value.recentIds.first())
        assertEquals(RECENT_LIMIT, repo.snapshot.value.recentIds.size)

        assertTrue(repo.removeRecent(ids[10]) is Outcome.Success)
        assertFalse(ids[10] in repo.snapshot.value.recentIds)
        assertTrue(repo.clearRecents() is Outcome.Success)
        assertTrue(repo.snapshot.value.recentIds.isEmpty())
        assertEquals("missing", failureCode(repo.recordWatched("nope")))
        assertEquals("missing", failureCode(repo.setFavorite("nope", true)))
    }

    @Test
    fun thumbnailsHonourTheNetworkPermissionAndFailuresFallBackSilently() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport(
            "A源",
            h.request(),
            report(draft("A", "https://p/a.m3u8"), draft("B", "https://p/b.m3u8"), draft("C", "https://p/c.m3u8")),
        )
        val ids = repo.snapshot.value.channels.map { it.id }
        val a = ids[0]
        val c = ids[2]

        assertTrue(repo.requestThumbnails(ids, allowNetwork = false) is Outcome.Success)
        advanceUntilIdle()
        assertTrue(h.thumbnailSource.requested.isEmpty())
        assertTrue(repo.snapshot.value.channels.all { it.thumbnailUri == null })

        assertTrue(repo.requestThumbnails(listOf(a), allowNetwork = true) is Outcome.Success)
        advanceUntilIdle()
        assertEquals(listOf(a), h.thumbnailSource.requested.toList())
        val cached = repo.snapshot.value.channels.first { it.id == a }
        assertNotNull(cached.thumbnailUri)
        assertTrue("local file uri expected, was ${cached.thumbnailUri}", cached.thumbnailUri!!.startsWith("file://"))
        assertTrue(repo.snapshot.value.cacheBytes > 0)

        // an uncapturable channel must not surface as an error and must not be retried in a loop
        h.thumbnailSource.error = IllegalStateException("no frame available")
        assertTrue(repo.requestThumbnails(listOf(c), allowNetwork = true) is Outcome.Success)
        advanceUntilIdle()
        assertNull(repo.snapshot.value.channels.first { it.id == c }.thumbnailUri)
        val attempts = h.thumbnailSource.requested.size
        assertTrue(repo.requestThumbnails(listOf(c), allowNetwork = true) is Outcome.Success)
        advanceUntilIdle()
        assertEquals(attempts, h.thumbnailSource.requested.size)

        // clearing the cache never touches personal records
        assertTrue(repo.clearThumbnailCache() is Outcome.Success)
        val after = repo.snapshot.value
        assertEquals(0L, after.cacheBytes)
        assertTrue(after.channels.all { it.thumbnailUri == null })
        assertEquals(3, after.channels.size)
    }

    @Test
    fun switchingGroupsCancelsOnlyUnneededInFlightWork() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8"), draft("B", "https://p/b.m3u8")))
        val ids = repo.snapshot.value.channels.map { it.id }
        h.thumbnailSource.gates[ids[0]] = CompletableDeferred()

        assertTrue(repo.requestThumbnails(ids, allowNetwork = true) is Outcome.Success)
        // runCurrent, not advanceUntilIdle: advancing time would fire the extraction timeout itself.
        runCurrent()
        // The second channel progresses while the first is waiting.
        assertEquals(ids, h.thumbnailSource.requested.toList())
        assertTrue(h.thumbnailSource.cancelledIds.isEmpty())

        // Scrolling the first channel away cancels the in-flight work instead of letting it run on.
        assertTrue(repo.requestThumbnails(listOf(ids[1]), allowNetwork = true) is Outcome.Success)
        advanceUntilIdle()
        assertTrue(h.thumbnailSource.cancelledIds.contains(ids[0]))
        assertNotNull(repo.snapshot.value.channels.first { it.id == ids[1] }.thumbnailUri)
    }

    @Test
    fun aRefreshThatLosesItsRaceWithAnAddressChangeIsDiscarded() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("甲源", h.request("https://a.example/list"), report(draft("A1", "https://a/1.m3u8")))
        val sourceId = repo.snapshot.value.sources.last().id

        val gate = h.importer.enqueueGated(Outcome.Success(report(draft("旧A1", "https://a/old.m3u8"))))
        val refresh = async { repo.refreshSource(sourceId) }
        runCurrent()

        // The user points the source at another subscription while the first refresh is still in flight.
        assertTrue(repo.updateSource(sourceId, "甲源", "https://b.example/list") is Outcome.Success)
        gate.complete(Unit)

        assertEquals("stale", failureCode(refresh.await()))
        val after = repo.snapshot.value
        assertEquals("https://b.example/list", after.sources.first { it.id == sourceId }.location)
        assertEquals(listOf("A1"), after.channels.filter { it.sourceId == sourceId }.map { it.name })
    }

    @Test
    fun anOlderRefreshNeverOverwritesTheResultOfANewerOne() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("甲源", h.request(), report(draft("A1", "https://a/1.m3u8")))
        val sourceId = repo.snapshot.value.sources.last().id

        val older = h.importer.enqueueGated(Outcome.Success(report(draft("旧结果", "https://a/old.m3u8"))))
        val newer = h.importer.enqueueGated(Outcome.Success(report(draft("新结果", "https://a/new.m3u8"))))

        val first = async { repo.refreshSource(sourceId) }
        runCurrent()
        val second = async { repo.refreshSource(sourceId) }
        runCurrent()

        // The newer refresh finishes first; the older one completes last and must be dropped.
        newer.complete(Unit)
        assertTrue(second.await() is Outcome.Success)
        older.complete(Unit)
        assertEquals("stale", failureCode(first.await()))

        assertEquals(listOf("新结果"), repo.snapshot.value.channels.filter { it.sourceId == sourceId }.map { it.name })
    }

    @Test
    fun aFailedSaveIsReportedAndLeavesBothTheSnapshotAndTheFileUntouched() = runTest {
        val h = harness()
        var failing = false
        val store = FileCatalogStore(File(h.dir, "catalog.tsv"), mover = { from, to -> !failing && from.renameTo(to) })
        val repo = h.newRepository(store = store)
        assertTrue(repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8"))) is Outcome.Success)
        val before = repo.snapshot.value

        failing = true
        val id = before.channels.single().id
        assertEquals("storage", failureCode(repo.setFavorite(id, true)))
        assertEquals(before, repo.snapshot.value)
        assertTrue(repo.snapshot.value.favoriteIds.isEmpty())

        // A failed save must not have damaged what is on disk either.
        failing = false
        assertEquals(before, TestHarness(this, h.dir).newRepository().snapshot.value)
    }

    @Test
    fun aStillThatFinishesAfterItsChannelWasRefreshedIsNotAttachedToTheNewStream() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8?token=aaa", externalId = "1")))
        val sourceId = repo.snapshot.value.sources.last().id
        val id = repo.snapshot.value.channels.single().id
        val gate = CompletableDeferred<Unit>()
        h.thumbnailSource.gates[id] = gate

        assertTrue(repo.requestThumbnails(listOf(id), allowNetwork = true) is Outcome.Success)
        runCurrent()
        assertEquals(listOf(id), h.thumbnailSource.requested.toList())

        // The provider rotates the token while the frame grab is still running.
        h.importer.next = Outcome.Success(report(draft("A", "https://p/a.m3u8?token=bbb", externalId = "1")))
        assertTrue(repo.refreshSource(sourceId) is Outcome.Success)
        assertNull(repo.snapshot.value.channels.single().thumbnailUri)

        gate.complete(Unit)
        advanceUntilIdle()

        // The still belongs to the previous stream, so it must not be attached to the refreshed channel.
        assertNull(repo.snapshot.value.channels.single().thumbnailUri)
        assertEquals(0L, repo.snapshot.value.cacheBytes)
        assertTrue(File(h.dir, "thumbnails").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theProductionPathReadsTheCatalogOffTheCallingThread() = runTest {
        val h = harness()
        h.newRepository().commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8")))
        val persisted = TestHarness(this, h.dir).newRepository().snapshot.value
        assertTrue(persisted.channels.isNotEmpty())

        val repo = TestHarness(this, h.dir).newRepository(asyncInit = true)
        assertFalse(repo.ready.value)
        // Until the background read finishes the snapshot is the empty fresh install, never a lie.
        assertTrue(repo.snapshot.value.sources.isEmpty())
        assertTrue(repo.snapshot.value.channels.isEmpty())

        repo.awaitReady()
        assertTrue(repo.ready.value)
        assertEquals(persisted, repo.snapshot.value)
    }

    @Test
    fun aMutationArrivingBeforeTheBackgroundReadFinishesWaitsInsteadOfOverwritingTheCatalog() = runTest {
        val h = harness()
        val seeding = h.newRepository()
        seeding.commitImport("A源", h.request(), report(draft("A", "https://p/a.m3u8")))
        val id = seeding.snapshot.value.channels.single().id

        val repo = TestHarness(this, h.dir).newRepository(asyncInit = true)
        // It must not persist a catalog built from the not-yet-read (empty) state.
        assertTrue(repo.setFavorite(id, true) is Outcome.Success)

        val after = repo.snapshot.value
        assertEquals(listOf(id), after.channels.map { it.id })
        assertEquals(setOf(id), after.favoriteIds)
        assertEquals(setOf(id), TestHarness(this, h.dir).newRepository().snapshot.value.favoriteIds)
    }

    @Test
    fun localFileRefreshWithoutPermissionFailsBeforeReachingTheImporter() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("本地", h.localRequest(), report(draft("L1", "https://l/1.m3u8")))
        val sourceId = repo.snapshot.value.sources.last().id

        h.granted = false
        assertEquals("uri_permission", failureCode(repo.refreshSource(sourceId)))
        assertEquals(0, h.importer.calls)
        assertEquals(1, repo.snapshot.value.channels.size)

        h.granted = true
        h.importer.next = Outcome.Success(report(draft("L1", "https://l/1.m3u8")))
        assertTrue(repo.refreshSource(sourceId) is Outcome.Success)
        assertEquals(ImportRequest.LocalFile("content://downloads/list.m3u"), h.importer.lastRequest)
    }

    @Test
    fun sourceEditingValidatesNamesAndRejectsDuplicateLocations() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.commitImport("甲源", h.request("https://a.example/list"), report(draft("A", "https://a/1")))
        repo.commitImport("乙源", h.request("https://b.example/list"), report(draft("B", "https://b/1")))
        val sourceA = repo.snapshot.value.sources.first { it.name == "甲源" }

        assertEquals("name", failureCode(repo.updateSource(sourceA.id, "   ", "https://a.example/list")))
        assertEquals("duplicate", failureCode(repo.updateSource(sourceA.id, "甲源", "https://b.example/list")))
        assertEquals("location", failureCode(repo.updateSource(sourceA.id, "甲源", "   ")))
        assertTrue(repo.updateSource(sourceA.id, "甲源改名", "https://a2.example/list") is Outcome.Success)
        assertEquals("甲源改名", repo.snapshot.value.sources.first { it.id == sourceA.id }.name)
        assertEquals("https://a2.example/list", repo.snapshot.value.sources.first { it.id == sourceA.id }.location)

        repo.saveCustomChannel(null,draft("手动", "https://example.com/manual"))
        assertTrue(repo.updateSource(CUSTOM_SOURCE_ID, "自建", "https://ignored.example") is Outcome.Success)
        val custom = repo.snapshot.value.sources.first { it.id == CUSTOM_SOURCE_ID }
        assertEquals("自建", custom.name)
        assertNull(custom.location)

        assertEquals("missing", failureCode(repo.selectSource("nope")))
        assertEquals("missing", failureCode(repo.updateSource("nope", "x", "https://x/1")))
    }

    @Test
    fun customChannelOrderIsStableWhenEditing() = runTest {
        val h = harness()
        val repo = h.newRepository()
        repo.saveCustomChannel(null, draft("一", "https://x/1"))
        repo.saveCustomChannel(null, draft("二", "https://x/2"))
        repo.saveCustomChannel(null, draft("三", "https://x/3"))
        val second = repo.snapshot.value.channels.first { it.name == "二" }

        repo.saveCustomChannel(second.id, draft("二改名", "https://x/2"))

        assertEquals(listOf("一", "二改名", "三"), repo.snapshot.value.channels.map { it.name })
    }
    @Test fun failedInitializationBlocksWritesUntilSuccessfulReload() = runTest {
        val h = harness(); var writes = 0
        val unreadable = object : CatalogStore {
            override fun read(): StoredCatalog = throw java.io.IOException("unreadable")
            override fun write(catalog: StoredCatalog) { writes++ }
        }
        val repo = h.newRepository(asyncInit = true, store = unreadable)
        val result = repo.updateSettings(com.tunvix.contract.UserSettings(hapticsEnabled = false))
        assertEquals("storage", failureCode(result))
        assertEquals(0, writes)
    }

    @Test fun cacheDirectoryInitializationDoesNotRunInConstructor() = runTest {
        val h = harness(); val cache = File(h.dir, "thumbnails")
        val repo = h.newRepository(asyncInit = true)
        assertFalse("Cache IO must wait for the background initializer", cache.exists())
        repo.awaitReady()
        assertTrue(cache.isDirectory)
    }

    @Test fun customHeaderChangeInvalidatesCachedStill() = runTest {
        val h = harness(); val repo = h.newRepository()
        repo.saveCustomChannel(null, draft("C", "https://p/live", headers = mapOf("Referer" to "https://a")))
        val id = repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id), true); advanceUntilIdle()
        assertNotNull(repo.snapshot.value.channels.single().thumbnailUri)
        repo.saveCustomChannel(id, draft("C", "https://p/live", headers = mapOf("Referer" to "https://b")))
        assertNull(repo.snapshot.value.channels.single().thumbnailUri)
    }

    @Test fun addressChangeDuringUriProbeCannotBeOverwrittenByOldRefresh() = runTest {
        val h = harness(); lateinit var repo: LocalCatalogRepository
        var modify = false; var sourceId = ""
        repo = LocalCatalogRepository(
            store = FileCatalogStore(File(h.dir, "catalog.tsv")), importer = h.importer,
            thumbnails = ThumbnailStore(File(h.dir, "thumbnails")), thumbnailSource = h.thumbnailSource,
            uriAccess = object : UriAccess {
                override fun canRead(uri: String): Boolean {
                    if (modify) kotlinx.coroutines.runBlocking {
                        repo.updateSource(sourceId, "local", "content://downloads/new")
                    }
                    return true
                }
            }, io = kotlinx.coroutines.Dispatchers.Unconfined, scope = this,
        )
        repo.commitImport("local", h.localRequest(), report(draft("old", "https://p/old")))
        sourceId = repo.snapshot.value.sources.last().id; modify = true
        h.importer.next = Outcome.Success(report(draft("stale", "https://p/stale")))
        assertEquals("stale", failureCode(repo.refreshSource(sourceId)))
        assertEquals("content://downloads/new", repo.snapshot.value.sources.last().location)
        assertEquals("old", repo.snapshot.value.channels.single().name)
    }

}
