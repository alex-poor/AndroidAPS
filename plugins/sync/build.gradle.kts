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

    // OpenHuman
    api(libs.com.squareup.okhttp3.okhttp)
    api(libs.com.squareup.retrofit2.retrofit)
    api(libs.androidx.browser)
    api(libs.androidx.work.runtime)
    api(libs.androidx.gridlayout)
    api(libs.com.google.android.material)

    // NSClient, Tidepool
    api(libs.io.socket.client)
    api(libs.com.squareup.okhttp3.logging.interceptor)
    api(libs.com.squareup.retrofit2.adapter.rxjava3)
    api(libs.com.squareup.retrofit2.converter.gson)
    api(libs.com.google.code.gson)
    api(libs.net.openid.appauth)

    // DataLayerListenerService

    // Garmin
    api(libs.com.garmin.connectiq) { artifact { type = "aar" } }
    androidTestImplementation(libs.com.garmin.connectiq) { artifact { type = "aar" } }

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