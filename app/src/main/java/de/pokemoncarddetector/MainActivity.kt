package de.pokemoncarddetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pokemoncarddetector.data.CatalogState
import de.pokemoncarddetector.ui.library.LibraryScreen
import de.pokemoncarddetector.ui.library.SetDetailScreen
import de.pokemoncarddetector.ui.scan.ScanScreen
import de.pokemoncarddetector.ui.search.SearchScreen
import de.pokemoncarddetector.ui.settings.SettingsScreen
import de.pokemoncarddetector.ui.theme.AppTheme
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PokeApp).container
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    val catalogState by container.catalogManager.state.collectAsStateWithLifecycle()
                    when (val s = catalogState) {
                        CatalogState.Ready -> MainScaffold()
                        is CatalogState.Failed -> ErrorScreen(s.message) { container.catalogManager.ensureReady() }
                        CatalogState.Checking -> LoadingScreen(null)
                        is CatalogState.Importing -> LoadingScreen(s.progress)
                    }
                }
            }
        }
    }
}

private object Routes {
    const val LIBRARY_GRAPH = "sammlung"
    const val LIBRARY = "sammlung/uebersicht"
    const val SET = "sammlung/set/{setId}"
    const val SETTINGS = "sammlung/einstellungen"
    const val SCAN = "scannen"
    const val SEARCH = "suche?q={q}"
    const val SEARCH_BASE = "suche"
    fun set(id: String) = "sammlung/set/${URLEncoder.encode(id, "UTF-8")}"
    fun search(q: String) = "suche?q=${URLEncoder.encode(q, "UTF-8")}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.SCAN, "Scannen", Icons.Default.CameraAlt),
    Tab(Routes.LIBRARY_GRAPH, "Sammlung", Icons.Default.CollectionsBookmark),
    Tab(Routes.SEARCH_BASE, "Suche", Icons.Default.Search),
)

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route || it.route?.startsWith(tab.route + "?") == true } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val isScan = current?.route == Routes.SCAN
        NavHost(
            navController = nav,
            startDestination = Routes.SCAN,
            modifier = Modifier
                .padding(padding)
                .then(if (isScan) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)),
        ) {
            composable(Routes.SCAN) {
                ScanScreen(
                    onSearch = { q -> nav.navigate(Routes.search(q)) { launchSingleTop = true } },
                    onOpenSet = { nav.navigate(Routes.set(it)) },
                )
            }
            navigation(startDestination = Routes.LIBRARY, route = Routes.LIBRARY_GRAPH) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        onOpenSet = { nav.navigate(Routes.set(it)) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SET, arguments = listOf(navArgument("setId") { type = NavType.StringType })) { entry ->
                    SetDetailScreen(setId = entry.arguments?.getString("setId").orEmpty(), onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                SearchScreen(
                    initialQuery = entry.arguments?.getString("q"),
                    onOpenSet = { nav.navigate(Routes.set(it)) },
                )
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LoadingScreen(progress: Float?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Karten-Scanner", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            Text("Kartendatenbank wird eingerichtet …", textAlign = TextAlign.Center)
            Text("(nur beim ersten Start)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Die Kartendatenbank konnte nicht geladen werden.", textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Erneut versuchen") }
        }
    }
}
