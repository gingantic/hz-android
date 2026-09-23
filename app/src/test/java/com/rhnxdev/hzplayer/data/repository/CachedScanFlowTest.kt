package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pins [cachedScanFlow]'s cache → Room → preview → scan protocol, especially the
 * `emitted` flag that decides whether a scan failure is swallowed or rethrown.
 *
 * The preview, the trailing `emptyList()` and the rethrow are all `BuildConfig.DEBUG`
 * dependent, and `gradlew test` runs this source set against **both** variants — so
 * the variant-specific cases are gated with `assumeTrue`/`assumeFalse` rather than
 * asserting one build's behaviour and failing the other.
 *
 * `kotlinx-coroutines-test` is not a dependency, so these collect through
 * `runBlocking`; the flow's `flowOn(Dispatchers.IO)` still hands off to a real pool,
 * which is fine because every fake upstream completes immediately.
 */
class CachedScanFlowTest {

    @Test
    fun memoryCacheHit_emitsCacheAndSkipsRoomAndScan() {
        val out = collect(memoryCache = { listOf("cached") })

        assertEquals(listOf(listOf("cached")), out.emissions)
        assertEquals(0, out.roomReads)
        assertEquals(0, out.scans)
    }

    @Test
    fun forceRefresh_ignoresMemoryCacheAndReadsRoom() {
        val out = collect(forceRefresh = true, memoryCache = { listOf("cached") }, room = listOf(1))

        assertEquals(1, out.roomReads)
        assertTrue(out.emissions.none { it == listOf("cached") })
        assertTrue(out.emissions.contains(listOf("item1")))
    }

    /**
     * The documented trap: `memoryCache` is a getter, not a captured value. The flow
     * is cold and re-collected on every tab re-entry, so a second collection must see
     * a cache written after the first one finished.
     */
    @Test
    fun memoryCache_isReadPerCollection_notCaptured() {
        var reads = 0
        var cache: List<String>? = listOf("first")
        val flow = buildFlow(memoryCache = { reads++; cache })

        assertEquals(listOf(listOf("first")), runBlocking { flow.toList() })

        cache = listOf("second")
        assertEquals(listOf(listOf("second")), runBlocking { flow.toList() })
        assertEquals(2, reads)
    }

    @Test
    fun roomPopulated_scanEmpty_emitsRoomOnly() {
        val out = collect(room = listOf(1))

        assertEquals(listOf(listOf("item1")), out.emissions)
        assertTrue(out.replaced.isEmpty())
    }

    @Test
    fun roomPopulatedAndScanPopulated_emitsBothAndReplacesRoom() {
        val out = collect(room = listOf(1), scan = listOf(2))

        assertEquals(listOf(listOf("item1"), listOf("item2")), out.emissions)
        assertEquals(listOf(listOf(2)), out.replaced)
        assertEquals(listOf(listOf("item1"), listOf("item2")), out.cachedIn)
    }

    @Test
    fun sortIsAppliedToEveryEmission() {
        val out = collect(room = listOf(1, 2), scan = listOf(3, 4), sort = { it.reversed() })

        assertEquals(listOf(listOf("item2", "item1"), listOf("item4", "item3")), out.emissions)
    }

    /** Room already emitted, so a failing scan must not take the list away. */
    @Test
    fun scanFailureAfterRoomEmission_isSwallowed() {
        val out = collect(room = listOf(1), scanError = IllegalStateException("boom"))

        assertEquals(listOf(listOf("item1")), out.emissions)
    }

    @Test
    fun roomEmpty_debug_emitsPreviewBeforeScan() {
        assumeTrue(BuildConfig.DEBUG)

        val out = collect(preview = listOf("preview"), scan = listOf(1))

        assertEquals(listOf(listOf("preview"), listOf("item1")), out.emissions)
    }

    /** The `!emitted` guard: the preview counts as an emission, so no empty tail. */
    @Test
    fun roomEmptyAndScanEmpty_debug_emitsPreviewOnly() {
        assumeTrue(BuildConfig.DEBUG)

        val out = collect(preview = listOf("preview"))

        assertEquals(listOf(listOf("preview")), out.emissions)
    }

    @Test
    fun roomEmptyAndScanEmpty_release_emitsEmptyList() {
        assumeFalse(BuildConfig.DEBUG)

        val out = collect(preview = listOf("preview"))

        assertEquals(listOf(emptyList<String>()), out.emissions)
    }

    /**
     * The half of the protocol debug builds never reach: with no preview and no Room
     * data, `emitted` is still false when the scan fails, so the failure surfaces
     * instead of the flow completing empty and reading as "no media".
     */
    @Test
    fun scanFailureWithNothingEmitted_release_rethrows() {
        assumeFalse(BuildConfig.DEBUG)

        val error = runCatching { collect(scanError = IllegalStateException("boom")) }.exceptionOrNull()

        assertTrue("expected the scan failure to propagate, got $error", error is IllegalStateException)
    }

    private class Outcome(
        val emissions: List<List<String>>,
        val replaced: List<List<Int>>,
        val cachedIn: List<List<String>>,
        val roomReads: Int,
        val scans: Int,
    )

    private fun collect(
        forceRefresh: Boolean = false,
        memoryCache: () -> List<String>? = { null },
        preview: List<String> = emptyList(),
        room: List<Int> = emptyList(),
        scan: List<Int> = emptyList(),
        scanError: Throwable? = null,
        sort: (List<String>) -> List<String> = { it },
    ): Outcome {
        var roomReads = 0
        var scans = 0
        val replaced = mutableListOf<List<Int>>()
        val cachedIn = mutableListOf<List<String>>()
        val emissions = runBlocking {
            buildFlow(
                forceRefresh = forceRefresh,
                memoryCache = memoryCache,
                preview = preview,
                readRoom = { roomReads++; flowOf(room) },
                scan = {
                    scans++
                    if (scanError != null) flow { throw scanError } else flowOf(scan)
                },
                replaceRoom = { replaced += it },
                cacheIn = { cachedIn += it },
                sort = sort,
            ).toList()
        }
        return Outcome(emissions, replaced, cachedIn, roomReads, scans)
    }

    private fun buildFlow(
        forceRefresh: Boolean = false,
        memoryCache: () -> List<String>? = { null },
        preview: List<String> = emptyList(),
        readRoom: () -> Flow<List<Int>> = { flowOf(emptyList()) },
        scan: () -> Flow<List<Int>> = { flowOf(emptyList()) },
        replaceRoom: suspend (List<Int>) -> Unit = {},
        cacheIn: (List<String>) -> Unit = {},
        sort: (List<String>) -> List<String> = { it },
    ): Flow<List<String>> = cachedScanFlow(
        forceRefresh = forceRefresh,
        memoryCache = memoryCache,
        preview = preview,
        readRoom = readRoom,
        toItem = { "item$it" },
        scan = scan,
        replaceRoom = replaceRoom,
        cacheIn = cacheIn,
        sort = sort,
    )
}
