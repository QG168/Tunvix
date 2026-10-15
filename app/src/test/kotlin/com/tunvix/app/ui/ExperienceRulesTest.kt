package com.tunvix.app.ui

import com.tunvix.contract.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceRulesTest {
    private val channels=List(50) { Channel("c$it","s","频道$it","https://p/$it",if(it<25)"体育" else "电影") }
    @Test fun visibleWindowStartsWhereUserScrolledAndPrefetchesOneScreen() {
        assertEquals((30..41).map { "c$it" },UiRules.thumbnailWindow(channels,(30..35).toList()))
        assertEquals(listOf("c48","c49"),UiRules.thumbnailWindow(channels,listOf(49,48,49)))
        assertEquals(emptyList<String>(),UiRules.thumbnailWindow(channels,emptyList()))
        assertEquals(emptyList<String>(),UiRules.thumbnailWindow(channels,listOf(-1,80)))
    }
    @Test fun groupRestorationUsesSourceAndFallsBackWhenGroupDisappears() {
        val snapshot=CatalogSnapshot(channels=channels,selectedSourceId="s",settings=UserSettings(browseGroups=mapOf("s" to "电影","other" to "新闻")))
        assertEquals("电影",UiRules.restoredGroup(snapshot))
        assertEquals("体育",UiRules.restoredGroup(snapshot.copy(channels=channels.take(25))))
        assertEquals("未分组",UiRules.restoredGroup(snapshot.copy(channels=emptyList())))
    }
    @Test fun appearanceFollowsSystemOnlyInSystemMode() {
        assertTrue(UiRules.darkAppearance(ThemeMode.SYSTEM,true))
        assertFalse(UiRules.darkAppearance(ThemeMode.SYSTEM,false))
        assertTrue(UiRules.darkAppearance(ThemeMode.DARK,false))
        assertFalse(UiRules.darkAppearance(ThemeMode.LIGHT,true))
    }
    @Test fun bothPalettesKeepTextContrastAndDistinctSurfaces() {
        for(dark in listOf(false,true)) {
            val p=DesignPalette(dark)
            assertNotEquals(p.background,p.surface)
            assertTrue(contrast(p.ink,p.surface)>=7.0)
            assertTrue(contrast(p.muted,p.surface)>=4.5)
            assertTrue(contrast(p.onPrimary,p.primary)>=4.5)
            assertTrue(contrast(p.primary,p.primaryLight)>=4.5)
        }
    }
    private fun contrast(a:androidx.compose.ui.graphics.Color,b:androidx.compose.ui.graphics.Color):Double {
        fun luminance(c:androidx.compose.ui.graphics.Color):Double {
            fun linear(v:Float)=if(v<=.04045f)v/12.92 else Math.pow((v+.055)/1.055,2.4)
            return .2126*linear(c.red)+.7152*linear(c.green)+.0722*linear(c.blue)
        }
        val x=luminance(a);val y=luminance(b)
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
}
