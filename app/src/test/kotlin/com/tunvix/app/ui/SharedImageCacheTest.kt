@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.tunvix.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SharedImageCacheTest {
    @Test fun scrollingBackUsesCachedImageAndConcurrentRowsShareDecode() = runTest {
        val cache=SharedImageCache<String>(12,this){it.length}
        val gate=CompletableDeferred<Unit>();var decodes=0
        val first=async { cache.load("uri") { decodes++;gate.await();"image" } }
        val second=async { cache.load("uri") { decodes++;"wrong" } }
        runCurrent();assertEquals(1,decodes)
        first.cancel();gate.complete(Unit)
        assertEquals("image",second.await());assertEquals("image",cache.peek("uri"))
        assertEquals("image",cache.load("uri") { decodes++;"wrong" });assertEquals(1,decodes)
    }

    @Test fun evictionOnlyReleasesMemoryAndNewUriHasIndependentImage() = runTest {
        val cache=SharedImageCache<String>(6,this){it.length}
        assertEquals("old",cache.load("old-uri"){"old"})
        assertEquals("two",cache.load("other"){"two"})
        assertEquals("old",cache.peek("old-uri")) // Protect most recently used image.
        assertEquals("new",cache.load("new-uri"){"new"})
        assertNull(cache.peek("other"));assertEquals("old",cache.peek("old-uri"))
        assertEquals("new",cache.peek("new-uri"))
        assertEquals("two",cache.load("other"){"two"})
    }
}
