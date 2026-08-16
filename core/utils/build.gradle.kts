import kotlin.math.min

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    kotlin("plugin.allopen")
    id("android-module-dependencies")
    id("all-open-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.core.utils"
    defaultConfig {
        minSdk = min(Versions.minSdk, Versions.wearMinSdk)
    }
}

dependencies {

    api(libs.net.danlew.android.joda)

    // Firebase removed: this is a personal single-user loop, so Analytics and Crashlytics only
    // shipped health telemetry off-device for reports nobody reads. FabricPrivacyImpl now logs
    // the same information to AndroidAPS.log. Cost recovered: ~4.9 MB of dex (GMS + Firebase +
    // datatransport), 6 background threads, and two telemetry databases.

    //CryptoUtil
    api(libs.com.madgag.spongycastle)
    api(libs.com.google.crypto.tink)

    //WorkManager
    api(libs.androidx.work.runtime) // DataWorkerStorage

    api(libs.com.google.dagger.android)
    api(libs.com.google.dagger.android.support)
}