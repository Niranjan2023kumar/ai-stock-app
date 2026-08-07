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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AI Stock Intelligence"

include(":app")
include(":core")
include(":network")
include(":database")
include(":marketdata")
include(":scanner")
include(":ai")
include(":news")
include(":strategy")
include(":risk")
include(":portfolio")
include(":backtesting")
include(":notifications")
include(":analytics")
include(":reporting")
include(":testing")
