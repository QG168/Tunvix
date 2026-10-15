package com.tunvix.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tunvix.backend.catalog.LocalCatalogRepository
import com.tunvix.backend.importer.AndroidSourceImporter
import com.tunvix.contract.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class BackendDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun isolatedContext(): Pair<Context, File> {
        val root = File(instrument.targetContext.cacheDir, "device-test-${UUID.randomUUID()}")
        val context = object : ContextWrapper(instrument.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        return context to root
    }
    @Test fun realSubscriptionPersistsAndFailedRefreshRetainsData() = runBlocking {
        val (context, root) = isolatedContext()
        val server = FixtureServer(instrument.context.assets.open("test-video.mp4").use { it.readBytes() })
        val importer = AndroidSourceImporter(context)
        val repo = LocalCatalogRepository(context, importer)
        try {
            repo.awaitReady(); assertTrue(repo.snapshot.value.channels.isEmpty())
            val request = ImportRequest.Subscription("${server.base}/list.m3u")
            val result = importer.inspect(request)
            assertTrue(result.toString(), result is Outcome.Success)
            val report = (result as Outcome.Success).value
            assertEquals(1, report.channels.size)
            assertEquals("${server.base}/ref", report.channels.single().headers["Referer"])
            assertTrue(repo.commitImport("device-test", request, report) is Outcome.Success)
            val channel = repo.snapshot.value.channels.single()
            repo.setFavorite(channel.id, true); repo.recordWatched(channel.id)
            val before = repo.snapshot.value
            server.failPlaylist = true
            assertTrue(repo.refreshSource(channel.sourceId) is Outcome.Failure)
            assertEquals(before, repo.snapshot.value)
            val restarted = LocalCatalogRepository(context, importer)
            try {
                restarted.awaitReady(); assertEquals(before, restarted.snapshot.value)
                restarted.deleteSource(channel.sourceId)
                assertTrue(restarted.snapshot.value.channels.isEmpty())
                assertTrue(restarted.snapshot.value.favoriteIds.isEmpty())
                assertTrue(restarted.snapshot.value.recentIds.isEmpty())
            } finally { restarted.close() }
        } finally { repo.close(); server.close(); root.deleteRecursively() }
    }
    @Test fun actualContentResolverReadsTxtFile() = runBlocking {
        val testContext = instrument.context
        val uri = "content://${testContext.packageName}.fixtures/playlist.txt"
        val result = AndroidSourceImporter(instrument.targetContext).inspect(ImportRequest.LocalFile(uri))
        assertTrue(result.toString(), result is Outcome.Success)
        val channel = (result as Outcome.Success).value.channels.single()
        assertEquals("测试组", channel.group)
        assertEquals("https://example.com/live?token=a&b=2", channel.url)
    }
    @Test fun liveFactoryUsesRealServicesAndEmptyInitialData() = runBlocking {
        val (context, root) = isolatedContext()
        lateinit var services: com.tunvix.app.services.AppServices
        instrument.runOnMainSync { services = ServiceFactory.create(context) }
        try {
            withTimeout(10000) { (services.catalog as LocalCatalogRepository).awaitReady() }
            assertTrue(services.backendReady); assertFalse(services.demo)
            assertTrue(services.catalog.snapshot.value.channels.isEmpty())
            assertTrue(services.catalog.snapshot.value.sources.isEmpty())
        } finally {
            instrument.runOnMainSync { services.playback.release(); services.closeCatalog() }
            root.deleteRecursively()
        }
    }
}
