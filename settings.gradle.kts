pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
    jcenter()
    maven("https://jitpack.io")
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    jcenter()
    maven("https://jitpack.io")
  }
}

rootProject.name = "Flyme-Freeform"

include(":app", ":hidden-api")