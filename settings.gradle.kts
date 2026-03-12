pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Hyperborea"
include(":app")
include(":core")
include(":hardware:fitpro")
include(":broadcast:ftms")
include(":broadcast:wifi")
include(":ecosystem:ifit")
include(":sensor:hrm")
