import kotlin.math.min

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("kotlinx-serialization")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.aaps.core.compose"
    // Matches :core:ui, which now depends on this module for its dialogs. Keeping the floors equal
    // avoids "minSdk of the library is greater than the consumer" on every module that uses core:ui.
    defaultConfig {
        minSdk = min(Versions.minSdk, Versions.wearMinSdk)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:interfaces"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core)

    // The skin file format. Data only — no Android surface, so it stays in the design system
    // module next to the tokens it describes. `api` rather than `implementation` because SkinSpec is
    // @Serializable: its generated serializer() is public API referencing kotlinx types, so a module
    // that merely reads the class — Dagger in :app, resolving SkinStore — needs them on its
    // classpath too. Matches how :core:interfaces exposes the same library.
    api(platform(libs.kotlinx.serialization.bom))
    api(libs.kotlinx.serialization.json)
}
