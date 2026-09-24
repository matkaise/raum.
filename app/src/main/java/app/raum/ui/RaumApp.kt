package app.raum.ui

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
import app.raum.domain.usecases.UiMessage
import app.raum.ui.automations.AutomationEditorScreen
import app.raum.ui.automations.AutomationsScreen
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.DeviceDetailActions
import app.raum.ui.components.DeviceDetailSheet
import app.raum.ui.devices.DevicesScreen
import app.raum.ui.overview.OverviewScreen
import app.raum.ui.rooms.RoomDetailScreen
import app.raum.ui.rooms.RoomsScreen
import app.raum.ui.scenes.SceneEditorScreen
import app.raum.ui.scenes.ScenesScreen
import app.raum.ui.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import app.raum.ui.appliance.MaintenanceScreen
import java.util.UUID

private enum class TopLevel(val route: String, @StringRes val labelRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    OVERVIEW("overview", R.string.nav_overview, Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    ROOMS("rooms", R.string.nav_rooms, Icons.Outlined.MeetingRoom, Icons.Filled.MeetingRoom),
    DEVICES("devices", R.string.nav_devices, Icons.Outlined.Devices, Icons.Filled.Devices),
    SCENES("scenes", R.string.nav_scenes, Icons.Outlined.WbTwilight, Icons.Filled.WbTwilight),
    AUTOMATIONS("automations", R.string.nav_automations, Icons.Outlined.AutoMode, Icons.Filled.AutoMode),
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                val error = lastMessage?.isError == true
                Snackbar(
                    snackbarData = data,
                    containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                    contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxHeight().width(120.dp),
                header = {
                    Text(
                        "raum.",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 28.dp, bottom = 24.dp),
                    )
                },
            ) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    TopLevel.entries.forEach { dest ->
                        val selected = backStack?.destination?.hierarchy?.any {
                            it.route == dest.route || (dest == TopLevel.ROOMS && it.route == ROOM_DETAIL) ||
                                (dest == TopLevel.SCENES && (it.route == SCENE_NEW || it.route == SCENE_EDIT)) ||
                                (dest == TopLevel.AUTOMATIONS && (it.route == AUTOMATION_NEW || it.route == AUTOMATION_EDIT)) ||
                                (dest == TopLevel.SETTINGS && it.route == MAINTENANCE)
                        } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(dest) },
                            icon = { Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                NavHost(navController, startDestination = TopLevel.OVERVIEW.route) {
                    composable(TopLevel.OVERVIEW.route) {
                        OverviewScreen(
                            state = state,
                            cardActions = cardActions,
                            onRunScene = viewModel::runScene,
                            onOpenRoom = { navController.navigate("rooms/${it}") },
                        )
                    }
                    composable(TopLevel.ROOMS.route) {
                        RoomsScreen(
                            state = state,
                            onOpenRoom = { navController.navigate("rooms/${it}") },
                            onAddRoom = viewModel::addRoom,
                            onUpdateRoom = viewModel::updateRoom,
                            onDeleteRoom = viewModel::deleteRoom,
                            onMoveRoom = viewModel::moveRoom,
                        )
                    }
                    composable(ROOM_DETAIL, arguments = listOf(navArgument("roomId") { type = NavType.StringType })) { entry ->
                        val roomId = entry.arguments?.getString("roomId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        RoomDetailScreen(
                            summary = roomId?.let(state::room),
                            cardActions = cardActions,
                            onGroupAction = viewModel::roomAction,
                            onBack = { navController.popBackStack() },
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
            ),
            onDismiss = { detailDeviceId = null },
        )
    } else if (detailDeviceId != null && state.devices.isNotEmpty()) {
        // Gerät wurde entfernt, während das Sheet offen war.
        LaunchedEffect(detailDeviceId) { detailDeviceId = null }
    }
}

/** „Alle Lichter aus“ bzw. „3 Lichter an“. */
@Composable
fun lightsStatus(lightsOn: Int): String =
    if (lightsOn == 0) stringResource(R.string.lights_all_off) else pluralStringResource(R.plurals.lights_on, lightsOn, lightsOn)

/** Wechsel zwischen den Hauptbereichen: je Bereich eigener, gespeicherter Stapel. */
private fun NavHostController.navigateTopLevel(dest: TopLevel) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = dest != TopLevel.ROOMS
    }
}
