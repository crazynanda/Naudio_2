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
import com.maxrave.domain.data.model.account.YouTubeAccountProbe
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
import com.maxrave.simpmusic.viewModel.YouTubeLoginOutcome
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

/** Shortest pause between two requests to YouTube. */
private const val LOGIN_PROBE_MIN_INTERVAL_MS = 1_500L

/** Pause before the same session is asked about again without having changed. */
private const val LOGIN_PROBE_INTERVAL_MS = 10_000L

/**
 * How long YouTube may answer "no account" for a session that keeps standing still before the
 * sign-in is called a failure.
 *
 * The answer arrives while the session is still being handed over: the cookies are in the jar
 * before YouTube accepts them. Reporting that answer as a failure is what ended a working
 * sign-in early, again and again.
 */
private const val LOGIN_NO_ACCOUNT_TIMEOUT_MS = 20_000L

/** The tag every stage of the sign-in is written under. */
private const val TAG_LOGIN = "Login"

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
    // The host the embedded page is currently showing. Only the host is kept: the query of the
    // Google sign-in pages carries the session handoff.
    var currentHost by remember { mutableStateOf("") }

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
    // The web page the WebView shows is not the signal that the sign-in finished. Every proxy for
    // it failed at some point: the URL matched the Google sign-in page itself (it carries
    // "music.youtube.com" inside its percent encoded "continue" parameter), the page stayed on
    // Google while the session was already handed over, and the cookie names were read from the
    // Google pages, where Google sets a SAPISID and a SID of its own.
    //
    // The signal is YouTube itself: the account switcher names the account a cookie belongs to, and
    // only that answer can say whether there is a session to register.
    //
    // So the jar is watched for a session, and a session is checked against YouTube. A session that
    // is not there yet is waited for, and every stage is written to the app log, because a
    // sign-in that does not finish has to say where it stopped.
    LaunchedEffect(Unit) {
        val cookieManager = createWebViewCookieManager()
        val timeSource = TimeSource.Monotonic
        var lastProbedCookie: String? = null
        var lastProbeAt = timeSource.markNow()
        var rejectionReported = false
        var waitingLogged = false
        var noAccountSinceMs: Long? = null

        while (isActive) {
            delay(LOGIN_POLL_INTERVAL_MS)

            val cookie = cookieManager.getCookie(Config.YOUTUBE_MUSIC_MAIN_URL)
            if (!hasYouTubeAuthCookies(cookie)) {
                // No session in the jar yet: the sign-in has not handed one over.
                if (!waitingLogged && cookie.isNotBlank()) {
                    waitingLogged = true
                    Logger.i(TAG_LOGIN, "Waiting for a YouTube session: the jar holds no session cookie yet")
                }
                continue
            }
            if (waitingLogged) {
                waitingLogged = false
                // The names only. The values of these cookies are the session.
                Logger.i(TAG_LOGIN, "The jar holds a session cookie (${sessionCookieNames(cookie).joinToString()})")
            }

            val changed = cookie != lastProbedCookie
            val elapsed = lastProbeAt.elapsedNow().inWholeMilliseconds
            if (!changed && elapsed < LOGIN_PROBE_INTERVAL_MS) continue
            if (changed && elapsed < LOGIN_PROBE_MIN_INTERVAL_MS) continue

            lastProbedCookie = cookie
            lastProbeAt = timeSource.markNow()
            // A session that changed is a new session: the clock of the rejection starts over.
            if (changed) noAccountSinceMs = null

            when (val probe = settingsViewModel.probeYouTubeAccount(cookie)) {
                is YouTubeAccountProbe.Found -> {
                    Logger.i(TAG_LOGIN, "YouTube named ${probe.accounts.size} account(s): registering")
                    val outcome = settingsViewModel.registerYouTubeAccount(cookie, probe.accounts)
                    if (outcome is YouTubeLoginOutcome.Success) {
                        viewModel.makeToast(getString(Res.string.login_success))
                        navController.navigateUp()
                        break
                    }
                    Logger.w(TAG_LOGIN, "The account could not be registered: ${outcome.describe()}")
                }

                is YouTubeAccountProbe.NoAccount -> {
                    // YouTube answered and named no account. That answer is not a failure yet: the
                    // cookies are in the jar before YouTube accepts them, and a session that is
                    // still being handed over is answered this way.
                    Logger.w(
                        TAG_LOGIN,
                        "YouTube named no account for the session (HTTP ${probe.httpStatus}, code ${probe.code ?: "none"}, page ${currentHost})",
                    )
                    val nowMs = timeSource.markNow().elapsedNow().inWholeMilliseconds
                    val rejectedForMs = noAccountSinceMs?.let { nowMs - it }
                    if (rejectedForMs == null) {
                        noAccountSinceMs = nowMs
                    } else if (rejectedForMs >= LOGIN_NO_ACCOUNT_TIMEOUT_MS && !rejectionReported) {
                        // The session stands still and YouTube keeps naming no account: this
                        // sign-in is not going to finish.
                        rejectionReported = true
                        Logger.w(TAG_LOGIN, "YouTube named no account for $rejectedForMs ms: giving up")
                        viewModel.makeToast(getString(Res.string.login_failed))
                    }
                }

                is YouTubeAccountProbe.Failed -> {
                    // A dropped request is not a failed sign-in: keep waiting for the next one.
                    Logger.w(TAG_LOGIN, "The account request did not get an answer: ${probe.reason} (page ${currentHost})")
                }
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
                // Only the host is logged: the query of the Google sign-in pages carries the
                // session handoff, and the log is read off the device.
                Logger.d(TAG_LOGIN, "The login page moved to ${hostOfUrl(url) ?: "an unknown host"}")
                currentHost = hostOfUrl(url) ?: ""
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
 * The names of the cookies of a cookie string.
 *
 * Only the names are ever read: the values are the session itself.
 */
internal fun cookieNames(cookie: String): Set<String> =
    cookie
        .split(';')
        .asSequence()
        .map { it.trim() }
        .filter { it.contains('=') }
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * The names of the cookies that carry the YouTube session.
 */
internal fun sessionCookieNames(cookie: String): Set<String> {
    val keys = cookieNames(cookie)
    return keys.filter { it in SESSION_COOKIE_NAMES }.toSet()
}

/**
 * Whether [cookie] can be a YouTube session.
 *
 * The names are the only thing that can be read from a cookie string without holding the session:
 * a SAPISID for the hash and one of the session ids beside it.
 */
internal fun hasYouTubeAuthCookies(cookie: String): Boolean {
    if (cookie.isBlank()) return false
    val keys = cookieNames(cookie)

    val hasSapisid = keys.contains("SAPISID") ||
        keys.contains("__Secure-3PAPISID") ||
        keys.contains("__Secure-1PAPISID")

    val hasSession = keys.contains("SID") ||
        keys.contains("__Secure-3PSID") ||
        keys.contains("LOGIN_INFO")

    return hasSapisid && hasSession
}

/** The cookie names that carry a YouTube session. */
private val SESSION_COOKIE_NAMES =
    setOf(
        "SAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PAPISID",
        "SID",
        "__Secure-3PSID",
        "__Secure-1PSID",
        "LOGIN_INFO",
    )
