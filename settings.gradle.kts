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

rootProject.name = "aloyo"

include(":app")
include(":common")
include(":core-inference")
include(":core-capture")
include(":core-overlay")
include(":core-model")
include(":core-logger")
