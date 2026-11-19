package com.maxrave.simpmusic.ui.screen.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.maxrave.common.Config
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.ui.PlatformWebView
import com.maxrave.simpmusic.expect.ui.createWebViewCookieManager
import com.maxrave.simpmusic.expect.ui.rememberWebViewState
import com.maxrave.simpmusic.extension.ultraThinBarStyle
import com.maxrave.simpmusic.ui.component.DevLogInBottomSheet
import com.maxrave.simpmusic.ui.component.DevLogInType
import com.maxrave.simpmusic.ui.component.RippleIconButton
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.LogoDev
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.LogInViewModel
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.log_in
import simpmusic.composeapp.generated.resources.login_failed
import simpmusic.composeapp.generated.resources.login_success

/** How often the YouTube cookie jar is checked while the sign-in page is open. */
private const val LOGIN_POLL_INTERVAL_MS = 500L

/** Pause between two attempts on the same cookie. */
private const val LOGIN_RETRY_DELAY_MS = 4_000L

/** How many times the same cookie is tried before it is given up on. */
private const val LOGIN_MAX_ATTEMPTS = 2

/** Pause before a cookie that failed [LOGIN_MAX_ATTEMPTS] times is tried once more. */
private const val LOGIN_EXHAUSTED_RETRY_DELAY_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    innerPadding: PaddingValues,
    navController: NavController,
    viewModel: LogInViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
    hideBottomNavigation: () -> Unit,
    showBottomNavigation: () -> Unit,
) {
    val hazeState = rememberHazeState()
    val coroutineScope = rememberCoroutineScope()
    var devLoginSheet by rememberSaveable {
        mutableStateOf(false)
    }
    // The URL the embedded page is currently showing. The sign-in completion poll reads it instead
    // of acting on the page callback directly, so a navigation that lands while an account is
    // being added is never dropped.
    var currentUrl by remember { mutableStateOf("") }

    val state = rememberWebViewState()

    LaunchedEffect(state) {
        snapshotFlow { state.value }.collect {
            Logger.d(
                "LogInScreen",
                "WebViewState: ${
                    when (it) {
                        is com.maxrave.simpmusic.expect.ui.WebViewState.Finished -> "Finished"
                        is com.maxrave.simpmusic.expect.ui.WebViewState.Loading -> "Loading ${it.progress}%"
                    }
                }",
            )
        }
    }

    // Hide bottom navigation when entering this screen
    LaunchedEffect(Unit) {
        hideBottomNavigation()
    }

    // Show bottom navigation when leaving this screen
    DisposableEffect(Unit) {
        onDispose {
            showBottomNavigation()
        }
    }

    // Sign-in completion.
    //
    // Two rules, both learned the hard way by the version that reported "Login failed" while the
    // user was still on the Google password screen:
    //
    // 1. The page must be on YouTube. The Google sign-in page carries "music.youtube.com" inside
    //    its percent encoded "continue" parameter, so matching the whole URL as a substring
    //    matched the sign-in page itself and started the cookie check before anything was signed
    //    in. Only the host decides now.
    // 2. The cookie must come from the YouTube cookie jar. The Google account pages set their own
    //    SAPISID and SID on google.com, which look exactly like a finished sign-in but are not one:
    //    they sent YouTube a cookie it cannot use, so the account was never added and the screen
    //    reported a failure for a sign-in that was still running.
    //
    // The cookie is read once the page is on YouTube, so a sign-in that is still on the Google
    // pages costs nothing but a URL comparison.
    LaunchedEffect(Unit) {
        val cookieManager = createWebViewCookieManager()
        val timeSource = TimeSource.Monotonic
        var attemptedCookie: String? = null
        var attempts = 0
        var lastAttempt = timeSource.markNow()
        var failureReportedFor: String? = null

        while (isActive) {
            delay(LOGIN_POLL_INTERVAL_MS)

            val pageUrl = currentUrl
            if (!isYouTubeUrl(pageUrl)) continue
            // Nothing new can come out of the same cookie for a while: do not even read it.
            if (attempts >= LOGIN_MAX_ATTEMPTS && lastAttempt.elapsedNow().inWholeMilliseconds < LOGIN_EXHAUSTED_RETRY_DELAY_MS) continue

            val cookie = cookieManager.getCookie(Config.YOUTUBE_MUSIC_MAIN_URL)
            if (!shouldCompleteYouTubeLogin(pageUrl, cookie)) continue

            if (cookie != attemptedCookie) {
                attemptedCookie = cookie
                attempts = 0
            }
            if (attempts >= LOGIN_MAX_ATTEMPTS) {
                // A whole round on this cookie failed. Start a new round later on, in case the
                // failure was a dropped request rather than a rejected cookie.
                attempts = 0
            } else if (attempts > 0 && lastAttempt.elapsedNow().inWholeMilliseconds < LOGIN_RETRY_DELAY_MS) {
                continue
            }

            attempts++
            lastAttempt = timeSource.markNow()
            Logger.i("LogInScreen", "YouTube sign-in cookies detected on $pageUrl, adding account")
            val success = settingsViewModel.addAccount(cookie)
            if (success) {
                viewModel.makeToast(getString(Res.string.login_success))
                navController.navigateUp()
                break
            }
            if (attempts >= LOGIN_MAX_ATTEMPTS && failureReportedFor != cookie) {
                failureReportedFor = cookie
                viewModel.makeToast(getString(Res.string.login_failed))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().hazeSource(state = hazeState)) {
        Column {
            Spacer(
                Modifier
                    .size(
                        innerPadding.calculateTopPadding() + 64.dp,
                    ),
            )
            // WebView for YouTube Music login
            PlatformWebView(
                state,
                Config.LOG_IN_URL,
                aboveContent = {
                    if (devLoginSheet) {
                        DevLogInBottomSheet(
                            onDismiss = {
                                devLoginSheet = false
                            },
                            onDone = { cookie ->
                                coroutineScope.launch {
                                    val success = settingsViewModel.addAccount(cookie)
                                    if (success) {
                                        viewModel.makeToast(getString(Res.string.login_success))
                                        navController.navigateUp()
                                    } else {
                                        viewModel.makeToast(getString(Res.string.login_failed))
                                    }
                                }
                            },
                            type = DevLogInType.YouTube,
                        )
                    }
                },
                clearCookies = true,
            ) { url ->
                Logger.d("LogInScreen", "Current URL: $url")
                currentUrl = url
            }
        }

        // Top App Bar with haze effect
        TopAppBar(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .hazeBlur(HazeInput.Sources(hazeState), ultraThinBarStyle()),
            title = {
                Text(
                    text = stringResource(Res.string.log_in),
                    style = typo().titleMedium,
                )
            },
            navigationIcon = {
                Box(Modifier.padding(horizontal = 5.dp)) {
                    RippleIconButton(
                        SimpIcons.ArrowBackIosNew,
                        Modifier.size(32.dp),
                        true,
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        navController.navigateUp()
                    }
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        devLoginSheet = true
                    },
                ) {
                    Icon(
                        SimpIcons.LogoDev,
                        "Developer Mode",
                    )
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
        )
    }
}

/**
 * The host of [url], or null when it is not an absolute http(s) URL.
 *
 * Parsed by hand because commonMain has no java.net. The login flow needs the host and not the
 * whole URL: the Google sign-in page carries "music.youtube.com" inside its percent encoded
 * "continue" parameter, so matching the whole URL as a substring matches the sign-in page itself.
 */
internal fun hostOfUrl(url: String): String? {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null

    var authority = trimmed.substring(schemeEnd + 3)
    val authorityEnd = authority.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (authorityEnd >= 0) authority = authority.substring(0, authorityEnd)
    val userInfoEnd = authority.lastIndexOf('@')
    if (userInfoEnd >= 0) authority = authority.substring(userInfoEnd + 1)
    val portStart = authority.indexOf(':')
    if (portStart >= 0) authority = authority.substring(0, portStart)

    return authority.lowercase().takeIf { it.isNotEmpty() }
}

/**
 * Whether [url] is a YouTube page, which is where a sign-in ends: the Google pages are where it
 * starts.
 */
internal fun isYouTubeUrl(url: String): Boolean {
    val host = hostOfUrl(url) ?: return false
    return host == "youtube.com" || host.endsWith(".youtube.com")
}

/**
 * Whether a sign-in can be completed for [pageUrl] with the cookies of the YouTube cookie jar.
 */
internal fun shouldCompleteYouTubeLogin(
    pageUrl: String,
    youTubeCookie: String,
): Boolean = isYouTubeUrl(pageUrl) && hasYouTubeAuthCookies(youTubeCookie)

internal fun hasYouTubeAuthCookies(cookie: String): Boolean {
    if (cookie.isBlank()) return false
    val keys = cookie.split(';')
        .asSequence()
        .map { it.trim() }
        .filter { it.contains('=') }
        .map { it.substringBefore('=').trim() }
        .toSet()

    val hasSapisid = keys.contains("SAPISID") ||
        keys.contains("__Secure-3PAPISID") ||
        keys.contains("__Secure-1PAPISID")

    val hasSession = keys.contains("SID") ||
        keys.contains("__Secure-3PSID") ||
        keys.contains("LOGIN_INFO")

    return hasSapisid && hasSession
}
