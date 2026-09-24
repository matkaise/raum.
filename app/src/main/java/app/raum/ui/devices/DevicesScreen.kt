package app.raum.ui.devices

import androidx.compose.runtime.LaunchedEffect
import app.raum.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.raum.domain.models.DeviceCategory
import app.raum.ui.HomeUiState
import app.raum.ui.components.DeviceCard
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.EmptyState
import app.raum.ui.components.ScreenTitle
import app.raum.ui.components.SectionHeader

/** Alle Geräte, nach Raum gruppiert und nach Kategorie filterbar. */
@Composable
fun DevicesScreen(
    state: HomeUiState,
    cardActions: DeviceCardActions,
    openAddDialog: Boolean = false,
    onAddDialogOpened: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf<DeviceCategory?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Direkt nach der Einrichtung („Erstes Gerät hinzufügen“)
    LaunchedEffect(openAddDialog) { if (openAddDialog) { adding = true; onAddDialogOpened() } }

    val visible = state.devices.filter { filter == null || it.category == filter }
    val noRoom = stringResource(R.string.no_room)
    val groups = state.rooms.map { it.room.name to visible.filter { d -> d.roomId == it.room.id } } +
        (noRoom to visible.filter { d -> d in state.unassigned })
    val available = DeviceCategory.entries.filter { c -> state.devices.any { it.category == c } }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 40.dp)) {
        ScreenTitle(
            stringResource(R.string.nav_devices),
            pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size) + " · " +
                stringResource(R.string.offline_count, state.offline.size),
        ) {
            FilledTonalButton(onClick = { adding = true }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.device_add))
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            item {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.filter_all)) }, modifier = Modifier.height(48.dp))
            }
            items(available) { c ->
                FilterChip(
                    selected = filter == c,
                    onClick = { filter = if (filter == c) null else c },
                    label = { Text(stringResource(c.labelRes)) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }

        if (state.devices.isEmpty()) {
            EmptyState(Icons.Outlined.Devices, stringResource(R.string.devices_empty), stringResource(R.string.devices_empty_text)) {
                FilledTonalButton(onClick = { adding = true }) { Text(stringResource(R.string.device_add)) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                groups.filter { it.second.isNotEmpty() }.forEach { (title, devices) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header-$title") {
                        Row { SectionHeader(title, Modifier.padding(top = 8.dp)) }
                    }
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            onToggle = cardActions.onToggle,
                            onCommand = cardActions.onCommand,
                            onOpenDetails = cardActions.onOpenDetails,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        CommissioningDialog(rooms = state.rooms.map { it.room }, onClose = { adding = false })
    }
}
