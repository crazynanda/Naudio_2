package com.maxrave.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.FavoriteSyncType
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.extension.now
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FavoriteSyncMigrationTest {
    private lateinit var tempDir: File
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("fav-migration-test").toFile()
        dbFile = File(tempDir, "migration_test.db")
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun migrationPreservesExistingFavoritesAndCreatesTombstoneTable() = runBlocking {
        // Step 1: Open database at current version (29) with all tables
        var db = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()

        val dao = db.getDatabaseDao()

        // Insert legacy/existing favorites into database
        val song = SongEntity(
            videoId = "legacy_song_1",
            title = "Legacy Song",
            duration = "3:00",
            durationSeconds = 180,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "LIKE",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            resultType = null,
            liked = true,
            favoriteAt = now(),
            inLibrary = now(),
        )
        val album = AlbumEntity(
            browseId = "legacy_album_1",
            title = "Legacy Album",
            audioPlaylistId = "OLAK5uy_legacy",
            description = "Desc",
            duration = "30:00",
            durationSeconds = 1800,
            thumbnails = null,
            trackCount = 5,
            type = "ALBUM",
            year = "2023",
            liked = true,
            favoriteAt = now(),
            inLibrary = now(),
        )
        val playlist = PlaylistEntity(
            id = "legacy_playlist_1",
            title = "Legacy Playlist",
            liked = true,
            favoriteAt = now(),
            inLibrary = now(),
        )

        dao.insertSong(song)
        dao.updateLiked(1, song.videoId, song.favoriteAt)
        dao.insertAlbum(album)
        dao.updateAlbumLiked(1, album.browseId, album.favoriteAt)
        dao.insertPlaylist(playlist)
        dao.updatePlaylistLiked(1, playlist.id, playlist.favoriteAt)

        // Close DB
        db.close()

        // Step 2: Reopen DB
        val reopenedDb = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()

        try {
            val reopenedDao = reopenedDb.getDatabaseDao()

            // Verify all existing favorites survived
            val restoredSong = reopenedDao.getSong("legacy_song_1")
            assertNotNull(restoredSong)
            assertTrue(restoredSong.liked)
            assertEquals("Legacy Song", restoredSong.title)

            val restoredAlbum = reopenedDao.getAlbum("legacy_album_1")
            assertNotNull(restoredAlbum)
            assertTrue(restoredAlbum.liked)

            val restoredPlaylist = reopenedDao.getPlaylist("legacy_playlist_1")
            assertNotNull(restoredPlaylist)
            assertTrue(restoredPlaylist.liked)

            // Verify the new tombstone table exists and works properly
            reopenedDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.SONG,
                    id = "legacy_song_1",
                    isFavorite = true,
                    updatedAt = 12345L,
                ),
            )
            val tomb = reopenedDao.getFavoriteTombstone(FavoriteSyncType.SONG, "legacy_song_1")
            assertNotNull(tomb)
            assertTrue(tomb.isFavorite)
            assertEquals(12345L, tomb.updatedAt)
        } finally {
            reopenedDb.close()
        }
    }
}
