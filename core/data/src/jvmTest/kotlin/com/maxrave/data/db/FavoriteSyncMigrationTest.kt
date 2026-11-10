package com.maxrave.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.FavoriteSyncType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun genuineVersion28DatabaseMigratesTo29PreservingFavoritesAndCreatingTombstones() =
        runBlocking {
            // Locate Room's version 28 schema JSON
            val schemaFile =
                sequenceOf(
                    File("schemas/com.maxrave.data.db.MusicDatabase/28.json"),
                    File("core/data/schemas/com.maxrave.data.db.MusicDatabase/28.json"),
                    File("../core/data/schemas/com.maxrave.data.db.MusicDatabase/28.json"),
                    File("../../core/data/schemas/com.maxrave.data.db.MusicDatabase/28.json"),
                ).firstOrNull { it.exists() } ?: error("28.json schema file not found")

            val schemaJson = schemaFile.readText()
            val parsedSchema = Json.parseToJsonElement(schemaJson).jsonObject
            val databaseObj = parsedSchema["database"]!!.jsonObject
            val version = databaseObj["version"]!!.jsonPrimitive.content.toInt()
            val identityHash = databaseObj["identityHash"]!!.jsonPrimitive.content
            val entities = databaseObj["entities"]!!.jsonArray

            assertEquals(28, version, "Schema file must be version 28")

            // Step 1: Create a genuine version 28 database fixture using raw SQLite
            val rawDriver = BundledSQLiteDriver()
            val rawConn = rawDriver.open(dbFile.absolutePath)
            try {
                // Execute all table creations and indices from version 28 schema
                for (entityElement in entities) {
                    val entity = entityElement.jsonObject
                    val tableName = entity["tableName"]!!.jsonPrimitive.content
                    val createSql =
                        entity["createSql"]!!.jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    rawConn.execSQL(createSql)

                    val indices = entity["indices"]?.jsonArray
                    if (indices != null) {
                        for (idxElement in indices) {
                            val idxObj = idxElement.jsonObject
                            val idxSql =
                                idxObj["createSql"]!!.jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", tableName)
                            rawConn.execSQL(idxSql)
                        }
                    }
                }

                // Configure Room metadata for version 28
                rawConn.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                )
                rawConn.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identityHash')",
                )
                rawConn.execSQL("PRAGMA user_version = 28")

                // Insert genuine legacy favorite records into version 28 tables
                rawConn.execSQL(
                    """
                    INSERT INTO song (
                        videoId, title, duration, durationSeconds, isAvailable, isExplicit,
                        likeStatus, videoType, liked, totalPlayTime, downloadState,
                        favoriteAt, inLibrary
                    ) VALUES (
                        'legacy_song_1', 'Legacy Song', '3:30', 210, 1, 0,
                        'LIKE', 'MUSIC_VIDEO_TYPE_ATV', 1, 0, 0,
                        100000, 100000
                    )
                    """.trimIndent(),
                )

                rawConn.execSQL(
                    """
                    INSERT INTO album (
                        browseId, title, audioPlaylistId, description, durationSeconds,
                        liked, inLibrary, favoriteAt, downloadState, trackCount, type
                    ) VALUES (
                        'legacy_album_1', 'Legacy Album', 'OLAK5uy_legacy', 'Legacy album desc', 1800,
                        1, 100000, 100000, 0, 10, 'ALBUM'
                    )
                    """.trimIndent(),
                )

                rawConn.execSQL(
                    """
                    INSERT INTO playlist (
                        id, title, description, duration, durationSeconds,
                        privacy, thumbnails, trackCount, liked, inLibrary,
                        favoriteAt, downloadState
                    ) VALUES (
                        'legacy_playlist_1', 'Legacy Playlist', 'Desc', '45:00', 2700,
                        'PUBLIC', '', 15, 1, 100000,
                        100000, 0
                    )
                    """.trimIndent(),
                )

                rawConn.execSQL(
                    """
                    INSERT INTO podcast_table (
                        podcastId, title, authorId, authorName, isFavorite,
                        inLibrary, favoriteTime, listEpisodes
                    ) VALUES (
                        'legacy_podcast_1', 'Legacy Podcast', 'author_1', 'Author One', 1,
                        100000, 100000, '[]'
                    )
                    """.trimIndent(),
                )

                rawConn.execSQL(
                    """
                    INSERT INTO artist (
                        channelId, name, followed, followedAt, inLibrary
                    ) VALUES (
                        'legacy_artist_1', 'Legacy Artist', 1, 100000, 100000
                    )
                    """.trimIndent(),
                )

                // Verify the new tombstone table does NOT exist before migration
                val tombTableStmt =
                    rawConn.prepare(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='favorite_sync_tombstone'",
                    )
                try {
                    assertTrue(tombTableStmt.step())
                    assertEquals(
                        0L,
                        tombTableStmt.getLong(0),
                        "favorite_sync_tombstone table must not exist in version 28",
                    )
                } finally {
                    tombTableStmt.close()
                }

                // Verify user_version is strictly 28
                val versionStmt = rawConn.prepare("PRAGMA user_version")
                try {
                    assertTrue(versionStmt.step())
                    assertEquals(28L, versionStmt.getLong(0))
                } finally {
                    versionStmt.close()
                }
            } finally {
                rawConn.close()
            }

            // Step 2: Open the database using Room version 29 MusicDatabase (triggers AutoMigration(28, 29))
            val upgradedDb =
                Room
                    .databaseBuilder<MusicDatabase>(dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addTypeConverter(Converters())
                    .build()

            try {
                val dao = upgradedDb.getDatabaseDao()

                // Verify existing songs remain favorited and undamaged
                val song = dao.getSong("legacy_song_1")
                assertNotNull(song, "Song must survive migration")
                assertTrue(song.liked, "Song must remain liked after migration")
                assertEquals("Legacy Song", song.title)

                // Verify existing albums remain favorited and undamaged
                val album = dao.getAlbum("legacy_album_1")
                assertNotNull(album, "Album must survive migration")
                assertTrue(album.liked, "Album must remain liked after migration")
                assertEquals("Legacy Album", album.title)

                // Verify existing playlists remain favorited and undamaged
                val playlist = dao.getPlaylist("legacy_playlist_1")
                assertNotNull(playlist, "Playlist must survive migration")
                assertTrue(playlist.liked, "Playlist must remain liked after migration")
                assertEquals("Legacy Playlist", playlist.title)

                // Verify existing podcasts remain favorited and undamaged
                val podcast = dao.getPodcast("legacy_podcast_1")
                assertNotNull(podcast, "Podcast must survive migration")
                assertTrue(podcast.isFavorite, "Podcast must remain favorite after migration")
                assertEquals("Legacy Podcast", podcast.title)

                // Verify existing artists remain followed and undamaged
                val artist = dao.getArtist("legacy_artist_1")
                assertNotNull(artist, "Artist must survive migration")
                assertTrue(artist.followed, "Artist must remain followed after migration")
                assertEquals("Legacy Artist", artist.name)

                // Verify the new tombstone table was created by the migration and is operational
                val initialTombstones = dao.getAllFavoriteTombstones()
                assertNotNull(initialTombstones)
                assertTrue(
                    initialTombstones.isEmpty(),
                    "Tombstones table must be empty right after migration",
                )

                // Verify writing and reading from the new tombstone table
                dao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.SONG,
                        id = "legacy_song_1",
                        isFavorite = true,
                        updatedAt = 999999L,
                    ),
                )
                val storedTombstone = dao.getFavoriteTombstone(FavoriteSyncType.SONG, "legacy_song_1")
                assertNotNull(storedTombstone)
                assertTrue(storedTombstone.isFavorite)
                assertEquals(999999L, storedTombstone.updatedAt)
            } finally {
                upgradedDb.close()
            }

            // Step 3: Verify with raw SQLite that PRAGMA user_version is now 29
            val verifyConn = rawDriver.open(dbFile.absolutePath)
            try {
                val finalVersionStmt = verifyConn.prepare("PRAGMA user_version")
                try {
                    assertTrue(finalVersionStmt.step())
                    assertEquals(29L, finalVersionStmt.getLong(0), "PRAGMA user_version must be 29 after migration")
                } finally {
                    finalVersionStmt.close()
                }
            } finally {
                verifyConn.close()
            }
        }
}
