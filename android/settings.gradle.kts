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
    }
}
rootProject.name = "tmls-android"
include(":app")
// Termux's terminal libraries, vendored (spec Q1): third_party/termux/PATCHES.md
include(":terminal-emulator", ":terminal-view", ":extrakeys")
project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
project(":extrakeys").projectDir = file("third_party/termux/extrakeys")
