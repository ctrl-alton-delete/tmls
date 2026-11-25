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
        // Termux's terminal-emulator (M1 spike; vendored in M2, spec Q1)
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}
rootProject.name = "tmls-android"
include(":app")
