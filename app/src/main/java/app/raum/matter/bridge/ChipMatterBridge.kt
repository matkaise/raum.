package app.raum.matter.bridge

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.matter.commissioning.SetupCodeGenerator
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.PairingWindowResult
import app.raum.security.KeyValueStore
import app.raum.security.KeystoreCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Echte Bridge: raum. als Matter-Aggregator im Prozess „:bridge“ ([BridgeService]).
 * Vergibt dauerhaft stabile Endpunkte je Gerät (andere Apps erkennen Geräte daran wieder), überträgt
 * freigegebene Geräte samt Zustand und reicht Befehle anderer Apps als [BridgedCommand] weiter.
 */
class ChipMatterBridge(
    private val context: Context,
    private val store: KeyValueStore,
    private val clock: Clock = Clock.systemUTC(),
) : MatterBridge {

    private val _state = MutableStateFlow(BridgeState())
    override val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val commands = MutableSharedFlow<BridgedCommand>(extraBufferCapacity = 32)
    override val incomingCommands: Flow<BridgedCommand> = commands.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    @Volatile private var service: Messenger? = null
    private var bound = false
    /** Zuletzt übertragene Geräte (für Neuverbindung) und Endpunkt → Node */
    @Volatile private var lastEntries: List<BridgeEntry> = emptyList()
    @Volatile private var deviceByEndpoint: Map<Int, UUID> = emptyMap()
    private var pendingWindow: CompletableDeferred<Message>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = Messenger(binder)
            send(Message.obtain(null, BridgeMessages.REGISTER).apply { replyTo = incoming })
            sendDevices(lastEntries)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            // Bridge-Prozess beendet (Absturz) – Android startet ihn neu und verbindet erneut
            service = null
            _state.update { it.copy(running = false, window = null) }
        }
    }

    override suspend fun start() {
        main.post {
            if (bound) return@post
            bound = context.bindService(Intent(context, BridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) Log.e(TAG, "Bridge-Dienst nicht gestartet")
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
            service = null
            _state.update { it.copy(running = false, window = null) }
        }
    }

    // --- Geräte ----------------------------------------------------------------------------------------------------

    override fun expose(devices: List<Device>) {
        _state.update { it.copy(exposed = devices.filter { d -> BridgeMapping.kind(d) != null }.map { d -> d.id }.toSet()) }
    }

    override fun publish(devices: List<Device>) {
        val entries = devices.mapNotNull { d ->
            val kind = BridgeMapping.kind(d) ?: return@mapNotNull null
            d.id to BridgeMapping.entry(d, endpointFor(d.id.toString()), kind)
        }
        deviceByEndpoint = entries.associate { (id, e) -> e.endpoint to id }
        val list = entries.map { it.second }.sortedBy { it.endpoint }
        if (list == lastEntries) return
        lastEntries = list
        sendDevices(list)
    }

    /** Dauerhafte Endpunkt-Nummer je Gerät (ab 2; 0 = Wurzel, 1 = Aggregator). Nie wiederverwendet. */
    @Synchronized
    private fun endpointFor(deviceId: String): Int {
        val key = "bridge_ep_$deviceId"
        store.getString(key)?.toIntOrNull()?.let { return it }
        val next = (store.getString(KEY_NEXT_ENDPOINT)?.toIntOrNull() ?: FIRST_ENDPOINT)
        store.putString(KEY_NEXT_ENDPOINT, (next + 1).toString())
        store.putString(key, next.toString())
        return next
    }

    private fun sendDevices(list: List<BridgeEntry>) {
        send(Message.obtain(null, BridgeMessages.SET_DEVICES).apply {
            data = Bundle().apply { putString(BridgeMessages.KEY_JSON, json.encodeToString(list)) }
        })
    }

    // --- Kopplung und verbundene Apps ------------------------------------------------------------------------------

    override suspend fun openPairingWindow(timeout: Duration): PairingWindowResult {
        if (!_state.value.running) return PairingWindowResult.Failure(CommandFailure.OFFLINE)
        val reply = CompletableDeferred<Message>().also { pendingWindow = it }
        send(Message.obtain(null, BridgeMessages.OPEN_WINDOW, timeout.seconds.toInt(), 0))
        val msg = withTimeoutOrNull(10_000) { reply.await() } ?: return PairingWindowResult.Failure(CommandFailure.TIMEOUT)
        if (msg.arg1 != 1) return PairingWindowResult.Failure(CommandFailure.DEVICE_ERROR)
        val passcode = msg.data.getLong(BridgeMessages.KEY_PASSCODE)
        val discriminator = msg.data.getInt(BridgeMessages.KEY_DISCRIMINATOR)
        val window = PairingWindow(
            nodeId = 0u,
            manualCode = SetupCodeGenerator.manualCode(discriminator, passcode),
            qrPayload = SetupCodeGenerator.qrPayload(BridgeConfigurationManager.VENDOR_ID, BridgeConfigurationManager.PRODUCT_ID.toInt(), discriminator, passcode),
            expiresAt = clock.instant().plus(timeout),
        )
        _state.update { it.copy(window = window) }
        return PairingWindowResult.Open(window)
    }

    override suspend fun closePairingWindow() {
        send(Message.obtain(null, BridgeMessages.CLOSE_WINDOW))
        _state.update { it.copy(window = null) }
    }

    /** Erfolg erst, wenn die Bridge eine Fabric-Liste ohne diese App meldet (sie antwortet mit ihrem Zustand). */
    override suspend fun removeAdmin(fabricIndex: Int): CommandResult {
        if (service == null || !_state.value.running) return CommandResult.Failure(CommandFailure.OFFLINE)
        if (_state.value.admins.none { it.fabricIndex == fabricIndex }) return CommandResult.Success
        send(Message.obtain(null, BridgeMessages.REMOVE_FABRIC, fabricIndex, 0))
        return withTimeoutOrNull(REMOVE_TIMEOUT_MS) { state.first { s -> s.admins.none { it.fabricIndex == fabricIndex } } }
            ?.let { CommandResult.Success }
            ?: CommandResult.Failure(CommandFailure.TIMEOUT, "bridge did not confirm removal of fabric $fabricIndex")
    }

    /**
     * Werksreset der Bridge-Identität – unabhängig davon, ob die Bridge gerade läuft: Dienst trennen, Bridge-Prozess
     * beenden und seinen Speicher direkt löschen. Wirft, wenn sich das nicht bestätigen lässt; der Aufrufer darf den
     * Reset dann nicht als abgeschlossen behandeln – sonst könnten frühere Kopplungen den Reset überleben.
     */
    override suspend fun reset() {
        stop()
        withContext(Dispatchers.IO) {
            endBridgeProcess()
            // Nur der Bridge-Prozess nutzt diese Dateien – nach seinem Ende gefahrlos löschbar
            val wiped = listOf(BridgeService.STORE, BridgeConfigurationManager.PREFS).all { context.deleteSharedPreferences(it) }
            if (!wiped) throw BridgeResetException("bridge storage not deleted")
            // Schlüssel weg: selbst übersehene Reste des verschlüsselten Speichers sind unlesbar
            KeystoreCipher(BridgeService.ALIAS).deleteKey()
        }
        _state.update { it.copy(running = false, admins = emptyList(), window = null) }
    }

    /** Beendet den Prozess „:bridge“ (läuft er nicht, ist nichts zu tun) und wartet, bis er wirklich weg ist. */
    private suspend fun endBridgeProcess() {
        val am = context.getSystemService(ActivityManager::class.java)
        val name = context.packageName + BridgeService.PROCESS_SUFFIX
        fun pid() = am.runningAppProcesses.orEmpty().firstOrNull { it.processName == name }?.pid
        pid()?.let(Process::killProcess)
        withTimeoutOrNull(PROCESS_END_TIMEOUT_MS) { while (pid() != null) delay(50) }
            ?: throw BridgeResetException("bridge process still running")
    }

    /**
     * Echtheitszertifikate importieren (Inbetriebnahme, PIN-geschützt in der Oberfläche) und die Bridge neu starten,
     * damit sie sie verwendet. Andere Apps müssen die Bridge danach nicht neu koppeln.
     */
    suspend fun importAttestation(zip: java.io.InputStream): AttestationStore.ImportResult {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AttestationStore(context, BridgeConfigurationManager.VENDOR_ID, BridgeConfigurationManager.PRODUCT_ID.toInt()).import(zip)
        }
        if (result is AttestationStore.ImportResult.Ok && bound) { stop(); kotlinx.coroutines.delay(1500); start() }
        return result
    }

    private fun attestationStore() =
        AttestationStore(context, BridgeConfigurationManager.VENDOR_ID, BridgeConfigurationManager.PRODUCT_ID.toInt())

    /** Schlüssel im Keystore erzeugen, Zertifikatsanforderung (PEM) zurückgeben – das aktive Zertifikat bleibt gültig. */
    suspend fun createAttestationRequest(): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { attestationStore().createRequest() }

    /** Offene Zertifikatsanforderung seit … (null = keine) */
    fun pendingAttestationRequest(): java.time.Instant? = runCatching { attestationStore().pendingSince }.getOrNull()

    // --- Nachrichten aus dem Bridge-Prozess -------------------------------------------------------------------------

    private fun handle(msg: Message) {
        when (msg.what) {
            BridgeMessages.STATE -> {
                val fabrics = runCatching {
                    json.decodeFromString<List<BridgeFabric>>(msg.data.getString(BridgeMessages.KEY_FABRICS) ?: "[]")
                }.getOrDefault(emptyList())
                val windowOpen = msg.data.getBoolean(BridgeMessages.KEY_WINDOW_OPEN)
                _state.update { s ->
                    s.copy(
                        running = msg.data.getBoolean(BridgeMessages.KEY_RUNNING),
                        admins = fabrics.map { AdminFabric(it.index, it.vendorId, it.label, own = false) },
                        window = if (windowOpen) s.window else null,
                        attestation = msg.data.getString(BridgeMessages.KEY_ATTESTATION)
                            ?.let { a -> AttestationStatus.entries.firstOrNull { it.name == a } },
                    )
                }
            }
            BridgeMessages.WINDOW -> pendingWindow?.complete(Message.obtain(msg))
            BridgeMessages.COMMAND -> {
                val device = deviceByEndpoint[msg.arg1] ?: return
                val value = msg.data.getInt(BridgeMessages.KEY_VALUE)
                val current = lastEntries.firstOrNull { it.endpoint == msg.arg1 }
                val command = when (msg.data.getString(BridgeMessages.KEY_TYPE)) {
                    BridgeMessages.TYPE_ONOFF -> {
                        if (current?.on == (value == 1)) return // Echo des aktuellen Zustands
                        DeviceCommand.SetOn(value == 1)
                    }
                    BridgeMessages.TYPE_OTHER -> otherCommand(msg.data.getInt(BridgeMessages.KEY_CMD), value, current) ?: return
                    BridgeMessages.TYPE_LEVEL -> {
                        // Beim Einschalten meldet die Bridge die gespeicherte Helligkeit – kein neuer Wunsch
                        if (current != null && BridgeMapping.levelToPercent(current.level) == BridgeMapping.levelToPercent(value)) return
                        DeviceCommand.SetBrightness(BridgeMapping.levelToPercent(value))
                    }
                    else -> return
                }
                // Welche App den Befehl schickte, verrät der Server nicht; bei genau einer ist es eindeutig
                val vendor = _state.value.admins.singleOrNull()?.vendorId ?: 0
                commands.tryEmit(BridgedCommand(device, command, vendor))
            }
        }
    }

    /** Thermostat und Storen; null = Echo des aktuellen Zustands oder unbekannt */
    private fun otherCommand(cmd: Int, value: Int, current: BridgeEntry?): DeviceCommand? = when (cmd) {
        BridgeMessages.CMD_SETPOINT ->
            if (current?.setpointC100 == value) null else DeviceCommand.SetTargetTemperature(value / 100.0)
        BridgeMessages.CMD_SYSTEM_MODE ->
            if (current?.systemMode == value) null else BridgeMapping.fromMatterMode(value)?.let { DeviceCommand.SetThermostatMode(it) }
        BridgeMessages.CMD_COVER_OPEN -> DeviceCommand.OpenCover
        BridgeMessages.CMD_COVER_CLOSE -> DeviceCommand.CloseCover
        BridgeMessages.CMD_COVER_STOP -> DeviceCommand.StopCover
        BridgeMessages.CMD_COVER_GOTO -> DeviceCommand.SetCoverPosition(value.coerceIn(0, 100))
        BridgeMessages.CMD_COLOR_HS -> {
            val hue = (value shr 8) and 0xFF
            val sat = value and 0xFF
            if (current?.colorMode == 0 && current.hue == hue && current.saturation == sat) null
            else DeviceCommand.SetColor(BridgeMapping.hueSatToRgb(hue, sat))
        }
        BridgeMessages.CMD_COLOR_XY -> {
            val x = value ushr 16
            val y = value and 0xFFFF
            if (current?.colorMode == 0 && current.colorX == x && current.colorY == y) null
            else DeviceCommand.SetColor(ColorMath.xyToRgb(x, y))
        }
        BridgeMessages.CMD_COLOR_TEMP ->
            if (current?.colorMode == 2 && current.mireds == value) null
            else DeviceCommand.SetColorTemperature(ColorMath.miredsToKelvin(value))
        else -> null
    }

    private fun send(msg: Message) {
        val s = service ?: return
        runCatching { s.send(msg) }.onFailure { Log.w(TAG, "Bridge nicht erreichbar", it) }
    }

    private companion object {
        const val TAG = "raum.bridge"
        const val FIRST_ENDPOINT = 2
        const val KEY_NEXT_ENDPOINT = "bridge_next_endpoint"
        const val PROCESS_END_TIMEOUT_MS = 5_000L
        const val REMOVE_TIMEOUT_MS = 5_000L
    }
}
