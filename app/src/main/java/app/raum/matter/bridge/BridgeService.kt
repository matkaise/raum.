package app.raum.matter.bridge

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import app.raum.matter.chip.EncryptedKeyValueStore
import app.raum.matter.commissioning.SetupCodeGenerator
import chip.platform.AndroidBleManager
import chip.platform.AndroidChipPlatform
import chip.platform.AndroidNfcCommissioningManager
import chip.platform.ChipMdnsCallbackImpl
import chip.platform.DiagnosticDataProviderImpl
import chip.platform.NsdManagerServiceBrowser
import chip.platform.NsdManagerServiceResolver
import kotlinx.serialization.json.Json

/**
 * raum. als Matter-Bridge – läuft im eigenen Prozess „:bridge“ mit eigenem Matter-Stack (libRaumBridge.so).
 * Der Hauptprozess bindet den Dienst und spricht per [Messenger] mit ihm (siehe [BridgeMessages]).
 * Die Bridge-Identität (Fabrics anderer Apps, Schlüssel) liegt verschlüsselt in einem eigenen Keystore-Speicher.
 */
class BridgeService : Service() {

    private val worker = HandlerThread("raum-bridge").apply { start() }
    private val handler = Handler(worker.looper) { handle(it); true }
    private val json = Json { ignoreUnknownKeys = true }

    private var platform: AndroidChipPlatform? = null
    private var attestation: AttestationStore? = null
    @Volatile private var started = false
    private var client: Messenger? = null
    /** Aktuell angelegte Endpunkte */
    private val entries = mutableMapOf<Int, BridgeEntry>()
    /** Helligkeit kommt beim Dimmen in vielen Schritten – nur den letzten Wert weitergeben */
    private val pendingLevel = mutableMapOf<Int, Runnable>()

    override fun onCreate() {
        super.onCreate()
        // Plattform auf dem Hauptthread anlegen (wie in den SDK-Beispielen), alles Weitere im Arbeitsthread
        runCatching {
            NativeBridge.load()
            platform = AndroidChipPlatform(
                AndroidBleManager(this),
                AndroidNfcCommissioningManager(),
                EncryptedKeyValueStore(this, STORE, legacyName = null, alias = ALIAS),
                BridgeConfigurationManager(this),
                NsdManagerServiceResolver(this, NsdManagerServiceResolver.NsdManagerResolverAvailState()),
                NsdManagerServiceBrowser(this),
                ChipMdnsCallbackImpl(),
                DiagnosticDataProviderImpl(this),
            )
            // Startwerte – ein Kopplungsfenster bekommt jeweils einen neuen, einmaligen Code
            setCommissionableData()
            NativeBridge.listener = listener
            attestation = AttestationStore(this, BridgeConfigurationManager.VENDOR_ID, BridgeConfigurationManager.PRODUCT_ID.toInt())
            NativeBridge.attestation = attestation?.load()
        }.onFailure { Log.e(TAG, "Bridge-Plattform nicht verfügbar", it) }
        handler.post {
            started = platform != null && runCatching {
                NativeBridge.start(
                    BridgeConfigurationManager.VENDOR_ID, BridgeConfigurationManager.PRODUCT_ID.toInt(),
                    app.raum.BuildConfig.MATTER_VENDOR_NAME.take(32), app.raum.BuildConfig.BRIDGE_PRODUCT_NAME.take(32),
                    NativeBridge.attestation != null,
                )
            }.getOrDefault(false)
            Log.i(TAG, "Bridge gestartet: $started, Echtheitsnachweis: ${attestation?.status}")
            sendState()
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    override fun onDestroy() {
        // Der Matter-Server lässt sich nicht sauber beenden – der Prozess endet mit dem Dienst
        // (Bridge aus = keine Ankündigung im Netz mehr).
        Process.killProcess(Process.myPid())
    }

    private val listener = object : NativeBridge.Listener {
        override fun onOnOff(endpoint: Int, on: Boolean) {
            handler.post { sendCommand(endpoint, BridgeMessages.TYPE_ONOFF, if (on) 1 else 0) }
        }
        override fun onLevel(endpoint: Int, level: Int) {
            handler.post {
                pendingLevel.remove(endpoint)?.let(handler::removeCallbacks)
                val r = Runnable { pendingLevel.remove(endpoint); sendCommand(endpoint, BridgeMessages.TYPE_LEVEL, level) }
                pendingLevel[endpoint] = r
                handler.postDelayed(r, LEVEL_DEBOUNCE_MS)
            }
        }
        override fun onCommand(endpoint: Int, type: Int, value: Int) {
            handler.post { sendCommand(endpoint, BridgeMessages.TYPE_OTHER, value, type) }
        }
        override fun onFabricsChanged() { handler.post { sendState() } }
    }

    private fun setCommissionableData(): Pair<Long, Int>? {
        val p = platform ?: return null
        val passcode = SetupCodeGenerator.randomPasscode()
        val discriminator = SetupCodeGenerator.randomDiscriminator()
        // Ohne Verifier/Salt berechnet die Plattform beides selbst aus dem Code
        return if (p.updateCommissionableDataProviderData(null, null, 0, passcode, discriminator)) passcode to discriminator else null
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            BridgeMessages.REGISTER -> { client = msg.replyTo; sendState() }
            BridgeMessages.SET_DEVICES -> msg.data.getString(BridgeMessages.KEY_JSON)?.let { apply(json.decodeFromString<List<BridgeEntry>>(it)) }
            BridgeMessages.OPEN_WINDOW -> openWindow(msg.arg1)
            BridgeMessages.CLOSE_WINDOW -> { if (started) NativeBridge.closeWindow(); sendState() }
            BridgeMessages.REMOVE_FABRIC -> { if (started) NativeBridge.removeFabric(msg.arg1); sendState() }
            BridgeMessages.RESET -> {
                if (started) {
                    NativeBridge.closeWindow()
                    fabrics().forEach { NativeBridge.removeFabric(it.index) }
                }
                sendState()
            }
        }
    }

    private fun apply(list: List<BridgeEntry>) {
        if (!started) return
        val wanted = list.associateBy { it.endpoint }
        (entries.keys - wanted.keys).forEach { ep -> NativeBridge.removeDevice(ep); entries.remove(ep) }
        wanted.values.forEach { e ->
            val old = entries[e.endpoint]
            val identityChanged = old == null || old.kind != e.kind || old.uniqueId != e.uniqueId ||
                old.name != e.name || old.vendor != e.vendor || old.product != e.product ||
                old.flags != e.flags || old.minC100 != e.minC100 || old.maxC100 != e.maxC100
            if (identityChanged) {
                if (old != null) NativeBridge.removeDevice(e.endpoint)
                if (!NativeBridge.addDevice(e.endpoint, e.kind, e.uniqueId, e.name, e.vendor, e.product, e.flags, e.minC100, e.maxC100)) {
                    Log.w(TAG, "Endpunkt ${e.endpoint} nicht angelegt"); entries.remove(e.endpoint); return@forEach
                }
            }
            if (identityChanged || old != e) {
                NativeBridge.updateDevice(
                    e.endpoint, e.reachable, e.on, e.level, e.contact, e.occupied, e.tempC100, e.humidity100,
                    e.setpointC100, e.systemMode, e.coverClosed100ths, e.coverMovement,
                    e.colorMode, e.hue, e.saturation, e.colorX, e.colorY, e.mireds,
                )
            }
            entries[e.endpoint] = e
        }
    }

    private fun openWindow(seconds: Int) {
        val data = setCommissionableData()
        val ok = started && data != null && NativeBridge.openWindow(seconds)
        reply(Message.obtain(null, BridgeMessages.WINDOW, if (ok) 1 else 0, 0).apply {
            this.data = Bundle().apply {
                putLong(BridgeMessages.KEY_PASSCODE, data?.first ?: 0)
                putInt(BridgeMessages.KEY_DISCRIMINATOR, data?.second ?: 0)
                putInt(BridgeMessages.KEY_SECONDS, seconds)
            }
        })
        sendState()
        // Nach Ablauf den Status erneut melden (Fenster zu)
        handler.postDelayed({ sendState() }, seconds * 1000L + 1000)
    }

    private fun fabrics(): List<BridgeFabric> =
        if (!started) emptyList() else runCatching { json.decodeFromString<List<BridgeFabric>>(NativeBridge.fabrics()) }.getOrDefault(emptyList())

    private fun sendState() {
        reply(Message.obtain(null, BridgeMessages.STATE).apply {
            data = Bundle().apply {
                putBoolean(BridgeMessages.KEY_RUNNING, started)
                putString(BridgeMessages.KEY_FABRICS, json.encodeToString(fabrics()))
                putBoolean(BridgeMessages.KEY_WINDOW_OPEN, started && NativeBridge.isWindowOpen())
                putString(BridgeMessages.KEY_ATTESTATION, attestation?.status?.name)
            }
        })
    }

    private fun sendCommand(endpoint: Int, type: String, value: Int, cmd: Int = 0) {
        reply(Message.obtain(null, BridgeMessages.COMMAND, endpoint, 0).apply {
            data = Bundle().apply {
                putString(BridgeMessages.KEY_TYPE, type); putInt(BridgeMessages.KEY_VALUE, value); putInt(BridgeMessages.KEY_CMD, cmd)
            }
        })
    }

    private fun reply(msg: Message) {
        runCatching { client?.send(msg) }.onFailure { client = null }
    }

    private companion object {
        const val TAG = "raum.bridge"
        const val STORE = "raum_bridge_kvs"
        const val ALIAS = "raum_bridge_kvs_v1"
        const val LEVEL_DEBOUNCE_MS = 400L
    }
}
