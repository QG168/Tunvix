@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tunvix.backend.catalog

import com.tunvix.contract.FirstFrameResult
import com.tunvix.contract.FirstFrameStatus
import com.tunvix.contract.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFrameMeasurementTest {

    private suspend fun LocalCatalogRepository.enableDetection() {
        updateSettings(snapshot.value.settings.copy(firstFrameEnabled = true))
    }

    /** 写入旧版式（v1、无测量列）目录文件，可指定每个频道的截图名。 */
    private fun writeLegacyCatalog(dir: File, channels: List<Triple<String, String, String?>>) {
        File(dir,"catalog.tsv").writeText(buildString {
            append("tunvix-catalog\t1\n")
            append("meta\tselected\ts1\n")
            append("meta\tsettings\t1\t1\n")
            append("source\ts1\tsrc\tSUBSCRIPTION\thttps%3A%2F%2Fp%2Fl\n")
            channels.forEach { (id,url,thumb) ->
                append("channel\t$id\ts1\tch-$id\t")
                append(java.net.URLEncoder.encode(url,"UTF-8"))
                append("\tg\t\t\t0\t11\t")
                append(thumb ?: "")
                append("\t\n")
            }
        })
        File(dir,"thumbnails").mkdirs()
        channels.forEach { (id,_,thumb) -> thumb?.let { File(dir,"thumbnails/$it").writeBytes("still-$id".toByteArray()) } }
    }

    @Test fun successfulCaptureRecordsMeasurementWithoutExtraFetch() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.enableDetection()
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val channel=repo.snapshot.value.channels.single()
        assertEquals(FirstFrameResult(FirstFrameStatus.SUCCESS,860L,h.now),channel.firstFrame)
        assertEquals(1,h.thumbnailSource.requested.size) // 补图与测量共用同一次抓流
        assertNotNull(channel.thumbnailUri)
    }

    @Test fun detectionOnlyMeasuresChannelThatAlreadyHasStill() = runTest {
        val h=harness()
        // 模拟旧数据：有截图、无测量结果。
        writeLegacyCatalog(h.dir,listOf(Triple("c1","https://p/a","thumb.jpg")))
        val repo=h.newRepository()
        val channel=repo.snapshot.value.channels.single()
        assertNotNull(channel.thumbnailUri);assertNull(channel.firstFrame)
        val stillBefore=channel.thumbnailUri
        repo.enableDetection()
        repo.requestThumbnails(listOf("c1"),true);advanceUntilIdle()
        val measured=repo.snapshot.value.channels.single()
        // 保留原截图，不因检测改变截图内容。
        assertEquals(stillBefore,measured.thumbnailUri)
        assertEquals(FirstFrameStatus.SUCCESS,measured.firstFrame?.status)
        assertEquals(1,h.thumbnailSource.requested.size)
    }

    @Test fun captureFailureRecordsTimeoutAndFailureNeverZero() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a"),draft("B","https://p/b")))
        val ids=repo.snapshot.value.channels.map{it.id}
        repo.enableDetection()
        // 8 秒总超时未取得画面 → 超时。
        h.thumbnailSource.gates[ids[0]]=CompletableDeferred()
        // 平台无法出帧 → 失败。
        h.thumbnailSource.result=null
        repo.requestThumbnails(ids,true)
        advanceTimeBy(8_001);runCurrent();advanceUntilIdle()
        val byId=repo.snapshot.value.channels.associateBy{it.id}
        assertEquals(FirstFrameStatus.TIMEOUT,byId[ids[0]]?.firstFrame?.status)
        assertNull(byId[ids[0]]?.firstFrame?.millis)
        assertEquals(FirstFrameStatus.FAILED,byId[ids[1]]?.firstFrame?.status)
        assertNull(byId[ids[1]]?.firstFrame?.millis)
    }

    @Test fun manualRefreshRemeasuresAndOverwritesExistingResult() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertEquals(860L,repo.snapshot.value.channels.single().firstFrame?.millis)
        h.now=2_000_000L
        h.thumbnailSource.firstFrameMillis=4200L
        val refresh=repo.refreshThumbnails(listOf(id))
        assertTrue(refresh is Outcome.Success)
        val channel=repo.snapshot.value.channels.single()
        assertEquals(4200L,channel.firstFrame?.millis)
        assertEquals(2_000_000L,channel.firstFrame?.measuredAt)
    }

    @Test fun automaticRecaptureDoesNotOverwriteExistingMeasurement() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertEquals(860L,repo.snapshot.value.channels.single().firstFrame?.millis)
        // 截图被清理后自动补图：保留原测量结果，不自动覆盖。
        repo.clearThumbnailCache();advanceUntilIdle()
        h.now=3_000_000L;h.thumbnailSource.firstFrameMillis=500L
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val channel=repo.snapshot.value.channels.single()
        assertNotNull(channel.thumbnailUri)
        assertEquals(FirstFrameResult(FirstFrameStatus.SUCCESS,860L,1_000_000L),channel.firstFrame)
    }

    @Test fun mediaChangeInvalidatesMeasurement() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("源",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertEquals(FirstFrameStatus.SUCCESS,repo.snapshot.value.channels.single().firstFrame?.status)
        h.importer.next=Outcome.Success(report(draft("A","https://p/new")))
        repo.refreshSource(repo.snapshot.value.sources.single().id)
        val channel=repo.snapshot.value.channels.single()
        assertNull(channel.firstFrame)
        assertNull(channel.thumbnailUri)
    }

    @Test fun customChannelMediaChangeInvalidatesMeasurement() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.saveCustomChannel(null,draft("A","https://p/a"))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertNotNull(repo.snapshot.value.channels.single().firstFrame)
        repo.saveCustomChannel(id,draft("A","https://p/changed"));advanceUntilIdle()
        assertNull(repo.snapshot.value.channels.single().firstFrame)
    }

    @Test fun clearThumbnailCacheKeepsMeasurements() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        repo.clearThumbnailCache();advanceUntilIdle()
        val channel=repo.snapshot.value.channels.single()
        assertNull(channel.thumbnailUri)
        assertEquals(FirstFrameResult(FirstFrameStatus.SUCCESS,860L,1_000_000L),channel.firstFrame)
    }

    @Test fun disablingDetectionCancelsOnlyDetectionJobs() = runTest {
        val h=harness()
        // 旧数据：有截图、无测量结果 —— 开启后只做纯检测。
        writeLegacyCatalog(h.dir,listOf(Triple("c1","https://p/a","thumb.jpg")))
        val repo=h.newRepository()
        val id="c1"
        repo.enableDetection()
        h.thumbnailSource.gates[id]=CompletableDeferred()
        repo.requestThumbnails(listOf(id),true);runCurrent()
        assertEquals(listOf(id),h.thumbnailSource.requested) // 纯检测发起
        assertTrue(id in repo.snapshot.value.detectingIds)
        repo.updateSettings(repo.snapshot.value.settings.copy(firstFrameEnabled=false))
        repo.requestThumbnails(listOf(id),true);runCurrent();advanceUntilIdle()
        assertTrue(id in h.thumbnailSource.cancelledIds)
        assertTrue(repo.snapshot.value.detectingIds.isEmpty())
        // 检测被取消，未产生测量结果。
        assertNull(repo.snapshot.value.channels.single().firstFrame)
        // 重新开启：无结果则重新检测；出结果后不再重复检测。
        repo.enableDetection()
        repo.requestThumbnails(listOf(id),true);runCurrent()
        assertEquals(2,h.thumbnailSource.requested.size)
        h.thumbnailSource.gates[id]?.complete(Unit);advanceUntilIdle()
        assertEquals(FirstFrameStatus.SUCCESS,repo.snapshot.value.channels.single().firstFrame?.status)
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        assertEquals(2,h.thumbnailSource.requested.size)
    }

    @Test fun detectionMixesWithCaptureUnderSharedThreeSlots() = runTest {
        val h=harness()
        // c0、c1 有截图无测量（纯检测）；c2、c3 缺图（补图）。四路共享 3 个名额。
        writeLegacyCatalog(h.dir,listOf(
            Triple("c0","https://p/a","t0.jpg"),
            Triple("c1","https://p/b","t1.jpg"),
            Triple("c2","https://p/c",null),
            Triple("c3","https://p/d",null),
        ))
        val repo=h.newRepository()
        repo.enableDetection()
        repo.requestThumbnails(listOf("c0","c1","c2","c3"),true)
        advanceTimeBy(7_999);runCurrent()
        assertTrue(h.thumbnailSource.peak.get()<=3)
        advanceUntilIdle()
        assertTrue(h.thumbnailSource.peak.get()<=3)
        assertEquals(0,h.thumbnailSource.active.get())
        val byId=repo.snapshot.value.channels.associateBy{it.id}
        assertEquals(listOf("t0.jpg","t1.jpg"),listOf(byId["c0"],byId["c1"]).map{File(java.net.URI(it!!.thumbnailUri!!)).name})
        assertTrue(byId.values.all { it.firstFrame?.status==FirstFrameStatus.SUCCESS })
    }

    @Test fun measurementAndToggleSurviveRestart() = runTest {
        val h=harness(); val repo=h.newRepository()
        repo.commitImport("组",h.request(),report(draft("A","https://p/a")))
        val id=repo.snapshot.value.channels.single().id
        repo.enableDetection()
        repo.requestThumbnails(listOf(id),true);advanceUntilIdle()
        val restarted=h.newRepository()
        val channel=restarted.snapshot.value.channels.single()
        assertEquals(FirstFrameResult(FirstFrameStatus.SUCCESS,860L,1_000_000L),channel.firstFrame)
        assertTrue(restarted.snapshot.value.settings.firstFrameEnabled)
    }

    @Test fun oldCatalogWithoutNewColumnsReadsAsDisabledAndUnmeasured() = runTest {
        val h=harness()
        writeLegacyCatalog(h.dir,listOf(Triple("c1","https://p/a","thumb.jpg")))
        val repo=h.newRepository()
        assertNull(repo.snapshot.value.channels.single().firstFrame)
        assertFalse(repo.snapshot.value.settings.firstFrameEnabled)
    }

    @Test fun measurementRoundTripsThroughStore() {
        val catalog=StoredCatalog(
            sources=listOf(StoredSource("s1","src",com.tunvix.contract.SourceKind.SUBSCRIPTION,"https://p/l")),
            channels=listOf(
                StoredChannel("c1","s1","ch","https://p/a",firstFrame=FirstFrameResult(FirstFrameStatus.SUCCESS,1234L,99L)),
                StoredChannel("c2","s1","ch2","https://p/b",firstFrame=FirstFrameResult(FirstFrameStatus.TIMEOUT,measuredAt=98L)),
                StoredChannel("c3","s1","ch3","https://p/c",firstFrame=FirstFrameResult(FirstFrameStatus.FAILED,measuredAt=97L)),
                StoredChannel("c4","s1","ch4","https://p/d"),
            ),
            selectedSourceId="s1",
            settings=com.tunvix.contract.UserSettings(firstFrameEnabled=true),
        )
        val encoded=CatalogCodec.encode(catalog)
        val decoded=CatalogCodec.decode(encoded)!!
        assertEquals(catalog.channels,decoded.channels)
        assertTrue(decoded.settings.firstFrameEnabled)
        // 写出的目录必须仍被旧版式解析（前 12 列不变）。
        assertTrue(encoded.lines().first { it.startsWith("channel\tc1") }.split('\t').size >= 15)
    }
}
