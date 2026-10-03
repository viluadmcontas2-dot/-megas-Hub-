pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "omegas-hub"
include(":core")
// Sem Android SDK (máquina de desenvolvimento), só o :core: OMEGAS_CORE_ONLY=1 ./gradlew :core:test
if (System.getenv("OMEGAS_CORE_ONLY") == null) include(":app")
