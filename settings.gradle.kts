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
	maven("https://jitpack.io")
	maven("https://repo1.maven.org/")
    }
}

rootProject.name = "vienna server for android"
include(":android")
