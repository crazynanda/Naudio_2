package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.Menu
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.PlaylistBrowsePage
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * The page of a YouTube playlist, and the liked music pinned in the Library above all.
 *
 * The page was read by decoding the whole answer into the `BrowseResponse` model. The model names
 * fields as required, and one missing field of one header failed the whole answer - so the page
 * was lost, the Library tile fell back to a playlist row the liked music never had, and the
 * screen answered "Empty response". The page is now read as its JSON when the typed read cannot
 * use its answer, and a page whose header did not decode is still the playlist: its tracks are
 * there, and the name comes from the row the id is pinned to.
 */
class PlaylistPageFallbackTest {
    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var localDataSource: LocalDataSource

    @BeforeTest
    fun setUp() {
        runBlocking {
            tempDir = Files.createTempDirectory("playlist-fallback-test").toFile()
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

    private fun detailHeader(title: String): BrowseResponse.Header.MusicDetailHeaderRenderer =
        BrowseResponse.Header.MusicDetailHeaderRenderer(
            title = Runs(listOf(Run(title, null))),
            subtitle = Runs(listOf(Run("Playlist", null))),
            secondSubtitle = Runs(listOf(Run("2 songs", null))),
            description = null,
            thumbnail =
                ThumbnailRenderer(
                    musicThumbnailRenderer =
                        ThumbnailRenderer.MusicThumbnailRenderer(
                            thumbnail = Thumbnails(listOf(Thumbnail("https://i.test/cover.jpg", 544, 544))),
                            thumbnailCrop = null,
                            thumbnailScale = null,
                        ),
                    musicAnimatedThumbnailRenderer = null,
                    croppedSquareThumbnailRenderer = null,
                ),
            menu = Menu(Menu.MenuRenderer(items = emptyList(), topLevelButtons = null)),
        )

    /** The typed read of the answer that cannot be used, and the page as its JSON beside it. */
    private class FakePlaylistYouTube : YouTube() {
        /** Whether the typed read of the answer fails outright. */
        var answerFails = false
        /** Whether the typed read answers with a page that has no header in it. */
        var answerWithoutHeader = false
        /** The page as its JSON, ready when the fallback asks for it. */
        var rawPage: PlaylistBrowsePage? = null

        /** The browse ids the fallback asked for. */
        val requestedBrowseIds = mutableListOf<String?>()
        /** The cookies the fallback asked with. */
        val requestedCookies = mutableListOf<String?>()

        override suspend fun customQuery(
            browseId: String?,
            params: String?,
            continuation: String?,
            country: String?,
            setLogin: Boolean,
        ): Result<BrowseResponse> =
            if (answerFails) {
                Result.failure(IllegalStateException("the answer does not fit the model"))
            } else {
                Result.success(
                    BrowseResponse(
                        contents = null,
                        continuationContents = null,
                        header = null,
                        microformat = null,
                        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
                        background = null,
                        onResponseReceivedActions = null,
                    ),
                )
            }

        override suspend fun fetchPlaylistPage(
            browseId: String?,
            customCookie: String?,
        ): Result<PlaylistBrowsePage> {
            requestedBrowseIds.add(browseId)
            requestedCookies.add(customCookie)
            return rawPage?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("the page is not there"))
        }
    }

    private class TestDataStoreManager(
        var cookieVal: String = "",
        val stringMap: MutableMap<String, String> = mutableMapOf(),
    ) {
        fun create(): DataStoreManager {
            val handler =
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getCookie" -> flowOf(cookieVal)
                        "getLoggedIn" -> flowOf(DataStoreManager.TRUE)
                        "getString" -> flowOf(stringMap[args?.get(0) as? String ?: ""])
                        "putString" -> {
                            stringMap[args?.get(0) as? String ?: ""] = args?.get(1) as? String ?: ""
                            Unit
                        }
                        "toString" -> "TestDataStoreManager"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> args?.get(0) === this
                        else -> {
                            if (method.returnType == Flow::class.java) {
                                flowOf("")
                            } else {
                                null
                            }
                        }
                    }
                }
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
                handler,
            ) as DataStoreManager
        }
    }

    private fun repository(youTube: YouTube): PlaylistRepositoryImpl =
        PlaylistRepositoryImpl(
            dataStoreManager = TestDataStoreManager(cookieVal = "cookie_abc").create(),
            localDataSource = localDataSource,
            youTube = youTube,
        )

    @Test
    fun thePageOfTheLikedMusicIsReadWhenTheAnswerDoesNotFitTheModel() = runBlocking {
        val youTube =
            FakePlaylistYouTube().apply {
                answerFails = true
                rawPage =
                    PlaylistBrowsePage(
                        httpStatus = 200,
                        header = detailHeader("Liked songs"),
                        rows = listOf(renderer("song_1"), renderer("song_2")),
                        continuations = listOf("token-2"),
                        signInRequired = false,
                        shape = "",
                    )
            }

        val res = repository(youTube).getPlaylistData("LM", "views").first()

        assertTrue(res is Resource.Success)
        val (browse, continuation) = res.data ?: error("no data")
        assertEquals("Liked songs", browse.title)
        assertEquals(listOf("song_1", "song_2"), browse.tracks.map { it.videoId })
        assertEquals("token-2", continuation)
        // The page was asked as the playlist of the session, and with the cookie of the account.
        assertEquals(listOf<String?>("VLLM"), youTube.requestedBrowseIds)
        assertEquals(listOf<String?>("cookie_abc"), youTube.requestedCookies)
    }

    @Test
    fun aPageWithoutAHeaderIsStillThePlaylist() = runBlocking {
        localDataSource.insertPlaylist(
            PlaylistEntity(
                id = "LM",
                title = "Liked Music",
                author = "YouTube Music",
                description = "PIN",
                thumbnails = "https://www.gstatic.com/youtube/media/ytm/images/pbg/liked-songs-delhi-1200.png",
            ),
        )
        val youTube =
            FakePlaylistYouTube().apply {
                answerFails = true
                rawPage =
                    PlaylistBrowsePage(
                        httpStatus = 200,
                        header = null,
                        rows = listOf(renderer("song_1")),
                        continuations = emptyList(),
                        signInRequired = false,
                        shape = "",
                    )
            }

        val res = repository(youTube).getPlaylistData("LM", "views").first()

        assertTrue(res is Resource.Success)
        val (browse, _) = res.data ?: error("no data")
        // The header was not read; the tracks are there and the name comes from the pinned row.
        assertEquals("Liked Music", browse.title)
        assertEquals(listOf("song_1"), browse.tracks.map { it.videoId })
    }

    @Test
    fun anAnswerTheTypedReadCannotUseIsRetriedAsItsJson() = runBlocking {
        // The typed read answers with a page that has no header in it: parsePlaylistData needs
        // the header, so the first read gives nothing and the page is asked as its JSON.
        val youTube =
            FakePlaylistYouTube().apply {
                answerWithoutHeader = true
                rawPage =
                    PlaylistBrowsePage(
                        httpStatus = 200,
                        header = detailHeader("Liked songs"),
                        rows = listOf(renderer("song_1")),
                        continuations = emptyList(),
                        signInRequired = false,
                        shape = "",
                    )
            }

        val res = repository(youTube).getPlaylistData("LM", "views").first()

        assertTrue(res is Resource.Success)
        assertEquals(listOf<String?>("VLLM"), youTube.requestedBrowseIds)
        assertEquals("Liked songs", res.data?.first?.title)
    }

    @Test
    fun whenBothReadsFailTheErrorNamesTheStage() = runBlocking {
        val youTube =
            FakePlaylistYouTube().apply {
                answerFails = true
            }

        val res = repository(youTube).getPlaylistData("LM", "views").first()

        assertTrue(res is Resource.Error)
        assertEquals("the second read failed: the page is not there", res.message)
    }
}
