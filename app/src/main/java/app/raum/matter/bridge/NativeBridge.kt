package app.raum.matter.bridge

/**
 * JNI zu libRaumBridge.so (native/raum-bridge). Nur im Prozess „:bridge“ laden – der Controller im
 * Hauptprozess bringt einen eigenen Matter-Stack mit, beide in einem Prozess würden sich stören.
 * Namen und Signaturen der Rückrufe sind in BridgeServer.cpp festgelegt.
 */
object NativeBridge {

    interface Listener {
        fun onOnOff(endpoint: Int, on: Boolean)
        fun onLevel(endpoint: Int, level: Int)
        /** Thermostat/Storen – [type] siehe BridgeMessages.CMD_* */
        fun onCommand(endpoint: Int, type: Int, value: Int)
        fun onFabricsChanged()
    }

    @Volatile var listener: Listener? = null

    fun load() = System.loadLibrary("RaumBridge")

    /** Eigene Echtheitszertifikate; null = Testzertifikate des SDK */
    @Volatile var attestation: AttestationStore.Credentials? = null

    @JvmStatic external fun start(vendorId: Int, productId: Int, vendorName: String, productName: String, customAttestation: Boolean): Boolean
    /** [flags]: Bit 0 Heizen, Bit 1 Kühlen, Bit 2 Auto (Thermostat), Bit 3 Farbe, Bit 4 Farbtemperatur (Leuchte).
     *  Grenzen: Thermostat in 0,01 °C, Leuchte in Mired. */
    @JvmStatic external fun addDevice(
        endpoint: Int, kind: Int, uniqueId: String, name: String, vendor: String, product: String, flags: Int, minC100: Int, maxC100: Int,
    ): Boolean
    @JvmStatic external fun removeDevice(endpoint: Int)
    @JvmStatic external fun updateDevice(
        endpoint: Int, reachable: Boolean, on: Boolean, level: Int, contact: Boolean, occupied: Boolean, tempC100: Int, humidity100: Int,
        setpointC100: Int, systemMode: Int, coverClosed100ths: Int, coverMovement: Int,
        colorMode: Int, hue: Int, saturation: Int, colorX: Int, colorY: Int, mireds: Int,
    )
    @JvmStatic external fun openWindow(timeoutSeconds: Int): Boolean
    @JvmStatic external fun closeWindow()
    @JvmStatic external fun isWindowOpen(): Boolean
    /** JSON: [{"index":1,"vendorId":4937,"label":"…"}] */
    @JvmStatic external fun fabrics(): String
    @JvmStatic external fun removeFabric(index: Int): Boolean

    // --- Rückrufe aus dem Matter-Thread (nicht blockieren) -------------------------------------------------------

    @JvmStatic fun onOnOff(endpoint: Int, on: Boolean) { listener?.onOnOff(endpoint, on) }
    @JvmStatic fun onLevel(endpoint: Int, level: Int) { listener?.onLevel(endpoint, level) }
    @JvmStatic fun onCommand(endpoint: Int, type: Int, value: Int) { listener?.onCommand(endpoint, type, value) }
    @JvmStatic fun onFabricsChanged() { listener?.onFabricsChanged() }

    // --- Echtheitsnachweis (vom Matter-Thread, ohne Stapelsperre) ------------------------------------------------

    @JvmStatic fun attestationCd(): ByteArray? = attestation?.cd
    @JvmStatic fun attestationDac(): ByteArray? = attestation?.dac
    @JvmStatic fun attestationPai(): ByteArray? = attestation?.pai
    @JvmStatic fun attestationSign(message: ByteArray): ByteArray? = attestation?.sign(message)
}
