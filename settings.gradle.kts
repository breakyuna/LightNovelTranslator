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

rootProject.name = "Novel Translator"

include(":app")

// Native LG rebuild. Old app remains the migration source until the device gates pass.
include(":core:domain", ":core:engine", ":core:agent", ":core:storage", ":core:formats", ":core:workspace")
