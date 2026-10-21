package com.maxrave.simpmusic.viewModel.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddAllToFavoritesTest {
    @Test
    fun duplicatesAreRequestedOnce() {
        val plan = planAddAllToFavorites(listOf("a", "b", "a"), mapOf("a" to false, "b" to false))

        assertEquals(listOf("a", "b"), plan.pending)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.failed)
    }

    @Test
    fun existingFavoritesAreSkipped() {
        val plan = planAddAllToFavorites(listOf("a", "b"), mapOf("a" to true, "b" to false))

        assertEquals(listOf("b"), plan.pending)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun songsWithoutAStoredRowFail() {
        val plan = planAddAllToFavorites(listOf("a", "gone"), mapOf("a" to false))

        assertEquals(listOf("a"), plan.pending)
        assertEquals(1, plan.failed)
    }

    @Test
    fun emptyPlaylistProducesNothing() {
        val plan = planAddAllToFavorites(emptyList(), emptyMap())

        assertEquals(emptyList(), plan.pending)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.failed)
    }

    @Test
    fun largePlaylistKeepsOrderAndCountsEverySong() {
        val ids = (1..1500).map { "id$it" }
        val liked = ids.associateWith { false }

        val plan = planAddAllToFavorites(ids, liked)

        assertEquals(ids, plan.pending)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.failed)
    }

    @Test
    fun applyOnlyWritesPendingSongsAndReportsCounts() =
        runBlocking {
            val plan = AddAllToFavoritesPlan(pending = listOf("a", "b"), skipped = 1, failed = 1)
            val written = mutableListOf<String>()

            val result =
                applyAddAllToFavorites(
                    plan = plan,
                    favorite = { videoId ->
                        written += videoId
                    },
                )

            assertEquals(listOf("a", "b"), written)
            assertEquals(AddAllToFavoritesResult(added = 2, skipped = 1, failed = 1), result)
        }

    @Test
    fun oneFailedWriteDoesNotStopLaterSongs() =
        runBlocking {
            val plan = AddAllToFavoritesPlan(pending = listOf("a", "b", "c"), skipped = 0, failed = 0)
            val attempted = mutableListOf<String>()
            val failedIds = mutableListOf<String>()

            val result =
                applyAddAllToFavorites(
                    plan = plan,
                    favorite = { videoId ->
                        attempted += videoId
                        if (videoId == "b") throw IllegalStateException("write failed")
                    },
                    onFailure = { videoId, _ -> failedIds += videoId },
                )

            assertEquals(listOf("a", "b", "c"), attempted)
            assertEquals(listOf("b"), failedIds)
            assertEquals(AddAllToFavoritesResult(added = 2, skipped = 0, failed = 1), result)
        }

    @Test
    fun cancellationIsRethrownInsteadOfCountedAsFailure() {
        val plan = AddAllToFavoritesPlan(pending = listOf("a", "b"), skipped = 0, failed = 0)
        val written = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            runBlocking {
                applyAddAllToFavorites(
                    plan = plan,
                    favorite = { videoId ->
                        written += videoId
                        if (videoId == "b") throw CancellationException("cancelled")
                    },
                )
            }
        }
        assertEquals(listOf("a", "b"), written)
    }

    @Test
    fun progressReportsEachWrittenSong() =
        runBlocking {
            val plan = AddAllToFavoritesPlan(pending = listOf("a", "b", "c"), skipped = 0, failed = 0)
            val progress = mutableListOf<Pair<Int, Int>>()

            applyAddAllToFavorites(
                plan = plan,
                favorite = {},
                onProgress = { done, total -> progress += done to total },
            )

            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        }

    @Test
    fun alreadyFavoritedSongsAreNotRewritten() =
        runBlocking {
            val plan = AddAllToFavoritesPlan(pending = listOf("new"), skipped = 2, failed = 0)
            val written = mutableListOf<String>()

            val result = applyAddAllToFavorites(plan = plan, favorite = { written += it })

            assertEquals(listOf("new"), written)
            assertEquals(AddAllToFavoritesResult(added = 1, skipped = 2, failed = 0), result)
        }
}
