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
        // FIX: Add JitPack repository with explicit content filtering
        maven {
            url = uri("https://jitpack.io")
            content {
                // This tells Gradle to only look for dependencies from "com.github" here
                includeGroupByRegex("com\\.github.*")
            }
        }
    }
}

rootProject.name = "BackgroundCamera"
include(":app")