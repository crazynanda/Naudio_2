package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.response.LibraryPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The import of the liked songs of the signed-in account.
 *
 * The account is a new one: YouTube serves a fresh library in a different shape than the library
 * of an account that has been around, and the import read one fixed place of the answer. The
 * answer was then empty, and an empty answer is an account without a single liked song - so the
 * Favorites list stayed empty while YouTube showed the likes.
 */
class RemoteLikedSongsTest {
    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var localDataSource: LocalDataSource

    @BeforeTest
    fun setUp() {
        runBlocking {
            tempDir = Files.createTempDirectory("remote-liked-songs-test").toFile()
            database =
                Room
                    .databaseBuilder<MusicDatabase>(File(tempDir, "test.db").absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addTypeConverter(Converters())
                    .build()
            localDataSource = LocalDataSource(database.getDatabaseDao(), database)
        }
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private suspend fun setGoogleAccount(email: String = "newbie@gmail.com") {
        localDataSource.insertGoogleAccount(
            GoogleAccountEntity(
                email = email,
                name = "New Account",
                thumbnailUrl = "",
                isUsed = true,
                cache = "cookie",
                authUser = 0,
                pageId = null,
            ),
        )
    }

    private fun renderer(videoId: String): MusicResponsiveListItemRenderer =
        MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = null,
            flexColumns =
                listOf(
                    MusicResponsiveListItemRenderer.FlexColumn(
                        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                            Runs(listOf(Run("Song $videoId", null))),
                        ),
                    ),
                ),
            thumbnail =
                ThumbnailRenderer(
                    musicThumbnailRenderer =
                        ThumbnailRenderer.MusicThumbnailRenderer(
                            thumbnail = Thumbnails(listOf(Thumbnail("https://i.test/$videoId.jpg", 120, 120))),
                            thumbnailCrop = null,
                            thumbnailScale = null,
                        ),
                    musicAnimatedThumbnailRenderer = null,
                    croppedSquareThumbnailRenderer = null,
                ),
            menu = null,
            playlistItemData =
                MusicResponsiveListItemRenderer.PlaylistItemData(
                    playlistSetVideoId = null,
                    videoId = videoId,
                ),
            overlay = null,
            navigationEndpoint = null,
        )

    /**
     * A library that answers in the shape of a fresh account: the songs sit in a
     * `musicShelfRenderer`, and the page opens with another section.
     */
    private class FakeLibraryYouTube : YouTube() {
        /** The pages, as (rows, next token). */
        var pages: List<Pair<List<MusicResponsiveListItemRenderer>, String?>> = emptyList()
        /** A page that asks for a sign-in. */
        var signInRequired = false
        /** The browse ids that were asked for. */
        val requestedBrowseIds: MutableList<String?> = mutableListOf()

        override suspend fun fetchLibraryPage(
            browseId: String?,
            continuation: String?,
            customCookie: String?,
        ): Result<LibraryPage> {
            requestedBrowseIds.add(browseId)
            if (signInRequired) {
                return Result.success(
                    LibraryPage(
                        httpStatus = 200,
                        rows = emptyList(),
                        continuations = emptyList(),
                        shelfCount = 0,
                        signInRequired = true,
                        shape = "",
                    ),
                )
            }
            val index =
                if (continuation == null) {
                    0
                } else {
                    continuation.removePrefix("token_").toIntOrNull() ?: 0
                }
            val (rows, nextToken) = pages.getOrNull(index) ?: (emptyList<MusicResponsiveListItemRenderer>() to null)
            return Result.success(
                LibraryPage(
                    httpStatus = 200,
                    rows = rows,
                    continuations = listOfNotNull(nextToken),
                    shelfCount = 1,
                    signInRequired = false,
                    shape = "",
                ),
            )
        }
    }

    private fun repository(
        youTube: YouTube,
        combineLocalAndYouTubeLiked: String = DataStoreManager.TRUE,
    ): SongRepositoryImpl {
        val dataStore =
            TestDataStoreManager(
                loggedInVal = DataStoreManager.TRUE,
                cookieVal = "cookie_abc",
                combineLocalAndYouTubeLikedVal = combineLocalAndYouTubeLiked,
            ).create()
        return SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = youTube,
        )
    }

    @Test
    fun importsTheSongsOfAFreshAccountLibrary() = runBlocking {
        setGoogleAccount()
        val youTube =
            FakeLibraryYouTube().apply {
                pages = listOf(listOf(renderer("song_1"), renderer("song_2")) to null)
            }
        val repo = repository(youTube)

        val result = repo.syncAccountFavorites()

        assertTrue(result.isSuccess, "The sync must succeed")
        assertEquals(2, result.getOrThrow().songsAddedOrUpdated)
        val liked = localDataSource.getAllLikedSongs()
        assertEquals(setOf("song_1", "song_2"), liked.map { it.videoId }.toSet())
        assertTrue(liked.all { it.liked })
    }

    @Test
    fun importsEveryPageOfTheLibrary() = runBlocking {
        setGoogleAccount()
        val youTube =
            FakeLibraryYouTube().apply {
                pages =
                    listOf(
                        listOf(renderer("page_1")) to "token_1",
                        listOf(renderer("page_2")) to "token_2",
                        listOf(renderer("page_3")) to null,
                    )
            }
        val repo = repository(youTube)

        val result = repo.syncAccountFavorites()

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow().songsAddedOrUpdated)
        assertEquals(
            setOf("page_1", "page_2", "page_3"),
            localDataSource.getAllLikedSongs().map { it.videoId }.toSet(),
        )
    }

    @Test
    fun anEmptyLibraryIsNotAFailure() = runBlocking {
        setGoogleAccount()
        val youTube = FakeLibraryYouTube().apply { pages = listOf(emptyList<MusicResponsiveListItemRenderer>() to null) }
        val repo = repository(youTube)

        val result = repo.syncAccountFavorites()

        assertTrue(result.isSuccess, "An empty library is a library without songs, not a failed sync")
        assertEquals(0, localDataSource.getAllLikedSongs().size)
    }

    @Test
    fun aLibraryThatAsksForASignInIsAFailureAndKeepsTheLocalFavorites() = runBlocking {
        setGoogleAccount()
        // A favorite that is in the app only.
        database.getDatabaseDao().insertSong(
            SongEntity(
                videoId = "local_only",
                title = "Local Only",
                duration = "3:30",
                durationSeconds = 210,
                isAvailable = true,
                isExplicit = false,
                likeStatus = "LIKE",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                category = null,
                resultType = null,
                liked = true,
                downloadState = DownloadState.STATE_NOT_DOWNLOADED,
            ),
        )
        val youTube = FakeLibraryYouTube().apply { signInRequired = true }
        val repo = repository(youTube)

        val result = repo.syncAccountFavorites()

        assertTrue(result.isFailure, "A page that asks for a sign-in is a failed read")
        // The favorite of the user is still there: a library that could not be read may not take
        // the local favorites away.
        assertEquals(
            listOf("local_only"),
            localDataSource.getAllLikedSongs().map { it.videoId },
        )
    }

    @Test
    fun asksEveryKnownPlaceOfTheLibrary() = runBlocking {
        setGoogleAccount()
        val youTube =
            FakeLibraryYouTube().apply {
                pages = listOf(listOf(renderer("song_1")) to null)
            }
        val repo = repository(youTube)

        repo.syncAccountFavorites()

        // The library section is asked for first.
        assertTrue(
            youTube.requestedBrowseIds.contains("FEmusic_liked_videos"),
            "The library section of the liked songs must be asked for, got ${youTube.requestedBrowseIds}",
        )
    }

    private class TestDataStoreManager(
        var combineLocalAndYouTubeLikedVal: String = DataStoreManager.FALSE,
        var loggedInVal: String = DataStoreManager.FALSE,
        var cookieVal: String = "",
        val stringMap: MutableMap<String, String> = mutableMapOf(),
    ) {
        fun create(): DataStoreManager {
            val handler =
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getCombineLocalAndYouTubeLiked" -> flowOf(combineLocalAndYouTubeLikedVal)
                        "getLoggedIn" -> flowOf(loggedInVal)
                        "getCookie" -> flowOf(cookieVal)
                        "getPageId" -> flowOf("")
                        "getAuthUser" -> flowOf("0")
                        "setCombineLocalAndYouTubeLiked" -> {
                            combineLocalAndYouTubeLikedVal =
                                if (args?.get(0) as? Boolean == true) DataStoreManager.TRUE else DataStoreManager.FALSE
                            stringMap[DataStoreManager.KEY_COMBINE_LOCAL_AND_YOUTUBE_LIKED] = combineLocalAndYouTubeLikedVal
                            Unit
                        }

                        "setLoggedIn" -> {
                            loggedInVal = if (args?.get(0) as? Boolean == true) DataStoreManager.TRUE else DataStoreManager.FALSE
                            Unit
                        }

                        "setCookie" -> {
                            cookieVal = args?.get(0) as? String ?: ""
                            Unit
                        }

                        "putString" -> {
                            stringMap[args?.get(0) as? String ?: ""] = args?.get(1) as? String ?: ""
                            Unit
                        }

                        "getString" -> flowOf(stringMap[args?.get(0) as? String ?: ""])
                        "toString" -> "TestDataStoreManager"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> args?.get(0) === this
                        else -> {
                            if (method.returnType == Flow::class.java) {
                                flowOf("")
                            } else if (method.returnType == java.lang.Boolean.TYPE) {
                                false
                            } else if (method.returnType == java.lang.Integer.TYPE) {
                                0
                            } else {
                                null
                            }
                        }
                    }
                }
            return Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
                handler,
            ) as DataStoreManager
        }
    }
}
