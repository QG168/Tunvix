@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tunvix.backend.catalog

import com.tunvix.contract.Channel
import com.tunvix.contract.ChannelDraft
import com.tunvix.contract.ImportReport
import com.tunvix.contract.ImportRequest
import com.tunvix.contract.Outcome
import com.tunvix.contract.SourceImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal fun tempDir(): File = Files.createTempDirectory("tunvix-catalog-test").toFile()

internal fun draft(
    name: String,
    url: String,
    group: String = DEFAULT_GROUP,
    externalId: String? = null,
    logoUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
): ChannelDraft = ChannelDraft(
    name = name,
    url = url,
    group = group,
    logoUrl = logoUrl,
    headers = headers,
    externalId = externalId,
)

internal fun report(vararg channels: ChannelDraft, skipped: Int = 0) = ImportReport(channels.toList(), skipped)

internal fun failureCode(outcome: Outcome<Unit>): String? = (outcome as? Outcome.Failure)?.code

internal class FakeImporter : SourceImporter {
    var next: Outcome<ImportReport> = Outcome.Success(ImportReport(emptyList()))
    var calls = 0
    var lastRequest: ImportRequest? = null

    private val scripted = ArrayDeque<Pair<CompletableDeferred<Unit>, Outcome<ImportReport>>>()

    /**
     * Serves [outcome] to the next [inspect] call, suspending there until the returned gate is
     * completed. Scripted results are handed out in call order, which lets a test hold two
     * concurrent refreshes open and control the order in which they finish.
     */
    fun enqueueGated(outcome: Outcome<ImportReport>): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        scripted += gate to outcome
        return gate
    }

    override suspend fun inspect(request: ImportRequest): Outcome<ImportReport> {
        calls++
        lastRequest = request
        val step = scripted.removeFirstOrNull()
        if (step != null) {
            step.first.await()
            return step.second
        }
        return next
    }
}

internal class FakeThumbnailSource : ThumbnailSource {
    val requested = CopyOnWriteArrayList<String>()
    val cancelledIds = CopyOnWriteArrayList<String>()
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    var result: ByteArray? = "jpeg-still".toByteArray()

    @Volatile
    var firstFrameMillis: Long? = 860L

    @Volatile
    var error: Throwable? = null

    val active = java.util.concurrent.atomic.AtomicInteger()
    val peak = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun fetch(channel: Channel): CaptureResult {
        val count=active.incrementAndGet()
        peak.updateAndGet { maxOf(it,count) }
        try {
            requested += channel.id
            gates[channel.id]?.let { gate ->
                try { gate.await() }
                catch (cancelled: CancellationException) { cancelledIds += channel.id; throw cancelled }
            }
            error?.let { throw it }
            return CaptureResult(result, firstFrameMillis)
        } finally { active.decrementAndGet() }
    }

}

/** Test wiring: real store, real thumbnail cache, fakes only at the platform boundaries. */
internal class TestHarness(private val scope: TestScope, val dir: File = tempDir()) {
    val importer = FakeImporter()
    val thumbnailSource = FakeThumbnailSource()
    var now = 1_000_000L
    var granted = true
    var access: UriAccess = object : UriAccess {
        override fun canRead(uri: String): Boolean = granted
    }

    fun newRepository(
        asyncInit: Boolean = false,
        store: CatalogStore = FileCatalogStore(File(dir, "catalog.tsv")),
    ): LocalCatalogRepository = LocalCatalogRepository(
        store = store,
        importer = importer,
        thumbnails = ThumbnailStore(File(dir, "thumbnails")),
        thumbnailSource = thumbnailSource,
        uriAccess = access,
        io = StandardTestDispatcher(scope.testScheduler),
        // Extraction runs on a scope the test can drive to completion; production uses its own IO scope.
        scope = scope,
        clock = { now },
        asyncInit = asyncInit,
    )

    fun request(url: String = "https://provider.example/list.m3u") = ImportRequest.Subscription(url)

    fun localRequest(uri: String = "content://downloads/list.m3u") = ImportRequest.LocalFile(uri)
}

internal fun TestScope.harness(dir: File = tempDir()) = TestHarness(this, dir)
