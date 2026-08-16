plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdk = Versions.compileSdk
    defaultConfig {
        multiDexEnabled = true
        versionCode = Versions.versionCode
        version = Versions.appVersion

        // Removed after Dagger injection setup in instrumentation tests
        //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
        named("debug") {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        // The build that actually drives the pump.
        //
        // Identical to `debug` in every way that matters for looping EXCEPT `isDebuggable`, because
        // Android refuses to ahead-of-time compile a debuggable package: `cmd package compile -m speed`
        // silently downgrades to `verify` and the app runs `status=run-from-apk`, JIT-ing all 30-odd
        // dex files forever (measured: 84 MB PSS of "code" + 47 MB of JIT state).
        //
        // It is signed with the SAME debug keystore, which is the whole point: the signature matches
        // the installed app, so `adb install -r` is an update rather than a reinstall, /data survives,
        // and `ypso_ble_state.xml` keeps the pump shared key. No re-pairing, no re-key, no keybox spend.
        // NEVER give this a different signingConfig — that turns every install into an uninstall and
        // burns a 28-day key.
        create("loop") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        buildTypes {
            create("benchmark") {
                initWith(buildTypes.getByName("release"))
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += listOf("release")
                isDebuggable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.time.ExperimentalTime"
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
    }
}