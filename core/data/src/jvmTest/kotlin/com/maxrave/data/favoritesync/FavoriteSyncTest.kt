package com.maxrave.data.favoritesync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.FavoriteSyncType
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.extension.now
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteSyncTest {
    private lateinit var tempDir: File
    private lateinit var androidDb: MusicDatabase
    private lateinit var windowsDb: MusicDatabase
    private lateinit var androidDataSource: LocalDataSource
    private lateinit var windowsDataSource: LocalDataSource
    private lateinit var androidSyncStore: FavoriteSyncStore
    private lateinit var windowsSyncStore: FavoriteSyncStore

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("fav-sync-test").toFile()
        val androidDbFile = File(tempDir, "android.db")
        val windowsDbFile = File(tempDir, "windows.db")

        androidDb = Room.databaseBuilder<MusicDatabase>(androidDbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()
        windowsDb = Room.databaseBuilder<MusicDatabase>(windowsDbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()

        androidDataSource = LocalDataSource(androidDb.getDatabaseDao(), androidDb)
        windowsDataSource = LocalDataSource(windowsDb.getDatabaseDao(), windowsDb)

        androidSyncStore = FavoriteSyncStore(androidDb.getDatabaseDao())
        windowsSyncStore = FavoriteSyncStore(windowsDb.getDatabaseDao())
    }

    @AfterTest
    fun tearDown() {
        androidDb.close()
        windowsDb.close()
        tempDir.deleteRecursively()
    }

    private fun createSong(
        videoId: String,
        title: String = "Title $videoId",
        liked: Boolean = true,
        favoriteAt: LocalDateTime? = now(),
    ) = SongEntity(
        videoId = videoId,
        title = title,
        duration = "3:30",
        durationSeconds = 210,
        isAvailable = true,
        isExplicit = false,
        likeStatus = if (liked) "LIKE" else "INDIFFERENT",
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = null,
        resultType = null,
        liked = liked,
        favoriteAt = favoriteAt,
        inLibrary = now(),
    )

    private fun createAlbum(
        browseId: String,
        title: String = "Album $browseId",
        liked: Boolean = true,
        favoriteAt: LocalDateTime? = now(),
    ) = AlbumEntity(
        browseId = browseId,
        title = title,
        audioPlaylistId = "OLAK5uy_$browseId",
        description = "Description for $browseId",
        duration = "45:00",
        durationSeconds = 2700,
        thumbnails = "https://example.com/cover-$browseId.jpg",
        trackCount = 10,
        type = "ALBUM",
        year = "2024",
        liked = liked,
        favoriteAt = favoriteAt,
        inLibrary = now(),
    )

    private fun createPlaylist(
        id: String,
        title: String = "Playlist $id",
        liked: Boolean = true,
        favoriteAt: LocalDateTime? = now(),
    ) = PlaylistEntity(
        id = id,
        title = title,
        thumbnails = "https://example.com/playlist-$id.jpg",
        liked = liked,
        favoriteAt = favoriteAt,
        inLibrary = now(),
    )

    private fun createPodcast(
        podcastId: String,
        title: String = "Podcast $podcastId",
        isFavorite: Boolean = true,
        favoriteTime: LocalDateTime? = now(),
    ) = PodcastsEntity(
        podcastId = podcastId,
        title = title,
        authorId = "author_$podcastId",
        authorName = "Author $podcastId",
        authorThumbnail = null,
        description = "Podcast description",
        thumbnail = "https://example.com/podcast-$podcastId.jpg",
        isFavorite = isFavorite,
        favoriteTime = favoriteTime,
        inLibrary = now(),
        listEpisodes = emptyList(),
    )

    private fun createArtist(
        channelId: String,
        name: String = "Artist $channelId",
        followed: Boolean = true,
        followedAt: LocalDateTime? = now(),
    ) = ArtistEntity(
        channelId = channelId,
        name = name,
        thumbnails = "https://example.com/artist-$channelId.jpg",
        followed = followed,
        followedAt = followedAt,
        inLibrary = now(),
    )

    /**
     * Simulates full bidirectional peer-to-peer sync:
     * Android exports -> Windows merges -> Windows exports -> Android merges.
     */
    private suspend fun performBidirectionalSync() {
        val androidBundles = androidSyncStore.exportBundles()
        for (bundle in androidBundles) {
            windowsSyncStore.mergeBundle(bundle)
        }
        val windowsBundles = windowsSyncStore.exportBundles()
        for (bundle in windowsBundles) {
            androidSyncStore.mergeBundle(bundle)
        }
    }

    @Test
    fun existingAndroidFavoritesAppearInWindows() = runBlocking {
        // Arrange: Android has existing favorites of each type
        val song = createSong("song_android_1")
        val album = createAlbum("album_android_1")
        val playlist = createPlaylist("playlist_android_1")
        val podcast = createPodcast("podcast_android_1")
        val artist = createArtist("artist_android_1")

        androidDb.getDatabaseDao().insertSong(song)
        androidDb.getDatabaseDao().updateLiked(1, song.videoId, song.favoriteAt)
        androidDb.getDatabaseDao().insertAlbum(album)
        androidDb.getDatabaseDao().updateAlbumLiked(1, album.browseId, album.favoriteAt)
        androidDb.getDatabaseDao().insertPlaylist(playlist)
        androidDb.getDatabaseDao().updatePlaylistLiked(1, playlist.id, playlist.favoriteAt)
        androidDb.getDatabaseDao().insertPodcast(podcast)
        androidDb.getDatabaseDao().updatePodcastFavorite(true, podcast.podcastId, podcast.favoriteTime)
        androidDb.getDatabaseDao().insertArtist(artist)
        androidDb.getDatabaseDao().updateFollowed(1, artist.channelId, artist.followedAt)

        // Windows currently has none of these
        assertNull(windowsDb.getDatabaseDao().getSong("song_android_1"))
        assertNull(windowsDb.getDatabaseDao().getAlbum("album_android_1"))
        assertNull(windowsDb.getDatabaseDao().getPlaylist("playlist_android_1"))
        assertNull(windowsDb.getDatabaseDao().getPodcast("podcast_android_1"))
        assertNull(windowsDb.getDatabaseDao().getArtist("artist_android_1"))

        // Act: Sync from Android to Windows
        performBidirectionalSync()

        // Assert: Windows now has all Android favorites in its local DB
        val winSong = windowsDb.getDatabaseDao().getSong("song_android_1")
        assertNotNull(winSong)
        assertTrue(winSong.liked)
        assertEquals("Title song_android_1", winSong.title)

        val winAlbum = windowsDb.getDatabaseDao().getAlbum("album_android_1")
        assertNotNull(winAlbum)
        assertTrue(winAlbum.liked)
        assertEquals("Album album_android_1", winAlbum.title)

        val winPlaylist = windowsDb.getDatabaseDao().getPlaylist("playlist_android_1")
        assertNotNull(winPlaylist)
        assertTrue(winPlaylist.liked)

        val winPodcast = windowsDb.getDatabaseDao().getPodcast("podcast_android_1")
        assertNotNull(winPodcast)
        assertTrue(winPodcast.isFavorite)

        val winArtist = windowsDb.getDatabaseDao().getArtist("artist_android_1")
        assertNotNull(winArtist)
        assertTrue(winArtist.followed)
    }

    @Test
    fun windowsFavoritesAppearInAndroid() = runBlocking {
        // Arrange: Windows has existing favorites
        val song = createSong("song_windows_1")
        val album = createAlbum("album_windows_1")

        windowsDb.getDatabaseDao().insertSong(song)
        windowsDb.getDatabaseDao().updateLiked(1, song.videoId, song.favoriteAt)
        windowsDb.getDatabaseDao().insertAlbum(album)
        windowsDb.getDatabaseDao().updateAlbumLiked(1, album.browseId, album.favoriteAt)

        // Act
        performBidirectionalSync()

        // Assert: Android now has Windows favorites
        val androidSong = androidDb.getDatabaseDao().getSong("song_windows_1")
        assertNotNull(androidSong)
        assertTrue(androidSong.liked)

        val androidAlbum = androidDb.getDatabaseDao().getAlbum("album_windows_1")
        assertNotNull(androidAlbum)
        assertTrue(androidAlbum.liked)
    }

    @Test
    fun mergingFavoritesWithoutDuplicates() = runBlocking {
        // Both devices have song A (shared) with different timestamps
        val time100 = 100_000L.toLocalDateTimeUtc()
        val time200 = 200_000L.toLocalDateTimeUtc()

        val songAAndroid = createSong("song_A", favoriteAt = time100)
        val songAWindows = createSong("song_A", favoriteAt = time200)

        // Android has unique song B, Windows has unique song C
        val songB = createSong("song_B", favoriteAt = time100)
        val songC = createSong("song_C", favoriteAt = time100)

        androidDb.getDatabaseDao().insertSong(songAAndroid)
        androidDb.getDatabaseDao().updateLiked(1, songAAndroid.videoId, songAAndroid.favoriteAt)
        androidDb.getDatabaseDao().insertSong(songB)
        androidDb.getDatabaseDao().updateLiked(1, songB.videoId, songB.favoriteAt)

        windowsDb.getDatabaseDao().insertSong(songAWindows)
        windowsDb.getDatabaseDao().updateLiked(1, songAWindows.videoId, songAWindows.favoriteAt)
        windowsDb.getDatabaseDao().insertSong(songC)
        windowsDb.getDatabaseDao().updateLiked(1, songC.videoId, songC.favoriteAt)

        // Act: Sync
        performBidirectionalSync()

        // Assert: Both devices have exactly 3 liked songs: A, B, C
        val androidLiked = androidDb.getDatabaseDao().getAllLikedSongs()
        val windowsLiked = windowsDb.getDatabaseDao().getAllLikedSongs()

        assertEquals(3, androidLiked.size)
        assertEquals(3, windowsLiked.size)

        val androidA = androidLiked.first { it.videoId == "song_A" }
        val windowsA = windowsLiked.first { it.videoId == "song_A" }

        // Newer timestamp (200_000) was preserved on both!
        assertEquals(time200, androidA.favoriteAt)
        assertEquals(time200, windowsA.favoriteAt)
    }

    @Test
    fun addingAFavoriteOnEitherDevice() = runBlocking {
        // Initial state: synced with song 1
        val song1 = createSong("song_1")
        androidDb.getDatabaseDao().insertSong(song1)
        androidDataSource.updateLiked(1, song1.videoId, song1.favoriteAt)
        performBidirectionalSync()

        // Device A adds song 2 offline
        val song2 = createSong("song_2")
        androidDb.getDatabaseDao().insertSong(song2)
        androidDataSource.updateLiked(1, song2.videoId, song2.favoriteAt)

        // Device B adds song 3 offline
        val song3 = createSong("song_3")
        windowsDb.getDatabaseDao().insertSong(song3)
        windowsDataSource.updateLiked(1, song3.videoId, song3.favoriteAt)

        // Reconnect and sync
        performBidirectionalSync()

        // Both devices now have song 1, 2, and 3
        assertEquals(3, androidDb.getDatabaseDao().getAllLikedSongs().size)
        assertEquals(3, windowsDb.getDatabaseDao().getAllLikedSongs().size)
        assertTrue(androidDb.getDatabaseDao().getSong("song_3")!!.liked)
        assertTrue(windowsDb.getDatabaseDao().getSong("song_2")!!.liked)
    }

    @Test
    fun removingAFavoriteOnEitherDevice() = runBlocking {
        // Both devices have song 1 and song 2
        val song1 = createSong("song_1")
        val song2 = createSong("song_2")
        androidDb.getDatabaseDao().insertSong(song1)
        androidDataSource.updateLiked(1, song1.videoId, song1.favoriteAt)
        androidDb.getDatabaseDao().insertSong(song2)
        androidDataSource.updateLiked(1, song2.videoId, song2.favoriteAt)
        performBidirectionalSync()

        // Android unlikes song 1 offline
        androidDataSource.updateLiked(0, song1.videoId)
        assertFalse(androidDb.getDatabaseDao().getSong("song_1")!!.liked)
        assertTrue(windowsDb.getDatabaseDao().getSong("song_1")!!.liked)

        // Sync
        performBidirectionalSync()

        // Song 1 is unliked on Windows too!
        assertFalse(windowsDb.getDatabaseDao().getSong("song_1")!!.liked)
        // Song 2 remains liked on both
        assertTrue(androidDb.getDatabaseDao().getSong("song_2")!!.liked)
        assertTrue(windowsDb.getDatabaseDao().getSong("song_2")!!.liked)
    }

    @Test
    fun preventingDeletedFavoritesFromReappearing() = runBlocking {
        // Both devices have song 1
        val song1 = createSong("song_1", favoriteAt = 100_000L.toLocalDateTimeUtc())
        androidDb.getDatabaseDao().insertSong(song1)
        androidDataSource.updateLiked(1, song1.videoId, song1.favoriteAt)
        performBidirectionalSync()

        // Offline: Android unlikes song 1 at t=200_000
        val t200 = 200_000L
        androidDb.getDatabaseDao().updateLiked(0, song1.videoId, null)
        androidSyncStore.recordChange(FavoriteSyncType.SONG, song1.videoId, isFavorite = false, timestamp = t200)

        // Simulate cache cleanup on Android: row deleted from song table
        androidDataSource.deleteSongsAndRelatedData(listOf("song_1"))
        assertNull(androidDb.getDatabaseDao().getSong("song_1"))

        // Windows still has song 1 liked with old timestamp t=100_000
        assertTrue(windowsDb.getDatabaseDao().getSong("song_1")!!.liked)

        // Sync: Windows sends song 1 (liked, t=100_000)
        // Android must NOT re-insert song 1 because Android's tombstone (t=200_000) is newer!
        performBidirectionalSync()

        // Android still does not have song 1 liked
        val androidSongAfter = androidDb.getDatabaseDao().getSong("song_1")
        assertTrue(androidSongAfter == null || !androidSongAfter.liked)

        // Windows received the tombstone and unliked song 1!
        val windowsSongAfter = windowsDb.getDatabaseDao().getSong("song_1")
        assertNotNull(windowsSongAfter)
        assertFalse(windowsSongAfter.liked)
    }

    @Test
    fun conflictResolutionLastWriteWins() = runBlocking {
        val songId = "conflict_song"
        val song = createSong(songId, favoriteAt = 100_000L.toLocalDateTimeUtc())
        androidDb.getDatabaseDao().insertSong(song)
        androidDataSource.updateLiked(1, songId, song.favoriteAt)
        performBidirectionalSync()

        // Case A: Windows unlikes at t=200_000. But Android re-likes at t=300_000.
        // Android re-like (t=300_000) must WIN over Windows unlike (t=200_000).
        windowsDataSource.updateLiked(0, songId)
        windowsSyncStore.recordChange(FavoriteSyncType.SONG, songId, isFavorite = false, timestamp = 200_000L)

        androidDataSource.updateLiked(1, songId, 300_000L.toLocalDateTimeUtc())
        androidSyncStore.recordChange(FavoriteSyncType.SONG, songId, isFavorite = true, timestamp = 300_000L)

        performBidirectionalSync()

        assertTrue(androidDb.getDatabaseDao().getSong(songId)!!.liked)
        assertTrue(windowsDb.getDatabaseDao().getSong(songId)!!.liked)
        assertEquals(300_000L.toLocalDateTimeUtc(), windowsDb.getDatabaseDao().getSong(songId)!!.favoriteAt)

        // Case B: Windows unlikes at t=400_000 (newer than Android's 300_000).
        // Unlike must WIN!
        windowsDataSource.updateLiked(0, songId)
        windowsSyncStore.recordChange(FavoriteSyncType.SONG, songId, isFavorite = false, timestamp = 400_000L)

        performBidirectionalSync()

        assertFalse(androidDb.getDatabaseDao().getSong(songId)!!.liked)
        assertFalse(windowsDb.getDatabaseDao().getSong(songId)!!.liked)
    }

    @Test
    fun offlineChangesSyncingAfterReconnection() = runBlocking {
        // Android performs multiple offline actions:
        // Likes song A, likes album B, unfollows artist C
        val songA = createSong("offline_song_A")
        androidDb.getDatabaseDao().insertSong(songA)
        androidDataSource.updateLiked(1, songA.videoId, songA.favoriteAt)

        val albumB = createAlbum("offline_album_B")
        androidDb.getDatabaseDao().insertAlbum(albumB)
        androidDataSource.updateAlbumLiked(1, albumB.browseId, albumB.favoriteAt)

        val artistC = createArtist("offline_artist_C", followed = true)
        androidDb.getDatabaseDao().insertArtist(artistC)
        windowsDb.getDatabaseDao().insertArtist(artistC)
        windowsDataSource.updateFollowed(1, artistC.channelId)
        androidDataSource.updateFollowed(0, artistC.channelId)

        // Windows performs offline actions:
        // Likes playlist D, favorites podcast E
        val playlistD = createPlaylist("offline_playlist_D")
        windowsDb.getDatabaseDao().insertPlaylist(playlistD)
        windowsDataSource.updatePlaylistLiked(1, playlistD.id, playlistD.favoriteAt)

        val podcastE = createPodcast("offline_podcast_E")
        windowsDb.getDatabaseDao().insertPodcast(podcastE)
        windowsDataSource.favoritePodcast(podcastE.podcastId, true)

        // Now devices connect and sync
        performBidirectionalSync()

        // Verify all changes synced in both directions
        assertTrue(windowsDb.getDatabaseDao().getSong("offline_song_A")!!.liked)
        assertTrue(windowsDb.getDatabaseDao().getAlbum("offline_album_B")!!.liked)
        assertFalse(windowsDb.getDatabaseDao().getArtist("offline_artist_C")!!.followed)

        assertTrue(androidDb.getDatabaseDao().getPlaylist("offline_playlist_D")!!.liked)
        assertTrue(androidDb.getDatabaseDao().getPodcast("offline_podcast_E")!!.isFavorite)
    }

    @Test
    fun interruptedSyncAndRetryIdempotency() = runBlocking {
        // Setup initial favorites on Android
        (1..10).forEach { i ->
            val s = createSong("song_$i")
            androidDb.getDatabaseDao().insertSong(s)
            androidDataSource.updateLiked(1, s.videoId, s.favoriteAt)
        }

        // Run sync once
        performBidirectionalSync()
        assertEquals(10, windowsDb.getDatabaseDao().getAllLikedSongs().size)

        // Run sync second time (idempotency check)
        val result = windowsSyncStore.mergeBundle(androidSyncStore.exportBundle())
        // No new items added or removed
        assertEquals(0, result.totalAddedOrUpdated)
        assertEquals(0, result.totalRemoved)
        assertEquals(10, windowsDb.getDatabaseDao().getAllLikedSongs().size)
    }

    @Test
    fun largeFavoriteCollectionsBatching() = runBlocking {
        // 1,200 songs on Android
        val totalSongs = 1200
        for (i in 1..totalSongs) {
            val song = createSong("bulk_song_$i")
            androidDb.getDatabaseDao().insertSong(song)
            androidDb.getDatabaseDao().updateLiked(1, song.videoId, song.favoriteAt)
        }
        assertEquals(totalSongs, androidDb.getDatabaseDao().getAllLikedSongs().size)

        // Export in chunks of 250 items
        val bundles = androidSyncStore.exportBundles(chunkSize = 250)
        assertTrue(bundles.size >= 4, "Should be split into at least 4 batches, was ${bundles.size}")

        // Process all batches on Windows
        for (bundle in bundles) {
            windowsSyncStore.mergeBundle(bundle)
        }

        assertEquals(totalSongs, windowsDb.getDatabaseDao().getAllLikedSongs().size)
    }

    @Test
    fun favoritesPreservedAfterLogoutAndRestart() = runBlocking {
        // Add favorite song
        val song = createSong("preserved_song")
        androidDb.getDatabaseDao().insertSong(song)
        androidDataSource.updateLiked(1, song.videoId, song.favoriteAt)

        // Simulate logout: clear Google accounts
        val account = com.maxrave.domain.data.entities.GoogleAccountEntity(
            email = "user@example.com",
            name = "Test User",
            thumbnailUrl = "thumb",
            authUser = 0,
            isUsed = true,
        )
        androidDb.getDatabaseDao().insertGoogleAccount(account)
        assertEquals(1, androidDb.getDatabaseDao().getAllGoogleAccount().size)
        androidDb.getDatabaseDao().deleteGoogleAccount("user@example.com")
        assertEquals(0, androidDb.getDatabaseDao().getAllGoogleAccount().size)

        // Verify favorite still exists
        assertTrue(androidDb.getDatabaseDao().getSong("preserved_song")!!.liked)

        // Close and reopen database (simulate restart)
        val dbFile = File(tempDir, "android.db")
        androidDb.close()

        val reopenedDb = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()
        try {
            val reopenedSong = reopenedDb.getDatabaseDao().getSong("preserved_song")
            assertNotNull(reopenedSong)
            assertTrue(reopenedSong.liked)
        } finally {
            reopenedDb.close()
            // Re-open for tearDown
            androidDb = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addTypeConverter(Converters())
                .build()
        }
    }
}
