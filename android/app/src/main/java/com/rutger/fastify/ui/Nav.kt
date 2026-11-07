package com.rutger.fastify.ui

import android.Manifest
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import com.rutger.fastify.ui.screens.ArtistScreen
import com.rutger.fastify.ui.screens.CollectionScreen
import com.rutger.fastify.ui.screens.DiagnosticsScreen
import com.rutger.fastify.ui.screens.ShazamScreen
import com.rutger.fastify.ui.screens.ShazamViewModel
import com.rutger.fastify.ui.screens.StealthLoader
import com.rutger.fastify.ui.screens.hasMic
import com.rutger.fastify.ui.screens.HomeScreen
import com.rutger.fastify.ui.screens.JamSheet
import com.rutger.fastify.ui.screens.toast
import com.rutger.fastify.ui.screens.LyricsScreen
import com.rutger.fastify.ui.screens.MiniPlayer
import com.rutger.fastify.ui.screens.NewReleasesScreen
import com.rutger.fastify.ui.screens.PlayerScreen
import com.rutger.fastify.ui.screens.SearchScreen
import kotlinx.serialization.json.Json

private val navJson = Json { ignoreUnknownKeys = true }

/** Full-screen routes that cover the app chrome (no mini player / bottom bar). */
private val fullScreen = setOf("player", "lyrics")

/** App-wide navigation, available to sheets and rows through [LocalNavigator]. */
class Navigator(private val nav: NavHostController) {
    fun openCollection(c: Collection) =
        go("collection?c=" + Uri.encode(navJson.encodeToString(Collection.serializer(), c)))

    fun openArtist(id: String) = go("artist/$id")
    fun openNewReleases() = go("new")
    fun openPlayer() = nav.navigate("player")
    fun openLyrics() = nav.navigate("lyrics")
    fun openDiagnostics() = nav.navigate("diagnostics")
    fun back() = nav.popBackStack()

    /** Bumped on a Search tab double-tap; SearchScreen focuses its field when it changes. */
    var searchFocusTick by mutableIntStateOf(0)
        internal set

    /** From the player (or lyrics) the destination replaces it, like Spotify closing now-playing. */
    private fun go(route: String) {
        val current = nav.currentDestination?.route
        nav.navigate(route) {
            if (current in fullScreen) popUpTo("player") { inclusive = true }
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no Navigator") }

// Screen transitions: pages slide in from the right and back out to the right (the page below
// moves along), the player and lyrics slide up from the bottom and back down. No scaling/fading,
// also not for predictive back (system back gesture/button).
private val slideSpec = fast<IntOffset>()
/** Keeps the page under the player in place (and visible) while the player slides over it. */
private val stay = fadeOut(tween(durationMillis = 1, delayMillis = ANIM_MS - 1))

private val NavBackStackEntry.isFullScreen get() = destination.route in fullScreen

private typealias Scope = AnimatedContentTransitionScope<NavBackStackEntry>

private fun Scope.enter(): EnterTransition =
    if (targetState.isFullScreen) slideInVertically(slideSpec) { it } else slideInHorizontally(slideSpec) { it }

private fun Scope.exit(): ExitTransition = when {
    targetState.isFullScreen -> stay
    initialState.isFullScreen -> slideOutVertically(slideSpec) { it }
    else -> slideOutHorizontally(slideSpec) { -it }
}

private fun Scope.popEnter(): EnterTransition =
    if (initialState.isFullScreen) EnterTransition.None else slideInHorizontally(slideSpec) { -it }

private fun Scope.popExit(): ExitTransition =
    if (initialState.isFullScreen) slideOutVertically(slideSpec) { it } else slideOutHorizontally(slideSpec) { it }

@Composable
fun FastifyNavHost() {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val ctx = LocalContext.current
    val c = ctx.container
    val player = c.player
    // Activity-scoped: shared by the Herkennen tab and the stealth loader.
    val shazam = viewModel { ShazamViewModel(c.app, c.library, c.shazamHistory) }
    val stealth by shazam.stealth.collectAsStateWithLifecycle()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shazam.start(stealth = true)
    }
    var lastSearchTap by remember { mutableLongStateOf(0L) }
    var lastShazamTap by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shazam) {
        shazam.stealthDone.collect { nav.switchTab("home"); shazam.stealthFinished() }
    }
    val shazamed by c.shazamHistory.items.collectAsStateWithLifecycle()
    val seenAt by c.shazamHistory.seenAt.collectAsStateWithLifecycle()
    val unseen = (shazamed.firstOrNull()?.at ?: 0L) > seenAt
    val playerState by player.state.collectAsStateWithLifecycle()
    val jamSheet by c.jam.sheet.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.jam.notices.collect { ctx.toast(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showChrome = route !in fullScreen
    // Anything newer than seenAt gets the tab badge; looking at the tab clears it.
    LaunchedEffect(route, stealth, shazamed) { if (route == "shazam" && !stealth) c.shazamHistory.markSeen() }
    // The tab whose stack is showing: search/shazam when on the back stack, else home (the start).
    val currentTab = remember(entry) {
        listOf("search", "shazam").firstOrNull { runCatching { nav.getBackStackEntry(it) }.isSuccess } ?: "home"
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
      Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (showChrome) {
                    Column {
                        MiniPlayer(playerState, player, onOpen = navigator::openPlayer)
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == "home",
                                onClick = { nav.selectTab("home", currentTab) },
                                icon = { Icon(Icons.Default.Home, null) },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = currentTab == "search",
                                onClick = {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastSearchTap < DOUBLE_TAP_MS) {
                                        lastSearchTap = 0L
                                        navigator.searchFocusTick++
                                    } else {
                                        lastSearchTap = now
                                    }
                                    nav.selectTab("search", currentTab)
                                },
                                icon = { Icon(Icons.Default.Search, null) },
                                label = { Text("Search") },
                            )
                            NavigationBarItem(
                                selected = currentTab == "shazam",
                                onClick = {
                                    // Double tap: recognize right away behind the fake loading screen.
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastShazamTap < DOUBLE_TAP_MS) {
                                        lastShazamTap = 0L
                                        if (hasMic(ctx)) shazam.start(stealth = true) else askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        lastShazamTap = now
                                        nav.selectTab("shazam", currentTab)
                                    }
                                },
                                icon = { BadgedBox(badge = { if (unseen) Badge() }) { Icon(Icons.Default.GraphicEq, null) } },
                                label = { Text("Herkennen") },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = "home",
                modifier = Modifier.padding(padding),
                enterTransition = { enter() },
                exitTransition = { exit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
                // Back gestures/back button go through the predictive path, whose defaults scale the
                // page down to 70% towards the center. Use the same slides as a normal pop.
                predictivePopEnterTransition = { popEnter() },
                predictivePopExitTransition = { popExit() },
            ) {
                composable("home") { HomeScreen() }
                composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                composable("search") { SearchScreen() }
                composable("shazam") { ShazamScreen(shazam) }
                composable("new") { NewReleasesScreen() }
                composable(
                    "collection?c={c}",
                    arguments = listOf(navArgument("c") { nullable = false }),
                ) { backStack ->
                    val c = navJson.decodeFromString(Collection.serializer(), backStack.arguments?.getString("c")!!)
                    CollectionScreen(c)
                }
                composable("artist/{id}") { backStack ->
                    ArtistScreen(backStack.arguments?.getString("id")!!)
                }
                composable("player") {
                    // Opaque, so the page below doesn't show through while it slides.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        PlayerScreen(onClose = { nav.popBackStack() })
                    }
                }
                composable("lyrics") { LyricsScreen(onClose = { nav.popBackStack() }) }
            }
        }
        if (stealth) StealthLoader(onCancel = shazam::stop)
        if (jamSheet) JamSheet()
      }
    }
}

private const val DOUBLE_TAP_MS = 400L

/**
 * Bottom bar tap. On another tab: switch to it, restoring where that tab was. On the current
 * tab (e.g. Home while on an artist opened from Home): go back to the tab's root page.
 */
private fun NavHostController.selectTab(tab: String, currentTab: String) {
    if (tab != currentTab) switchTab(tab)
    else if (currentDestination?.route != tab) popBackStack(tab, inclusive = false)
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
