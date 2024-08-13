pluginManagement {
    repositories {
        maven { url = uri("https://maven.google.com") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.google.com") }
        google()
        mavenCentral()
        jcenter()
        maven { url = uri("https://jitpack.io") }

    }
}
rootProject.name = "V2rayNG"
include(":app")
