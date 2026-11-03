package com.maxrave.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.domain.data.entities.RecentSongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.FeedbackTokens
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentSongDaoTest {
    @Test
    fun keepsNewestTwentyAndMovesReselectedToFront() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<MusicDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .addTypeConverter(Converters())
                    .build()
            try {
                val dao = db.getDatabaseDao()
                (1..25).forEach { i ->
                    dao.saveRecentSong(RecentSongEntity("v$i", track("v$i"), i.toLong()))
                }

                val capped = dao.getRecentSongs().first()
                assertEquals(20, capped.size)
                assertEquals("v25", capped.first().videoId)

                dao.saveRecentSong(RecentSongEntity("v1", track("v1"), 1000))
                val afterReselect = dao.getRecentSongs().first()
                assertEquals(20, afterReselect.size)
                assertEquals("v1", afterReselect.first().videoId)
                assertEquals(1, afterReselect.count { it.videoId == "v1" })

                dao.clearRecentSongs()
                assertTrue(dao.getRecentSongs().first().isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun roundTripsSongMetadata() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<MusicDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .addTypeConverter(Converters())
                    .build()
            try {
                val dao = db.getDatabaseDao()
                val full =
                    Track(
                        album = Album("MPREb_album", "Album name"),
                        artists = listOf(Artist("UC_artist", "Artist name")),
                        duration = "3:45",
                        durationSeconds = 225,
                        isAvailable = true,
                        isExplicit = true,
                        likeStatus = "LIKE",
                        thumbnails =
                            listOf(
                                Thumbnail(544, "https://example.com/cover=w544-h544", 544),
                            ),
                        title = "Song title",
                        videoId = "abc123",
                        videoType = "MUSIC_VIDEO_TYPE_ATV",
                        category = null,
                        feedbackTokens = FeedbackTokens("add", "remove"),
                        resultType = null,
                        year = "2024",
                    )
                dao.saveRecentSong(RecentSongEntity("abc123", full, 42))

                val stored = dao.getRecentSongs().first().single()
                assertEquals("abc123", stored.videoId)
                assertEquals(42, stored.selectedAt)
                assertEquals(full, stored.track)
            } finally {
                db.close()
            }
        }

    private fun track(videoId: String) =
        Track(
            album = null,
            artists = null,
            duration = null,
            durationSeconds = 100,
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = null,
            title = "Song $videoId",
            videoId = videoId,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null,
        )
}
