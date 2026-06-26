pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "neuro-synapse"
include(":app", ":data", ":domain")
