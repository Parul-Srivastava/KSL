// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// If you're using a multi-project setup, include your subprojects here
include(":app")
