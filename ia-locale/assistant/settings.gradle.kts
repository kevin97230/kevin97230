pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
    plugins {
        kotlin("jvm") version "2.3.10"
        kotlin("android") version "2.3.10"
        id("com.android.application") version "8.11.1"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

rootProject.name = "assistant"

// Cœur de l'IA (Kotlin pur, testable partout) et simulateur pour ordinateur.
include(":core", ":simulator")

// L'application Android n'est incluse que si un SDK Android est disponible
// (Android Studio crée automatiquement local.properties avec sdk.dir).
val androidSdk: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")

if (androidSdk != null) {
    include(":android-app")
} else {
    println("ℹ️  SDK Android introuvable : module :android-app ignoré (ouvrez le projet dans Android Studio).")
}
