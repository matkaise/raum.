import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.raum"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.raum.panel"
        // Android 11 ist die Mindestanforderung für alternative Panels (Spez. 3.3).
        minSdk = 30
        targetSdk = 37
        // Matter-SDK (connectedhomeip) ist nur für arm64 gebaut – siehe tools/build-matter-sdk.sh
        ndk { abiFilters += "arm64-v8a" }
        // Überschreibbar für Update-Tests: ./gradlew assembleDebug -PraumVersionCode=8
        versionCode = (project.findProperty("raumVersionCode") as String?)?.toInt() ?: 7
        // Kontakt im User-Agent für api.met.no (Nutzungsbedingungen) – vor Auslieferung setzen:
        // gradle.properties: raumWeatherContact=mailto:betrieb@example.com  oder  https://…
        buildConfigField("String", "WEATHER_CONTACT", "\"${project.findProperty("raumWeatherContact") ?: ""}\"")
        // Matter-Hersteller: ohne Angabe die Test-ID der CSA (0xFFF1) – vor Auslieferung eigene ID (docs/VENDOR.md).
        // gradle.properties: raumVendorId=0x1234, raumVendorName=…, raumBridgeProductId=0x0001, raumBridgeProductName=…
        fun hexProp(name: String, default: Int): Int =
            (project.findProperty(name) as String?)?.trim()?.let { v -> if (v.startsWith("0x", true)) v.substring(2).toInt(16) else v.toInt() } ?: default
        val vendorId = hexProp("raumVendorId", 0xFFF1)
        val bridgeProductId = hexProp("raumBridgeProductId", 0x8000)
        require(vendorId in 1..0xFFF4) { "raumVendorId außerhalb des Matter-Bereichs" }
        require(bridgeProductId in 1..0xFFFF) { "raumBridgeProductId ungültig" }
        buildConfigField("int", "MATTER_VENDOR_ID", vendorId.toString())
        buildConfigField("String", "MATTER_VENDOR_NAME", "\"${project.findProperty("raumVendorName") ?: "raum."}\"")
        buildConfigField("int", "BRIDGE_PRODUCT_ID", bridgeProductId.toString())
        buildConfigField("String", "BRIDGE_PRODUCT_NAME", "\"${project.findProperty("raumBridgeProductName") ?: "raum. Bridge"}\"")
        versionName = "0.7.0-m7" + ((project.findProperty("raumVersionCode") as String?)?.let { "+$it" } ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release-Signatur (Spez. 11.4) aus keystore.properties – nie einchecken.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // Exportierte Room-Schemata für Migrationstests (Spez. 12.3: versionierte Datenbank)
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR-Code für Multi-Admin-Kopplung (Apache-2.0)
    implementation(libs.zxing.core)
    // Matter-SDK (connectedhomeip v1.6.0.0, Apache-2.0), selbst gebaut – tools/build-matter-sdk.sh
    implementation(fileTree("libs/matter") { include("*.jar") })
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test.android)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
