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
        // ZODL zcash-android-sdk 5.2.1-SNAPSHOT (built 2026-10-14) lives only in the snapshot repo.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
            content { includeGroup("com.zodl.android") }
        }
    }
}
rootProject.name = "offline-zec-wallet"
include(":app")
