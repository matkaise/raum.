plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// Das Projekt liegt ggf. in einem iCloud-synchronisierten Ordner (Schreibtisch).
// iCloud erzeugt sonst Duplikate wie "Foo 2.class" in Build-Ausgaben; Ordner mit
// der Endung ".nosync" werden nicht synchronisiert.
allprojects {
    layout.buildDirectory.set(layout.projectDirectory.dir("build.nosync"))
}
