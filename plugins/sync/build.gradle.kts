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
    namespace = "app.aaps.plugins.sync"
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
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))


    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)

    testImplementation(project(":shared:tests"))
    testImplementation(project(":implementation"))
    testImplementation(project(":plugins:aps"))
    androidTestImplementation(project(":shared:tests"))

    // NSClientV3. The retrofit / okhttp / gson stack arrives transitively from :core:nssdk,
    // which api()s it. The OpenHumans (appauth, browser) and Garmin (connectiq) stacks went with
    // those plugins; appauth in particular also contributed a manifest placeholder
    // (appAuthRedirectScheme) that had to be satisfied at merge time.
    api(libs.androidx.work.runtime)
    api(libs.com.google.android.material)
    // socket.io is NSClientV3's websocket transport (NSClientV3Service), not only the retired v1 client.
    api(libs.io.socket.client)

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)

    // Compose (redesigned connectivity & sync screen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.foundation)
}