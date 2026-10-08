pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "DieselBridge"

// The Wear OS app contains the Diesel platform and BLE-peripheral bridge.
// M6 adds a separate phone companion application. Stock, unmodified Gadgetbridge remains
// the only phone-side BLE owner; the companion must not acquire its own watch BLE link.
include(":watch")
include(":phone")
