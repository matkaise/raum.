package app.raum.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.R
import app.raum.domain.usecases.UiMessageBus
import app.raum.i18n.Strings
import app.raum.platform.bluetooth.BluetoothAccess
import app.raum.platform.bluetooth.BluetoothState
import app.raum.platform.network.NetworkController
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import app.raum.thread.BorderRouter
import app.raum.thread.DatasetFetch
import app.raum.thread.ImportResult
import app.raum.thread.ThreadDataset
import app.raum.thread.ThreadService
import app.raum.thread.ThreadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Einstellungen → Matter und Thread: Thread-Netz, Border Router, Diagnose, Zugangsdaten für neue Geräte (M6). */
class ThreadViewModel(
    private val thread: ThreadService,
    private val bluetoothAccess: BluetoothAccess,
    private val pins: AdminPinStore,
    private val network: NetworkController,
    private val messages: UiMessageBus,
    private val strings: Strings,
) : ViewModel() {

    val status: StateFlow<ThreadStatus> = thread.status
    val scanning: StateFlow<Boolean> = thread.scanning
    val bluetooth: StateFlow<BluetoothState> = bluetoothAccess.state
    val wifiSsid: StateFlow<String?> = thread.wifiSsid
    val bluetoothPermissions: Array<String> get() = bluetoothAccess.permissions
    val canEnableBluetooth: Boolean get() = bluetoothAccess.canEnableItself

    /** Router, von dem gerade Zugangsdaten geholt werden */
    private val _adopting = MutableStateFlow<String?>(null)
    val adopting: StateFlow<String?> = _adopting.asStateFlow()

    val pinRequired: Boolean get() = pins.isSet
    fun verifyPin(pin: String): VerifyResult = pins.verify(pin)

    fun startScan() { thread.startScan(); bluetoothAccess.refresh() }
    fun stopScan() = thread.stopScan()
    fun rescan() { thread.stopScan(); thread.startScan() }

    fun preview(text: String): ThreadDataset.ParseResult = thread.preview(text)

    /** @return true, wenn übernommen */
    fun import(text: String): Boolean = when (val r = thread.importDataset(text)) {
        is ImportResult.Ok -> { messages.info(strings.get(R.string.thread_saved, r.network.networkName)); true }
        else -> false
    }

    fun adopt(router: BorderRouter) {
        if (_adopting.value != null) return
        _adopting.value = router.id
        viewModelScope.launch {
            when (val r = thread.adoptFrom(router)) {
                is ImportResult.Ok -> messages.info(strings.get(R.string.thread_adopted, r.network.networkName, router.displayName))
                is ImportResult.FetchFailed -> messages.error(
                    strings.get(
                        when (r.reason) {
                            DatasetFetch.NoNetwork -> R.string.thread_adopt_no_network
                            DatasetFetch.Invalid -> R.string.thread_adopt_invalid
                            else -> R.string.thread_adopt_unreachable
                        },
                        router.displayName,
                    ),
                )
                is ImportResult.Invalid -> messages.error(strings.get(R.string.thread_adopt_invalid, router.displayName))
            }
            _adopting.value = null
        }
    }

    fun clearThread() = thread.clearThread()

    /** SSID des Panels als Vorschlag (sofern Android sie preisgibt) */
    val currentSsid: String? get() = network.status.value.ssid

    fun setWifi(ssid: String, password: String) {
        thread.setWifi(ssid, password)
        messages.info(strings.get(R.string.commissioning_wifi_saved, ssid.trim()))
    }

    fun clearWifi() = thread.clearWifi()

    fun refreshBluetooth() = bluetoothAccess.refresh()

    /** @return false, wenn Android den Nutzer fragen muss (kein Geräteeigentümer) */
    fun enableBluetooth(): Boolean = bluetoothAccess.enable().also { bluetoothAccess.refresh() }
}
