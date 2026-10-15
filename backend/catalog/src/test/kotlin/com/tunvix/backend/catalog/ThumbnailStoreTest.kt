package com.tunvix.backend.catalog

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ThumbnailStoreTest {
    @Test fun referencedStillsSurviveOldSizeLimitAndRestart() {
        val directory=File(tempDir(),"thumbnails")
        val store=ThumbnailStore(directory);store.initialize()
        val block=ByteArray(1024*1024)
        repeat(25) { store.write("$it.jpg",block) }
        assertEquals(25L*1024*1024,store.sizeBytes())
        val restarted=ThumbnailStore(directory);restarted.initialize()
        repeat(25) { assertNotNull(restarted.uriOf("$it.jpg")) }
        restarted.retain(setOf("0.jpg"))
        assertNotNull(restarted.uriOf("0.jpg"));assertNull(restarted.uriOf("1.jpg"))
        restarted.clear();assertEquals(0L,restarted.sizeBytes())
    }

    @Test fun failedMigrationPreservesReadableOldStillAndRetriesNextStartup() {
        val root=tempDir();val legacy=File(root,"legacy").apply{mkdirs()}
        File(legacy,"channel.jpg").writeText("old")
        val blocked=File(root,"files").apply{writeText("not a directory")}
        val store=ThumbnailStore(File(blocked,"thumbnails"),legacyDirectory=legacy)
        store.initialize()
        assertEquals("old",File(java.net.URI(store.uriOf("channel.jpg")!!)).readText())
        assertTrue(File(legacy,"channel.jpg").exists())
        blocked.delete();store.initialize()
        assertEquals("old",File(blocked,"thumbnails/channel.jpg").readText())
        assertFalse(File(legacy,"channel.jpg").exists())
    }

    @Test fun migrationPreservesNamesAndDataWithoutRecapturing() {
        val root=tempDir();val legacy=File(root,"cache/thumbnails").apply{mkdirs()}
        File(legacy,"channel.jpg").writeBytes("old image".toByteArray())
        val destination=File(root,"files/thumbnails")
        val store=ThumbnailStore(destination,legacyDirectory=legacy);store.initialize()
        assertEquals("old image",File(destination,"channel.jpg").readText())
        assertNotNull(store.uriOf("channel.jpg"));assertFalse(File(legacy,"channel.jpg").exists())
        // Migration is repeatable and keeps an already persisted new copy.
        File(legacy,"channel.jpg").writeText("stale duplicate")
        store.initialize();assertEquals("old image",File(destination,"channel.jpg").readText())
    }
}
