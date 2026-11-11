package app.raum.ui

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.navigation.NavHostController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.outlined.Kitchen
import app.raum.homeconnect.HomeConnectService
import app.raum.ui.household.HouseholdRow
import app.raum.ui.household.HouseholdScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.raum.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.raum.platform.display.DisplayController
import app.raum.ui.overview.LocalSkyScene
import app.raum.ui.theme.SkyContentTheme
import app.raum.ui.overview.SkyBackdrop
import app.raum.ui.overview.rememberSkyScene
import app.raum.domain.usecases.UiMessage
import app.raum.ui.automations.AutomationEditorScreen
import app.raum.ui.automations.AutomationsScreen
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.DeviceDetailActions
import app.raum.ui.components.DeviceDetailSheet
import app.raum.ui.devices.DevicesScreen
import app.raum.ui.overview.OverviewScreen
import app.raum.ui.rooms.RoomsScreen
import app.raum.ui.scenes.SceneEditorScreen
import app.raum.ui.scenes.ScenesScreen
import app.raum.ui.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import app.raum.ui.appliance.MaintenanceScreen
import java.util.UUID

private enum class TopLevel(val route: String, @StringRes val shortLabelRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    OVERVIEW("overview", R.string.nav_overview, Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    ROOMS("rooms", R.string.nav_rooms, Icons.Outlined.MeetingRoom, Icons.Filled.MeetingRoom),
    SCENES("scenes", R.string.nav_scenes, Icons.Outlined.WbTwilight, Icons.Filled.WbTwilight),
    HOUSEHOLD("household", R.string.nav_household, Icons.Outlined.Kitchen, Icons.Filled.Kitchen),
    DEVICES("devices", R.string.nav_devices, Icons.Outlined.Devices, Icons.Filled.Devices),
    AUTOMATIONS("automations", R.string.nav_automations_short, Icons.Outlined.AutoMode, Icons.Filled.AutoMode),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

private const val ROOM_DETAIL = "rooms/{roomId}"
private const val SCENE_NEW = "scenes/new"
private const val SCENE_EDIT = "scenes/{sceneId}/edit"
private const val AUTOMATION_NEW = "automations/new"
private const val MAINTENANCE = "settings/maintenance"
private const val AUTOMATION_EDIT = "automations/{automationId}/edit"

@Composable
fun RaumApp(
    viewModel: HomeViewModel = koinViewModel(),
    requests: UiRequests = koinInject(),
    display: DisplayController = koinInject(),
    homeConnect: HomeConnectService = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val household by homeConnect.state.collectAsStateWithLifecycle()
    val openAppliance by requests.openAppliance.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    var lastMessage by remember { mutableStateOf<UiMessage?>(null) }
    var detailDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    val openCommissioning by requests.openCommissioning.collectAsStateWithLifecycle()
    LaunchedEffect(openCommissioning) {
        // Wie ein Tipp auf die Navigationsleiste – sonst liegt „Geräte“ im gespeicherten Stapel der Übersicht
        if (openCommissioning) navController.navigateTopLevel(TopLevel.DEVICES)
    }
    val openSettings by requests.openSettings.collectAsStateWithLifecycle()
    LaunchedEffect(openSettings) {
        if (openSettings != null) navController.navigateTopLevel(TopLevel.SETTINGS)
    }
    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { msg ->
            lastMessage = msg
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg.text, duration = if (msg.isError) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }

    val cardActions = remember(viewModel) {
        DeviceCardActions(
            onToggle = viewModel::toggle,
            onCommand = viewModel::send,
            onOpenDetails = { detailDeviceId = it.id.toString() },
        )
    }

    val route = backStack?.destination?.route
    val sky = LocalSkyScene.current ?: rememberSkyScene(state)
    val selectedTop = TopLevel.entries.firstOrNull { dest ->
        route == dest.route || (dest == TopLevel.ROOMS && route == ROOM_DETAIL) ||
            (dest == TopLevel.SCENES && (route == SCENE_NEW || route == SCENE_EDIT)) ||
            (dest == TopLevel.AUTOMATIONS && (route == AUTOMATION_NEW || route == AUTOMATION_EDIT)) ||
            (dest == TopLevel.SETTINGS && route == MAINTENANCE)
    }

    // Home Connect: „fertig“/„vorgeheizt“ als Snackbar – außer auf „Haushalt“, dort steht der Hinweis oben auf der Seite
    val currentTop by androidx.compose.runtime.rememberUpdatedState(selectedTop)
    LaunchedEffect(homeConnect) {
        homeConnect.notices.collect { n ->
            if (n.global && currentTop != TopLevel.HOUSEHOLD) {
                lastMessage = UiMessage(n.text)
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(n.text, duration = SnackbarDuration.Short)
            }
        }
    }

    CompositionLocalProvider(LocalSkyScene provides sky) {
    run {
    Box(Modifier.fillMaxSize()) {
        // Himmel bewegt sich nur auf der Übersicht – sonst kostet er bei jedem Bild der anderen Seiten Rechenzeit
        SkyBackdrop(sky, animate = selectedTop == TopLevel.OVERVIEW)
        Row(Modifier.fillMaxSize()) {
            GlassRail(
                selected = selectedTop,
                // „Haushalt“ nur, wenn Home Connect eingeschaltet ist
                hidden = if (household.active) emptySet() else setOf(TopLevel.HOUSEHOLD),
                onNavigate = { navController.navigateTopLevel(it) },
                onSleep = display::sleepNow,
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // Ohne Überblendung: Beim Wechsel würden sonst zwei Seiten gleichzeitig gezeichnet – auf dem Panel ruckelt
                // das; ein sofortiger Wechsel wirkt dort ruhiger.
                NavHost(
                    navController, startDestination = TopLevel.OVERVIEW.route,
                    enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
                ) {
                    composable(TopLevel.OVERVIEW.route) {
                        OverviewScreen(
                            state = state,
                            scene = sky,
                            cardActions = cardActions,
                            onRunScene = viewModel::runScene,
                            onOpenRoom = { navController.openRoomFromOverview(it) },
                            householdRow = {
                                HouseholdRow(household, homeConnect) { haId ->
                                    requests.openAppliance.value = haId
                                    navController.navigateTopLevel(TopLevel.HOUSEHOLD)
                                }
                            },
                        )
                    }
                    composable(TopLevel.ROOMS.route) {
                        RoomsScreen(
                            state = state,
                            initialRoomId = null,
                            cardActions = cardActions,
                            onGroupAction = viewModel::roomAction,
                            onAddRoom = viewModel::addRoom,
                            onUpdateRoom = viewModel::updateRoom,
                            onDeleteRoom = viewModel::deleteRoom,
                            onMoveRoom = viewModel::moveRoom,
                        )
                    }
                    composable(ROOM_DETAIL, arguments = listOf(navArgument("roomId") { type = NavType.StringType })) { entry ->
                        val roomId = entry.arguments?.getString("roomId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        RoomsScreen(
                            state = state,
                            initialRoomId = roomId,
                            cardActions = cardActions,
                            onGroupAction = viewModel::roomAction,
                            onAddRoom = viewModel::addRoom,
                            onUpdateRoom = viewModel::updateRoom,
                            onDeleteRoom = viewModel::deleteRoom,
                            onMoveRoom = viewModel::moveRoom,
                        )
                    }
                    composable(TopLevel.DEVICES.route) {
                        DevicesScreen(
                            state = state,
                            cardActions = cardActions,
                            openAddDialog = openCommissioning,
                            onAddDialogOpened = { requests.openCommissioning.value = false },
                        )
                    }
                    composable(TopLevel.SCENES.route) {
                        ScenesScreen(
                            state = state,
                            onRunScene = viewModel::runScene,
                            onEditScene = { scene -> navController.navigate(if (scene == null) SCENE_NEW else "scenes/${scene.id}/edit") },
                            onDuplicateScene = viewModel::duplicateScene,
                            onDeleteScene = viewModel::deleteScene,
                        )
                    }
                    composable(TopLevel.HOUSEHOLD.route) {
                        HouseholdScreen(
                            requestedAppliance = openAppliance,
                            onRequestHandled = { requests.openAppliance.value = null },
                            onOpenSettings = { requests.openSettings.value = "HOME_CONNECT" },
                        )
                    }
                    composable(SCENE_NEW) {
                        SceneEditorScreen(sceneId = null, rooms = state.rooms.map { it.room }, onClose = { navController.popBackStack() })
                    }
                    composable(SCENE_EDIT, arguments = listOf(navArgument("sceneId") { type = NavType.StringType })) { entry ->
                        val sceneId = entry.arguments?.getString("sceneId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        SceneEditorScreen(sceneId = sceneId, rooms = state.rooms.map { it.room }, onClose = { navController.popBackStack() })
                    }
                    composable(TopLevel.AUTOMATIONS.route) {
                        AutomationsScreen(onEdit = { a -> navController.navigate(if (a == null) AUTOMATION_NEW else "automations/${a.id}/edit") })
                    }
                    composable(AUTOMATION_NEW) {
                        AutomationEditorScreen(automationId = null, onClose = { navController.popBackStack() })
                    }
                    composable(AUTOMATION_EDIT, arguments = listOf(navArgument("automationId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("automationId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        AutomationEditorScreen(automationId = id, onClose = { navController.popBackStack() })
                    }
                    composable(TopLevel.SETTINGS.route) {
                        SettingsScreen(state = state, onOpenMaintenance = { navController.navigate(MAINTENANCE) })
                    }
                    composable(MAINTENANCE) {
                        MaintenanceScreen(vm = koinViewModel(), onClose = { navController.popBackStack(TopLevel.SETTINGS.route, inclusive = false) })
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { data ->
            val error = lastMessage?.isError == true
            Snackbar(
                snackbarData = data,
                containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
            )
        }

    val detailDevice = detailDeviceId?.let { id -> state.devices.firstOrNull { it.id.toString() == id } }
    if (detailDevice != null) {
        DeviceDetailSheet(
            device = detailDevice,
            rooms = state.rooms.map { it.room },
            actions = DeviceDetailActions(
                onCommand = viewModel::send,
                onFavorite = { d, fav -> viewModel.setFavorite(d, fav) },
                onRename = { d, name -> viewModel.rename(d, name) },
                onAssignRoom = { d, room -> viewModel.assignRoom(d, room) },
                onRemove = { d -> viewModel.remove(d) },
                onSwitchAsLight = { d, asLight -> viewModel.setSwitchAsLight(d, asLight) },
            ),
            onDismiss = { detailDeviceId = null },
        )
    } else if (detailDeviceId != null && state.devices.isNotEmpty()) {
        // Gerät wurde entfernt, während das Sheet offen war.
        LaunchedEffect(detailDeviceId) { detailDeviceId = null }
    }
    }
    }
    }
}


/**
 * Navigation als schwebende Glasleiste links (Entwurf „Himmel“): fünf Hauptbereiche (sechs mit „Haushalt“),
 * darunter Einstellungen und Ruhezustand als runde Knöpfe.
 */
@Composable
private fun GlassRail(selected: TopLevel?, hidden: Set<TopLevel>, onNavigate: (TopLevel) -> Unit, onSleep: () -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(120.dp)
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.24f), Color.Transparent)))
            .padding(top = 22.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("raum.", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, style = app.raum.ui.theme.SkyTextStyle)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                Modifier.clip(RoundedCornerShape(40.dp)).background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(40.dp)).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TopLevel.entries.filter { it != TopLevel.SETTINGS && it !in hidden }.forEach { dest ->
                    val on = dest == selected
                    Column(
                        Modifier.width(78.dp).height(72.dp).clip(RoundedCornerShape(36.dp))
                            .background(if (on) Color.White else Color.Transparent)
                            .clickable { onNavigate(dest) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val fg = if (on) Color(0xFF10131F) else Color.White
                        Icon(if (on) dest.selectedIcon else dest.icon, null, Modifier.size(26.dp), tint = fg)
                        Spacer(Modifier.height(3.dp))
                        Text(stringResource(dest.shortLabelRes), color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RailCircle(if (selected == TopLevel.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings, stringResource(R.string.nav_settings), selected == TopLevel.SETTINGS) {
                onNavigate(TopLevel.SETTINGS)
            }
            RailCircle(Icons.Outlined.Bedtime, stringResource(R.string.sleep_now), false, onSleep)
        }
    }
}

@Composable
private fun RailCircle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (on) Color(0xFF10131F) else Color.White) }
}

/** „Alle Lichter aus“ bzw. „3 Lichter an“. */
@Composable
fun lightsStatus(lightsOn: Int): String =
    if (lightsOn == 0) stringResource(R.string.lights_all_off) else pluralStringResource(R.plurals.lights_on, lightsOn, lightsOn)

/**
 * Raum aus der Übersicht öffnen – als Wechsel in den Bereich „Räume“, nicht als Seite auf dem Stapel der Übersicht:
 * Sonst speichert der Tipp auf „Übersicht“ den Raum mit und stellt ihn sofort wieder her.
 */
private fun NavHostController.openRoomFromOverview(roomId: UUID) {
    navigate("rooms/$roomId") {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

/** Wechsel zwischen den Hauptbereichen: je Bereich eigener, gespeicherter Stapel. */
private fun NavHostController.navigateTopLevel(dest: TopLevel) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        // Übersicht hat keine Unterseiten. Beim Speichern ordnet Navigation den abgeräumten Stapel auch dem Ziel von
        // popUpTo (= Übersicht) zu – mit restoreState käme z. B. ein aus der Übersicht geöffneter Raum immer wieder.
        restoreState = dest != TopLevel.ROOMS && dest != TopLevel.OVERVIEW
    }
}
