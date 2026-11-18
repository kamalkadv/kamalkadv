// KioskLock edisi Device Owner: di-build dengan Android SDK biasa (tanpa AOSP).
// Kode diambil dari vendor/kioskos (src/ + src-dpc/ + libs/common), tidak diduplikasi.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "kioskos-dpc"
include(":app")
