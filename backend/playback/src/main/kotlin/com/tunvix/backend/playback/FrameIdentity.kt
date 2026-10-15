package com.tunvix.backend.playback

/** The renderer's originating media ID, never the current item's mutable ID. */
internal fun renderedPrepareId(mediaId: String, activePrepareId: Int): Int? =
    mediaId.toIntOrNull()?.takeIf { it == activePrepareId }
