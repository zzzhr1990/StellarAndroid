package cn.coldlake.stellar.opensource

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import android.view.ViewTreeObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import cn.coldlake.stellar.opensource.platform.FoldPosture
import cn.coldlake.stellar.opensource.platform.foldPosture
import cn.coldlake.stellar.opensource.platform.rememberBookFold
import cn.coldlake.stellar.opensource.presentation.HomeTab
import com.stellar.player.ui.CapsuleDestination
import com.stellar.player.ui.CapsuleNavigation
import cn.coldlake.stellar.opensource.navigation.LibraryNavDisplay

internal val LocalHomeNavigationBottomInset = staticCompositionLocalOf { 96.dp }

/** Home roots retain only NavEntry state while off screen. */
@Composable
internal fun HomeNavigation(selected: HomeTab, onSelect: (HomeTab) -> Unit,
    showBottomBar: Boolean = true, floating: Boolean = true, hideNavigationLabels: Boolean = false,
    edgeToEdgeTabs: Set<HomeTab> = emptySet(),
    playbackControls: @Composable () -> Unit = {},
    content: @Composable (HomeTab) -> Unit) {
    val view = LocalView.current
    val windowSize = LocalWindowInfo.current.containerSize
    val coverScreen = with(LocalDensity.current) {
        windowSize.width.toDp() < 480.dp && windowSize.height.toDp() < 480.dp
    }
    var insetsReady by remember(view) { mutableStateOf(false) }
    var settingsBottomInset by rememberSaveable { mutableFloatStateOf(96f) }
    // Keep the fold subscription and host geometry when a child page hides the bar.
    // Re-entering must measure the capsule at its pane width on the first pass.
    val bookFold = if (floating) rememberBookFold().value else null
    // Leaving book posture drops the bounds, so a later window size never reuses stale geometry.
    var navigationBounds by remember(view, bookFold != null) { mutableStateOf<Rect?>(null) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (ViewCompat.getRootWindowInsets(view) != null) insetsReady = true
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
    }
    // Scaffold measures the bar before content in the same layout pass. A post-layout
    // height callback would briefly enlarge the viewport and clamp restored scroll offsets.
    val destinations = listOf(
                CapsuleDestination(HomeTab.MEDIA.name, stringResource(R.string.home_media), ImageVector.vectorResource(R.drawable.material_symbol_play_arrow)),
                CapsuleDestination(HomeTab.MEDIA_LIBRARY.name, stringResource(R.string.home_media_library), ImageVector.vectorResource(R.drawable.material_symbol_folder)),
                CapsuleDestination(HomeTab.SETTINGS.name, stringResource(R.string.home_settings), ImageVector.vectorResource(R.drawable.material_symbol_settings)),
            )
    // Flat windows do not observe layout coordinates or feed placement back into composition.
    Scaffold(modifier = if (bookFold == null) Modifier else Modifier.onGloballyPositioned {
            navigationBounds = it.boundsInWindow()
        }, containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        Column(Modifier.then(if (showBottomBar) Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) else Modifier)) {
        playbackControls()
        if (showBottomBar && floating) {
        // A half-opened book fold must not split the capsule; center it on the start pane instead.
        val posture = foldPosture(bookFold, navigationBounds, LocalDensity.current)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(Modifier.fillMaxWidth()
            .then(if (posture !is FoldPosture.Book) Modifier
                else if (rtl) Modifier.absolutePadding(left = posture.left + posture.gap)
                else Modifier.absolutePadding(right = posture.right + posture.gap))
            .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        CapsuleNavigation(
            destinations = destinations,
            selectedId = selected.name,
            onSelect = { onSelect(HomeTab.valueOf(it)) },
            // Flip cover screens are short in both directions; icons alone keep the capsule clear of content.
            showLabels = !hideNavigationLabels && !coverScreen,
        )
        }
        } else if (showBottomBar) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(Modifier.testTag("home-standard-navigation"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)) {
                destinations.forEach { destination ->
                    NavigationBarItem(selected = destination.id == selected.name, onClick = { onSelect(HomeTab.valueOf(destination.id)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = { Icon(destination.icon, contentDescription = if (hideNavigationLabels) destination.label else null,
                            modifier = Modifier.size(24.dp)) },
                        label = if (hideNavigationLabels) null else { { Text(destination.label) } },
                        modifier = Modifier.testTag("home-tab-${destination.id}"))
                }
            }
        }
        }
    }) { padding ->
        // Hiding the bar must not briefly shorten the outgoing settings list and
        // clamp its saved scroll offset before the child page replaces it.
        val barHeight = padding.calculateBottomPadding()
        val settingsInset = if (barHeight > 0.dp) barHeight + 16.dp else settingsBottomInset.dp
        SideEffect { if (barHeight > 0.dp) settingsBottomInset = settingsInset.value }
        // Hills settings scroll beneath the floating capsule; the list itself has a
        // trailing inset so the final row can always scroll above the touch targets.
        Box(Modifier.fillMaxSize()) {
            // On Activity recreation the first composition can precede window insets.
            // Do not clamp a restored scroll offset against that temporary larger viewport.
            if (insetsReady) CompositionLocalProvider(LocalHomeNavigationBottomInset provides
                settingsInset) {
                LibraryNavDisplay(listOf(selected), Modifier.fillMaxSize(), onBack = {}, retainedRoots = HomeTab.entries) { tab ->
                    Box(Modifier.fillMaxSize()
                        .then(if (tab == HomeTab.SETTINGS && floating) Modifier else Modifier.padding(padding).consumeWindowInsets(padding))
                        .then(if (tab in edgeToEdgeTabs) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            else Modifier.safeDrawingPadding())) {
                        content(tab)
                    }
                }
            }
        }
    }
}
