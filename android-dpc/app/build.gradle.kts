plugins {
    id("com.android.application")
}

val kiosk = "../../vendor/kioskos/apps/KioskLock"
val common = "../../vendor/kioskos/libs/common"

android {
    namespace = "id.kioskos.lock"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.kioskos.lock"
        minSdk = 30          // DevicePolicyManager.setLocationEnabled, WifiConfiguration.setSecurityParams
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dpc"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs("$kiosk/src", "$kiosk/src-dpc", "$common/src")
            res.srcDirs("$kiosk/res")
        }
        // src/debug/res/values/server.xml (dibuat scripts/40-dpc-try.sh, tidak di-commit)
        // menimpa URL server & public key untuk pengujian di emulator.
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
