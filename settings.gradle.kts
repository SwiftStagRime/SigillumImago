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

rootProject.name = "SigillumImago"
include(":app")
include(":core_ui")
include(":core_common")
include(":core_domain")
include(":core_data_api")
include(":core_data_impl")
include(":feature_camera")
include(":feature_gallery")
include(":feature_settings")
include(":feature_auth")
include(":feature_home")
include(":feature_recorder")
include(":feature_recordings")
