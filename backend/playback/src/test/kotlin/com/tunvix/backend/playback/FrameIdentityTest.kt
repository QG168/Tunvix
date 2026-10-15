package com.tunvix.backend.playback

import org.junit.Assert.*
import org.junit.Test

class FrameIdentityTest {
    @Test fun queuedFrameFromOlderMediaCannotMarkCurrentPrepareReady() {
        assertNull(renderedPrepareId("7",8))
        assertNull(renderedPrepareId("",8))
        assertNull(renderedPrepareId("unknown",8))
        assertEquals(8,renderedPrepareId("8",8))
    }
}
