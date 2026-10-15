package com.tunvix.backend.catalog

/**
 * Answers "may the data layer still read this URI?".
 *
 * Persistent access rights are requested by the UI layer when the user picks a file; the data layer
 * only remembers the URI and has to cope with the grant disappearing later (user revoked it, app data
 * restored, SD card removed).
 */
internal interface UriAccess {
    fun canRead(uri: String): Boolean

    object AlwaysGranted : UriAccess {
        override fun canRead(uri: String): Boolean = true
    }
}
