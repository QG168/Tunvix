@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.tunvix.backend.catalog

import com.tunvix.contract.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

class BrowsePersistenceTest {
    @Test fun themesAndPerSourceGroupsSurviveRestartAndSourceDeletion()=runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("甲",h.request(),report(draft("体育","https://p/a","体育"),draft("电影","https://p/b","电影")))
        val a=repo.snapshot.value.selectedSourceId!!
        repo.rememberBrowseGroup(a,"电影")
        repo.commitImport("乙",h.request("https://p/list2"),report(draft("新闻","https://p/c","新闻")))
        val b=repo.snapshot.value.selectedSourceId!!
        repo.rememberBrowseGroup(b,"新闻");repo.setThemeMode(ThemeMode.DARK)
        val restarted=h.newRepository()
        assertEquals(mapOf(a to "电影",b to "新闻"),restarted.snapshot.value.settings.browseGroups)
        assertEquals(ThemeMode.DARK,restarted.snapshot.value.settings.themeMode)
        assertTrue(restarted.rememberBrowseGroup(a,"不存在") is Outcome.Failure)
        restarted.deleteSource(a)
        assertEquals(mapOf(b to "新闻"),h.newRepository().snapshot.value.settings.browseGroups)
    }
    @Test fun playbackStillPersistsWithoutExtractionAndRejectsChangedMedia()=runTest {
        val h=harness();val repo=h.newRepository()
        repo.saveCustomChannel(null,draft("A","https://p/a"))
        val channel=repo.snapshot.value.channels.single()
        assertTrue(repo.savePlaybackThumbnail(channel,"playback".toByteArray()) is Outcome.Success)
        val image=repo.snapshot.value.channels.single().thumbnailUri!!
        assertEquals("playback",File(URI(image)).readText())
        assertTrue(h.thumbnailSource.requested.isEmpty())
        assertEquals(image,h.newRepository().snapshot.value.channels.single().thumbnailUri)
        repo.savePlaybackThumbnail(channel,"replacement".toByteArray())
        assertEquals("playback",File(URI(image)).readText())
        repo.saveCustomChannel(channel.id,draft("A","https://p/new"))
        assertTrue(repo.savePlaybackThumbnail(channel,"stale".toByteArray()) is Outcome.Failure)
        assertNull(repo.snapshot.value.channels.single().thumbnailUri)
        assertEquals(0,File(h.dir,"thumbnails").listFiles()!!.size)
    }
    @Test fun failedPlaybackStillCommitKeepsCatalogAndDoesNotLeakImage()=runTest {
        val h=harness();var reject=false
        val underlying=FileCatalogStore(File(h.dir,"catalog.tsv"))
        val repo=h.newRepository(store=object:CatalogStore {
            override fun read()=underlying.read()
            override fun write(catalog:StoredCatalog) { if(reject)throw CatalogWriteException("full");underlying.write(catalog) }
        })
        repo.saveCustomChannel(null,draft("A","https://p/a"));val channel=repo.snapshot.value.channels.single();reject=true
        assertTrue(repo.savePlaybackThumbnail(channel,"still".toByteArray()) is Outcome.Failure)
        assertNull(repo.snapshot.value.channels.single().thumbnailUri)
        assertEquals(0,File(h.dir,"thumbnails").listFiles()!!.size)
    }
    @Test fun scrollCancelsOldWorkAndCancelledChannelsCanBeRequestedAgain()=runTest {
        val h=harness();val repo=h.newRepository()
        repo.commitImport("甲",h.request(),report(*(0..25).map { draft("$it","https://p/$it") }.toTypedArray()))
        val ids=repo.snapshot.value.channels.map { it.id }
        ids.take(6).forEach { h.thumbnailSource.gates[it]=CompletableDeferred() }
        repo.requestThumbnails(ids.take(6),true);runCurrent()
        assertEquals(ids.take(3).toSet(),repo.snapshot.value.capturingIds)
        repo.requestThumbnails(ids.drop(20).take(6),true);advanceUntilIdle()
        assertEquals(ids.take(3).toSet(),h.thumbnailSource.cancelledIds.toSet())
        assertTrue(ids.drop(20).all { id -> repo.snapshot.value.channels.first { it.id==id }.thumbnailUri!=null })
        assertTrue(repo.snapshot.value.capturingIds.isEmpty())
        h.thumbnailSource.gates.clear();repo.requestThumbnails(ids.take(6),true);advanceUntilIdle()
        assertTrue(repo.snapshot.value.channels.take(6).all { it.thumbnailUri!=null })
        assertTrue(h.thumbnailSource.peak.get()<=3)
    }
}
