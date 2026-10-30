package com.rutger.fastify.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import com.rutger.fastify.ui.screens.HomeScreen
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

    /** From the player (or lyrics) the destination replaces it, like Spotify closing now-playing. */
    private fun go(route: String) {
        val current = nav.currentDestination?.route
        nav.navigate(route) {
            if (current in fullScreen) popUpTo("player") { inclusive = true }
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no Navigator") }

@Composable
fun FastifyNavHost() {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val player = LocalContext.current.container.player
    val playerState by player.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showChrome = route !in fullScreen

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Scaffold(
            bottomBar = {
                if (showChrome) {
                    Column {
                        MiniPlayer(playerState, player, onOpen = navigator::openPlayer)
                        NavigationBar {
                            NavigationBarItem(
                                selected = route == "home",
                                onClick = { nav.switchTab("home") },
                                icon = { Icon(Icons.Default.Home, null) },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = route == "search",
                                onClick = { nav.switchTab("search") },
                                icon = { Icon(Icons.Default.Search, null) },
                                label = { Text("Search") },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen() }
                composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                composable("search") { SearchScreen() }
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
                composable("player") { PlayerScreen(onClose = { nav.popBackStack() }) }
                composable("lyrics") { LyricsScreen(onClose = { nav.popBackStack() }) }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
