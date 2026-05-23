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
        maven("https://andob.io/repository/open_source")
        maven("https://jcenter.bintray.com")
        maven("https://jitpack.io")
    }
}

rootProject.name = "Vienna-Server-Android"
include(":app")
