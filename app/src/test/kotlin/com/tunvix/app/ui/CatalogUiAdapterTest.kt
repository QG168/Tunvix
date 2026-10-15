package com.tunvix.app.ui
import com.tunvix.app.services.MemoryCatalog
import com.tunvix.contract.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class CatalogUiAdapterTest {
    @Test fun emptyAdapterDoesNotSeedLiveChannels() {
        val state=MemoryCatalog().snapshot.value
        assertTrue(state.channels.isEmpty());assertTrue(state.favoriteIds.isEmpty());assertTrue(state.recentIds.isEmpty())
    }
    @Test fun deletingSourceRemovesOnlyItsAssociatedRecords()=runTest {
        val a=Channel("a","source-a","同名","https://example.com/a")
        val b=Channel("b","source-b","同名","https://example.com/b")
        val repo=MemoryCatalog(CatalogSnapshot(listOf(LiveSource("source-a","A",SourceKind.SUBSCRIPTION),LiveSource("source-b","B",SourceKind.LOCAL_FILE)),listOf(a,b),"source-a",setOf("a","b"),listOf("a","b")))
        repo.deleteSource("source-a")
        assertEquals(listOf(b),repo.snapshot.value.channels);assertEquals(setOf("b"),repo.snapshot.value.favoriteIds);assertEquals(listOf("b"),repo.snapshot.value.recentIds);assertEquals("source-b",repo.snapshot.value.selectedSourceId)
    }
    @Test fun customSourceCanBeDeleted()=runTest {
        val source=LiveSource("custom","自建频道",SourceKind.CUSTOM)
        val repo=MemoryCatalog(CatalogSnapshot(sources=listOf(source)))
        assertTrue(repo.deleteSource("custom") is Outcome.Success);assertTrue(repo.snapshot.value.sources.isEmpty())
    }
    @Test fun editingSubscriptionChannelAsCustomIsRejected()=runTest {
        val a=Channel("a","online","频道","https://example.com/a")
        val repo=MemoryCatalog(CatalogSnapshot(channels=listOf(a)))
        assertTrue(repo.saveCustomChannel("a",ChannelDraft("修改","https://example.com/new")) is Outcome.Failure)
        assertEquals(listOf(a),repo.snapshot.value.channels)
    }
}
