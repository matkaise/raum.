# Projektspezifische R8-Regeln

# Matter-SDK: der native Teil (JNI) sucht Klassen, Felder und Methoden über ihren Namen
-keep class chip.** { *; }
-keep class matter.** { *; }
# Eigener Schlüsselspeicher, vom SDK per JNI aufgerufen (get/set/delete)
-keep class app.raum.matter.chip.EncryptedKeyValueStore { public *; }
# Bridge: native Methoden und Rückrufe aus libRaumBridge.so (JNI sucht sie über den Namen)
-keep class app.raum.matter.bridge.NativeBridge { *; }
-keep class app.raum.matter.bridge.BridgeConfigurationManager { public *; }
-dontwarn javax.annotation.**
-dontwarn chip.**
