plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.common"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))

    // XStream backs PumpSyncStorage (pending bolus/TBR sync entries). Its mxparser transitive
    // drags in xmlpull:xmlpull, whose org.xmlpull.v1.XmlPullParser is ALSO part of android.jar —
    // R8 rejects that as "library class ... implements program class". The framework provides the
    // interface at runtime, so exclude the bundled copy and let mxparser bind against Android's.
    api(libs.com.thoughtworks.xstream) {
        exclude(group = "xmlpull", module = "xmlpull")
    }
    api(libs.com.google.code.gson)
    implementation(project(":core:keys"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}
