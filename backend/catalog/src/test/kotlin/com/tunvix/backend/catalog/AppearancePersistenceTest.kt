package com.tunvix.backend.catalog

import org.junit.Test
import org.junit.Assert.*

class AppearancePersistenceTest {
    @Test fun appendedThemeSurvivesCatalogRoundTrip() {
        val text = "tunvix-catalog\t1\nmeta\tsettings\t1\t1\t0\tDARK\n"
        val encoded = CatalogCodec.encode(CatalogCodec.decode(text)!!)
        assertTrue(encoded.contains("meta\tsettings\t1\t1\t0\tDARK"))
    }
    @Test fun legacyAndUnknownThemeDefaultToFollowingSystem() {
        for(extra in listOf("", "\tUNKNOWN")) {
            val settings=CatalogCodec.decode("tunvix-catalog\t1\nmeta\tsettings\t1\t1\t0$extra\n")!!.settings
            assertEquals(com.tunvix.contract.ThemeMode.SYSTEM,settings.themeMode)
            assertTrue(settings.browseGroups.isEmpty())
        }
    }
}
