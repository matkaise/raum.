package app.raum.platform.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.raum.platform.kiosk.KioskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BluetoothState {
    /** Kein Bluetooth im Gerät */
    UNAVAILABLE,
    /** Berechtigung fehlt (nur ohne Geräteeigentümer-Status möglich) */
    NO_PERMISSION,
    /** Android 11: Bluetooth-Suche braucht den Standortdienst (raum. ortet nicht) */
    NO_LOCATION_SERVICE,
    OFF,
    READY,
}

/**
 * Bluetooth für das Koppeln neuer Matter-Geräte (COM-002). Als Geräteeigentümer erteilt sich raum. die
 * Berechtigungen selbst und darf Bluetooth einschalten; sonst fragt die Oberfläche nach.
 */
class BluetoothAccess(
    private val context: Context,
    private val kiosk: KioskManager,
) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Berechtigungen, die die Oberfläche anfragen muss, wenn raum. nicht Geräteeigentümer ist. */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = refresh() },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    val canEnableItself: Boolean get() = kiosk.isDeviceOwner

    fun refresh() { ensurePermissions(); _state.value = read() }

    /** Darf das SDK jetzt nach Geräten suchen? Nur dann koppelt raum. auch über Bluetooth. */
    val ready: Boolean get() { refresh(); return _state.value == BluetoothState.READY }

    private fun ensurePermissions() {
        if (permissions.all(::granted)) return
        permissions.forEach(kiosk::grantPermission)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun read(): BluetoothState {
        val adapter = adapter ?: return BluetoothState.UNAVAILABLE
        return readWith(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun readWith(adapter: BluetoothAdapter): BluetoothState = when {
        !permissions.all(::granted) -> BluetoothState.NO_PERMISSION
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true -> BluetoothState.NO_LOCATION_SERVICE
        !runCatching { adapter.isEnabled }.getOrDefault(false) -> BluetoothState.OFF
        else -> BluetoothState.READY
    }

    /** Einschalten – ab Android 13 nur als Geräteeigentümer erlaubt. @return false, wenn die Oberfläche fragen muss. */
    @SuppressLint("MissingPermission")
    fun enable(): Boolean {
        if (_state.value == BluetoothState.NO_LOCATION_SERVICE) return kiosk.setLocationEnabled(true).also { refresh() }
        if (!kiosk.isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        @Suppress("DEPRECATION")
        return runCatching { adapter?.enable() == true }.getOrDefault(false)
    }
}
