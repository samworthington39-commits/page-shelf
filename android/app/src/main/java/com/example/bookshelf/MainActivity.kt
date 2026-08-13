package com.example.bookshelf

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bookshelf.domain.Book
import com.example.bookshelf.ui.LibraryViewModel
import com.example.bookshelf.ui.ManageViewModel
import com.example.bookshelf.ui.PageShelfViewModelFactory
import com.example.bookshelf.ui.ReaderViewModel
import com.example.bookshelf.ui.SettingsViewModel
import com.example.bookshelf.ui.StartupState
import com.example.bookshelf.ui.StartupViewModel
import com.example.bookshelf.ui.auth.StartupScreen
import com.example.bookshelf.ui.library.LibraryScreen
import com.example.bookshelf.ui.manage.ManageScreen
import com.example.bookshelf.ui.narration.NarrationScreen
import com.example.bookshelf.ui.reader.PdfReaderScreen
import com.example.bookshelf.ui.reader.ReaderLoadingScreen
import com.example.bookshelf.ui.reader.TextReaderScreen
import com.example.bookshelf.ui.reader.TextReaderViewModel
import com.example.bookshelf.ui.settings.ServerSettingsScreen
import com.example.bookshelf.ui.theme.PageShelfTheme
import kotlinx.coroutines.delay

/** A refined push transition: the next screen glides in from the right while the old one settles. */
private val pushEnter: EnterTransition =
    fadeIn(tween(320, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(360, easing = FastOutSlowInEasing)) { it / 6 } +
        scaleIn(initialScale = 0.985f, animationSpec = tween(360, easing = FastOutSlowInEasing))

private val pushExit: ExitTransition =
    fadeOut(tween(240, easing = FastOutSlowInEasing)) +
        slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 10 }

private val popEnter: EnterTransition =
    fadeIn(tween(200, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it / 10 }

private val popExit: ExitTransition =
    fadeOut(tween(180, easing = FastOutSlowInEasing)) +
        slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 8 }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PageShelfApplication).container
        setContent {
            val preferences by container.settings.state.collectAsStateWithLifecycle()
            PageShelfTheme(preferences.themeMode) {
                SyncSystemBarIcons()
                PageShelfApp(container)
            }
        }
    }
}

@Composable
private fun SyncSystemBarIcons() {
    val view = LocalView.current
    val useDarkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }
}

@Composable
private fun PageShelfApp(container: AppContainer) {
    val navController = rememberNavController()
    LaunchedEffect(container) {
        container.credentials.sessionRevoked.collect {
            if (navController.currentDestination?.route != "settings") {
                navController.navigate("settings") {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = "startup",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = { pushEnter },
        exitTransition = { pushExit },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit },
    ) {
        composable("startup") {
            val model: StartupViewModel = viewModel(factory = PageShelfViewModelFactory(container))
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                val startup = state
                if (startup == StartupState.Connected) {
                    navController.navigate("library") { popUpTo("startup") { inclusive = true } }
                } else if (startup is StartupState.NeedsLogin && startup.message == null) {
                    navController.navigate("settings") { popUpTo("startup") { inclusive = true } }
                }
            }
            StartupScreen(
                state = state,
                onRetry = model::connect,
                onEditServer = { navController.navigate("settings") { popUpTo("startup") { inclusive = true } } },
            )
        }
        composable("settings") {
            val model: SettingsViewModel = viewModel(factory = PageShelfViewModelFactory(container))
            ServerSettingsScreen(
                viewModel = model,
                onBack = if (navController.previousBackStackEntry != null) ({ navController.navigateUp(); Unit }) else null,
                onConnected = {
                    navController.navigate("library") {
                        popUpTo("settings") { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = "library",
        ) {
            val model: LibraryViewModel = viewModel(factory = PageShelfViewModelFactory(container))
            LibraryScreen(
                viewModel = model,
                bookRepository = container.books,
                onManage = { navController.navigate("manage") },
                onBookClick = { book -> navController.openReader(book) },
            )
        }
        composable(
            route = "manage",
        ) {
            val model: ManageViewModel = viewModel(factory = PageShelfViewModelFactory(container))
            ManageScreen(
                viewModel = model,
                onBack = navController::navigateUp,
                onServerSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "text-reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = requireNotNull(entry.arguments?.getString("bookId"))
            TextReaderDestination(
                container = container,
                bookId = bookId,
                onBack = navController::navigateUp,
                onOpenNarration = { navController.navigate("narration/$bookId") },
            )
        }
        composable(
            route = "narration/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = requireNotNull(entry.arguments?.getString("bookId"))
            NarrationScreen(
                controller = container.narration,
                bookId = bookId,
                onBack = navController::navigateUp,
            )
        }
        composable(
            route = "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = requireNotNull(entry.arguments?.getString("bookId"))
            val model: ReaderViewModel = viewModel(
                key = "reader-$bookId",
                factory = PageShelfViewModelFactory(container, bookId),
            )
            PdfReaderScreen(viewModel = model, onBack = navController::navigateUp)
        }
    }
}

@Composable
private fun TextReaderDestination(
    container: AppContainer,
    bookId: String,
    onBack: () -> Unit,
    onOpenNarration: () -> Unit,
) {
    var initializeReader by remember(bookId) { mutableStateOf(false) }
    if (!initializeReader) {
        ReaderLoadingScreen(
            message = "正在加载中",
            detail = "正在启动文字阅读器",
            modifier = Modifier.fillMaxSize(),
        )
        LaunchedEffect(bookId) {
            // Do not construct the ViewModel until the independent loading surface has drawn.
            withFrameNanos { }
            delay(48)
            initializeReader = true
        }
        return
    }
    val model: TextReaderViewModel = viewModel(
        key = "text-reader-$bookId",
        factory = PageShelfViewModelFactory(container, bookId),
    )
    TextReaderScreen(viewModel = model, onBack = onBack, onOpenNarration = onOpenNarration)
}

private fun androidx.navigation.NavHostController.openReader(book: Book) {
    // A fast double tap used to stack multiple reader destinations and start duplicate preload jobs.
    if (currentDestination?.route != "library") return
    val route = if (book.capabilities.reflowableText) "text-reader/${book.id}" else "reader/${book.id}"
    navigate(route) { launchSingleTop = true }
}
