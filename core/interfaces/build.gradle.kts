import kotlin.math.min

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {

    namespace = "app.aaps.core.interfaces"
    defaultConfig {
        minSdk = min(Versions.minSdk, Versions.wearMinSdk)
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:keys"))


    api(libs.androidx.appcompat)
    api(libs.androidx.preference)
    // DocumentFile appears in public signatures here (Storage, FileListProvider), so it must be
    // `api`. It used to arrive implicitly as a transitive of the Firebase/GMS stack; removing
    // Firebase surfaced that it was never declared by the modules that actually use it.
    api(libs.androidx.documentfile)

    api(platform(libs.kotlinx.serialization.bom))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.protobuf)

    api(libs.org.apache.commons.lang3)
    api(libs.net.danlew.android.joda)

    //RxBus
    api(libs.io.reactivex.rxjava3.rxkotlin)
    testImplementation(libs.io.reactivex.rxjava3.rxandroid)
}