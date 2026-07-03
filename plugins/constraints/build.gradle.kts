plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("all-open-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.aaps.plugins.constraints"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:compose"))
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))

    testImplementation(project(":implementation"))
    testImplementation(project(":pump:insight"))
    testImplementation(project(":plugins:aps"))
    testImplementation(project(":plugins:source"))
    testImplementation(project(":pump:dana"))
    testImplementation(project(":pump:danar"))
    testImplementation(project(":pump:danars"))
    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:impl"))
    testImplementation(project(":shared:tests"))

    api(libs.kotlinx.datetime)

    // Phone checker
    api(libs.com.scottyab.rootbeer.lib)

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)

    // Compose (redesigned objectives journey overlay)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
}