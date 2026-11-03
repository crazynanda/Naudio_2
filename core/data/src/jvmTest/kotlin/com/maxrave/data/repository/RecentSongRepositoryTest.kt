package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.FeedbackTokens
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Search history row renders artwork, title and artist straight off the stored [Track], so the
 * test asserts those fields survive the repository -> LocalDataSource -> Room -> Flow path AND a
 * database reopen, which is what "navigate away and come back" relies on.
 */
class RecentSongRepositoryTest {
    @Test
    fun recentSongSurvivesTheRepositoryAndAReopen() =
        runBlocking {
            val dir = Files.createTempDirectory("recent-song-repo").toFile()
            val dbFile = File(dir, "recent.db")
            val track = fullTrack()

            open(dbFile).let { db ->
                try {
                    val repository = SearchRepositoryImpl(LocalDataSource(db.getDatabaseDao(), db), YouTube())
                    repository.saveRecentSong(track)
                    assertEquals(1, db.getDatabaseDao().getRecentSongCount())
                } finally {
                    db.close()
                }
            }

            open(dbFile).let { db ->
                try {
                    val stored =
                        LocalDataSource(db.getDatabaseDao(), db)
                            .getRecentSongs()
                            .first()
                    assertEquals(1, stored.size)
                    assertEquals(track.videoId, stored.single().videoId)
                    assertEquals(track.title, stored.single().track.title)
                    assertEquals(track.thumbnails, stored.single().track.thumbnails)
                    assertEquals(track.artists, stored.single().track.artists)
                    assertTrue(stored.single().selectedAt > 0)
                } finally {
                    db.close()
                }
            }

            dir.deleteRecursively()
            Unit
        }

    private fun open(file: File): MusicDatabase =
        Room
            .databaseBuilder<MusicDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()

    private fun fullTrack() =
        Track(
            album = Album("MPREb_album", "Album name"),
            artists = listOf(Artist("UC_artist", "Artist name")),
            duration = "3:45",
            durationSeconds = 225,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "LIKE",
            thumbnails = listOf(Thumbnail(544, "https://example.com/cover=w544-h544", 544)),
            title = "Song title",
            videoId = "abc123",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            feedbackTokens = FeedbackTokens("add", "remove"),
            resultType = null,
            year = "2024",
        )
}
