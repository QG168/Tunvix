package com.tunvix.app.ui
import android.view.SurfaceView
import com.tunvix.app.services.*
import com.tunvix.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncUiTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private fun model(repo:CatalogRepository=MemoryCatalog(), importer:SourceImporter=object:SourceImporter {override suspend fun inspect(request:ImportRequest):Outcome<ImportReport>{delay(1000);return Outcome.Success(ImportReport(listOf(ChannelDraft("频道","https://example.com/live"))))}}):TunvixModel {
        val playback=object:PlaybackController {
            override val snapshot=MutableStateFlow(PlaybackSnapshot())
            override fun attachSurface(surface:SurfaceView){};override fun detachSurface(surface:SurfaceView){}
            override fun play(channel:Channel){};override fun pause(){};override fun resume(){};override fun retry(){};override fun stop(){};override fun setVolume(percent:Int){};override fun refreshVolume(){};override fun onBackground(){};override fun onForeground(){};override fun release(){}
        }
        return TunvixModel(AppServices(repo,importer,playback,true))
    }
    @Test fun leavingImportDoesNotNavigateWhenDownloadFinishes()=runTest(dispatcher) {
        val m=model();m.navigate(Page.SUBSCRIBE);m.formName="测试";m.formLink="https://example.com/list.m3u";m.parse();runCurrent();m.back();advanceUntilIdle()
        assertEquals(Page.HOME,m.page);assertNull(m.report)
    }
    @Test fun leavingSaveDoesNotInterruptTheNewPage()=runTest(dispatcher) {
        val repo=object:CatalogRepository by MemoryCatalog(){override suspend fun saveCustomChannel(channelId:String?,draft:ChannelDraft):Outcome<Unit>{delay(1000);return Outcome.Success(Unit)}}
        val m=model(repo);m.navigate(Page.ADD);m.navigate(Page.MANUAL);m.formName="频道";m.formLink="https://example.com/live";m.saveChannel();runCurrent();m.back();advanceUntilIdle()
        assertEquals(Page.ADD,m.page)
    }
    @Test fun failedThumbnailFallsBackToLogo()=runTest {
        val result=ImageCandidates.firstAvailable("broken-local","https://example.com/logo"){uri->if(uri=="broken-local")throw IllegalStateException("bad image") else "decoded logo"}
        assertEquals("decoded logo",result)
    }
    @Test fun cancellationIsNotTreatedAsImageFailure()=runTest {
        try {ImageCandidates.firstAvailable("local","logo"){throw CancellationException("cancel")};fail("must propagate cancellation")}catch(expected:CancellationException){assertEquals("cancel",expected.message)}
    }
}
