package com.tunvix.app

import android.graphics.BitmapFactory
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tunvix.app.ui.TunvixModel
import com.tunvix.app.ui.Page
import com.tunvix.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

class PlayerExperienceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun playbackFillsMissingStillAndThemeChangesKeepActivityAndSession() {
        lateinit var model:TunvixModel
        compose.runOnIdle { model=ViewModelProvider(compose.activity)[TunvixModel::class.java] }
        compose.waitUntil(15000) { model.catalogReady }
        val context=InstrumentationRegistry.getInstrumentation().context
        FixtureServer(context.assets.open("test-video.mp4").use { it.readBytes() }).use { server ->
            val name="播放截图验收-${UUID.randomUUID()}"
            compose.runOnIdle { model.tab(Page.MANAGEMENT) }
            runBlocking {
                assertTrue(model.services.catalog.commitImport(name,ImportRequest.Subscription("${server.base}/unique-list"),
                    ImportReport(listOf(ChannelDraft("截图测试频道","${server.base}/stream","截图测试",headers=mapOf("Referer" to "${server.base}/ref")))),null) is Outcome.Success)
            }
            compose.waitUntil(10000) { model.catalog.sources.any { it.name==name } }
            val source=model.catalog.sources.first { it.name==name }
            val channel=model.catalog.channels.first { it.sourceId==source.id }
            try {
                assertNull(channel.thumbnailUri)
                compose.runOnIdle { model.play(channel,true) }
                compose.waitUntil(15000) { model.playback.videoFrameReady || model.playback.phase==PlaybackPhase.FAILED }
                if(model.playback.phase==PlaybackPhase.FAILED && (android.os.Build.HARDWARE=="ranchu" || android.os.Build.HARDWARE=="goldfish")) {
                    // Some emulator decoders reject ExoPlayer's initial placeholder surface.
                    // Retry only after the real video SurfaceView has been laid out.
                    fun findSurface(view:android.view.View):android.view.SurfaceView? {
                        if(view is android.view.SurfaceView)return view
                        if(view is android.view.ViewGroup)for(i in 0 until view.childCount) {
                            findSurface(view.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    compose.waitUntil(5000) { findSurface(compose.activity.window.decorView)?.holder?.surface?.isValid==true }
                    compose.runOnIdle { model.services.playback.retry() }
                }
                compose.waitUntil(20000) { model.playback.videoFrameReady && model.playback.phase==PlaybackPhase.PLAYING }
                compose.waitUntil(10000) { model.catalog.channels.first { it.id==channel.id }.thumbnailUri!=null }
                val uri=model.catalog.channels.first { it.id==channel.id }.thumbnailUri!!
                assertTrue(uri,uri.contains("-play-"))
                val bitmap=BitmapFactory.decodeFile(File(URI(uri)).absolutePath)
                assertNotNull(bitmap)
                assertTrue(bitmap.width<=320 && bitmap.height<=180)
                val colors=(0..4).flatMap { x -> (0..4).map { y -> bitmap.getPixel(x*(bitmap.width-1)/4,y*(bitmap.height-1)/4) } }.toSet()
                assertTrue("Copied video must contain the fixture's visible picture",colors.size>2)
                bitmap.recycle()
                val activity=compose.activity
                val session=model.playSession
                runBlocking { model.services.catalog.setThemeMode(ThemeMode.DARK) }
                compose.waitUntil(5000) { model.catalog.settings.themeMode==ThemeMode.DARK }
                assertSame(activity,compose.activity)
                assertEquals(session,model.playSession)
                val info=activity.packageManager.getActivityInfo(activity.componentName,0)
                assertTrue(info.configChanges and android.content.pm.ActivityInfo.CONFIG_UI_MODE != 0)
                runBlocking { model.services.catalog.setThemeMode(ThemeMode.SYSTEM) }
                // This test is run only on our dedicated emulator, never a personal device.
                org.junit.Assume.assumeTrue(android.os.Build.HARDWARE=="ranchu" || android.os.Build.HARDWARE=="goldfish")
                val mode=activity.getSystemService(android.app.UiModeManager::class.java).nightMode
                val wasDark=activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                fun setNight(value:String) {
                    val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd uimode night $value")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                }
                try {
                    setNight(if(wasDark)"no" else "yes")
                    compose.waitUntil(10000) {
                        val dark=activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        dark!=wasDark && model.catalog.settings.themeMode==ThemeMode.SYSTEM
                    }
                    assertSame(activity,compose.activity)
                    assertEquals(session,model.playSession)
                    assertTrue(model.playback.videoFrameReady)
                } finally {
                    setNight(when(mode) { android.app.UiModeManager.MODE_NIGHT_YES -> "yes"; android.app.UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" })
                }
            } finally {
                compose.runOnIdle { model.tab(Page.MANAGEMENT) }
                runBlocking { model.services.catalog.deleteSource(source.id);model.services.catalog.setThemeMode(ThemeMode.SYSTEM) }
            }
        }
    }
}
