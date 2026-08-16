plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.aaps.plugins.aps"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:compose"))
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(libs.androidx.swiperefreshlayout)
    api(libs.androidx.gridlayout)
    api(kotlin("reflect"))

    // APS (it should be androidTestImplementation but it doesn't work)
    api(libs.org.mozilla.rhino)

    //Logger
    api(libs.org.slf4j.api)

    ksp(libs.com.google.dagger.android.processor)

    // Compose (redesigned algorithm screen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
}